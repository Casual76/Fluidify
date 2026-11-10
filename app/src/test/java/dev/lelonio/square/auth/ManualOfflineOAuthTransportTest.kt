package dev.lelonio.square.auth

import dev.lelonio.square.data.SpotifyNetworkPolicy
import dev.lelonio.square.data.accountDataTestContext
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ManualOfflineOAuthTransportTest {
    @After fun reset() { SpotifyNetworkPolicy.setManual(false) }
    private class Peer(private val response: String, private val release: CountDownLatch = CountDownLatch(0)) : AutoCloseable {
        private val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${server.localPort}/token"
        val bodyReceived = CountDownLatch(1)
        val requests = AtomicInteger()
        private val worker = thread(isDaemon = true, name = "synthetic-oauth-peer") {
            try { while (!server.isClosed) server.accept().use { socket ->
                val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                var length = 0
                while (true) {
                    val line = input.readLine() ?: return@use
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                }
                repeat(length) { check(input.read() >= 0) }
                requests.incrementAndGet(); bodyReceived.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                socket.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
            } } catch (_: java.net.SocketException) { }
        }
        override fun close() { release.countDown(); server.close(); worker.join(1_000) }
    }
    private fun response(status: String, body: String = "", retry: String = "") =
        "HTTP/1.1 $status\r\nContent-Length: ${body.length}\r\nConnection: close\r\n$retry\r\n$body"

    @Test fun actualOAuthClientDoesNotReplay503AndManualPreflightAdmitsNothing() = runBlocking {
        SpotifyNetworkPolicy.setManual(false)
        val client = SpotifyOAuth.tokenClient()
        try {
            Peer(response("503 Service Unavailable", retry = "Retry-After: 0\r\n")).use { peer ->
                val permit = SpotifyNetworkPolicy.capture()
                assertTrue(runCatching { SpotifyOAuth.refreshUsing(client, peer.url, "synthetic", "synthetic-client", permit) }.isFailure)
                assertEquals(1, peer.requests.get())
                SpotifyNetworkPolicy.setManual(true)
                assertTrue(runCatching { SpotifyOAuth.refreshUsing(client, peer.url, "synthetic", "synthetic-client", permit) }
                    .exceptionOrNull() is SpotifyNetworkPolicy.Offline)
                assertEquals(1, peer.requests.get())
            }
        } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
    }

    @Test fun codeExchangeAfterBrowserWaitCannotRecaptureAcrossRapidOnOff() = runBlocking {
        SpotifyNetworkPolicy.setManual(false)
        val original = SpotifyNetworkPolicy.capture()
        val body = """{"access_token":"synthetic-access","expires_in":3600}"""
        Peer(response("200 OK", body)).use { peer ->
            val client = SpotifyOAuth.tokenClient()
            try {
                SpotifyNetworkPolicy.setManual(true); SpotifyNetworkPolicy.setManual(false)
                assertThrows(SpotifyNetworkPolicy.Offline::class.java) {
                    SpotifyOAuth.exchangeCode("synthetic-code", "synthetic-verifier", "http://127.0.0.1/synthetic",
                        "synthetic-client", original, client, peer.url)
                }
                assertEquals(0, peer.requests.get())
                val answer = SpotifyOAuth.exchangeCode("synthetic-code", "synthetic-verifier", "http://127.0.0.1/synthetic",
                    "synthetic-client", SpotifyNetworkPolicy.capture(), client, peer.url)
                assertEquals("synthetic-access", answer.accessToken)
                assertEquals(1, peer.requests.get())
            } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
        }
    }

    @Test fun realTokenResponseReceivedAfterRevocationPreservesRotationForSameAccountOnly() = runBlocking {
        SpotifyNetworkPolicy.setManual(false)
        val release = CountDownLatch(1)
        val body = """{"access_token":"synthetic-access","refresh_token":"synthetic-rotated","expires_in":3600}"""
        Peer(response("200 OK", body), release).use { peer ->
            val client = SpotifyOAuth.tokenClient()
            val prefs = accountDataTestContext().getSharedPreferences("synthetic-oauth", 0)
            prefs.edit().putString("refresh_token", "synthetic-old").commit()
            val tokens = TokenStore(prefs, UUID.randomUUID().toString(), { "synthetic-client" },
                { _, _ -> error("Must use the production admitted refresh path") }, { System.currentTimeMillis() },
                refreshAdmitted = { token, id, permit -> SpotifyOAuth.refreshUsing(client, peer.url, token, id, permit) })
            try {
                val work = async(Dispatchers.Default) { runCatching { tokens.validAccessToken() } }
                assertTrue(peer.bodyReceived.await(5, TimeUnit.SECONDS))
                SpotifyNetworkPolicy.setManual(true); SpotifyNetworkPolicy.setManual(false)
                release.countDown()
                assertTrue(work.await().exceptionOrNull() is SpotifyNetworkPolicy.Offline)
                assertEquals("synthetic-rotated", prefs.getString("refresh_token", null))
                assertEquals("synthetic-access", tokens.validAccessToken())
                assertEquals(1, peer.requests.get())
            } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
        }
    }
}
