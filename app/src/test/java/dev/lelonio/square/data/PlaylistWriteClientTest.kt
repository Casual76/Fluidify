package dev.lelonio.square.data

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test

/** Prepared, NOT EXECUTED. Real write client against loopback only, with a synthetic credential. */
class PlaylistWriteClientTest {
    private class Server(private val response: (Int) -> String?) : AutoCloseable {
        private val listener = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val url get() = "http://127.0.0.1:${listener.localPort}/playlist"
        val requests = AtomicInteger()
        @Volatile var headers = emptyList<String>()
        @Volatile private var active: Socket? = null
        private val worker = thread(isDaemon = true, name = "synthetic-playlist-server") {
            try {
                while (!listener.isClosed) {
                    listener.accept().use { socket ->
                        active = socket
                        val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val lines = mutableListOf<String>()
                        while (true) {
                            val line = input.readLine() ?: return@use
                            if (line.isEmpty()) break
                            lines += line
                        }
                        val size = lines.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                            ?.substringAfter(':')?.trim()?.toInt() ?: 0
                        repeat(size) { check(input.read() >= 0) }
                        headers = lines
                        val number = requests.incrementAndGet()
                        // null means the server received the body but the receipt was lost.
                        response(number)?.let { socket.getOutputStream().write(it.toByteArray(Charsets.US_ASCII)) }
                    }
                    active = null
                }
            } catch (_: SocketException) { /* test cleanup */ }
        }
        override fun close() {
            listener.close()
            active?.close()
            worker.join(1_000)
        }
    }

    private fun response(status: String, extra: String = "") =
        "HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n$extra\r\n"
    private fun request(url: String) = Request.Builder().url(url)
        .header(ApiFactory.SESSION_AUTH, PLAYLIST_EDIT_SCOPES)
        .post("{}".toRequestBody("application/json".toMediaType())).build()
    private fun OkHttpClient.close() { connectionPool.evictAll(); dispatcher.executorService.shutdownNow() }

    @Test fun refusedCredentialRateLimitAndTemporaryFailureDoNotReplayWrite() {
        for (status in listOf("401 Unauthorized", "403 Forbidden", "429 Too Many Requests", "503 Service Unavailable")) {
            Server { response(status, "Retry-After: 0\r\n") }.use { server ->
                val client = playlistWriteClient("synthetic-credential") { }
                try {
                    client.newCall(request(server.url)).execute().use { assertEquals(status.take(3).toInt(), it.code) }
                    assertEquals(status, 1, server.requests.get())
                } finally { client.close() }
            }
        }
    }

    @Test fun redirectDoesNotSendTheBodyAgain() {
        Server { response("307 Temporary Redirect", "Location: /redirected\r\n") }.use { server ->
            val client = playlistWriteClient("synthetic-credential") { }
            try {
                client.newCall(request(server.url)).execute().use { assertEquals(307, it.code) }
                assertEquals(1, server.requests.get())
            } finally { client.close() }
        }
    }

    @Test fun receivedBodyWithLostAcknowledgementIsNotRetried() {
        Server { null }.use { server ->
            val client = playlistWriteClient("synthetic-credential") { }
            try {
                try { client.newCall(request(server.url)).execute().close(); fail("Missing response was accepted") }
                catch (_: IOException) { }
                assertEquals(1, server.requests.get())
            } finally { client.close() }
        }
    }

    @Test fun authorityRevokedBeforeDispatchCannotSendBody() {
        Server { response("200 OK") }.use { server ->
            val client = playlistWriteClient("synthetic-credential") { throw CancellationException("Synthetic revocation") }
            try {
                try { client.newCall(request(server.url)).execute().close(); fail("Revoked write was sent") }
                catch (_: IOException) { }
                assertEquals(0, server.requests.get())
            } finally { client.close() }
        }
    }

    @Test fun receiptRevocationRejectsResponseAndInternalScopesNeverLeaveClient() {
        Server { response("200 OK") }.use { server ->
            var checks = 0
            val client = playlistWriteClient("synthetic-credential") {
                if (++checks == 2) throw CancellationException("Synthetic receipt revocation")
            }
            try {
                try { client.newCall(request(server.url)).execute().close(); fail("Revoked receipt was accepted") }
                catch (_: IOException) { }
                assertEquals(1, server.requests.get())
                assertTrue(server.headers.any { it == "Authorization: Bearer synthetic-credential" })
                assertFalse(server.headers.any { it.startsWith(ApiFactory.SESSION_AUTH, ignoreCase = true) })
            } finally { client.close() }
        }
    }
}
