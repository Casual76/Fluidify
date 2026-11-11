package dev.lelonio.square.download

import dev.lelonio.square.data.SpotifyNetworkPolicy
import dev.lelonio.square.io.HttpFetch
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Production DownloadExtras -> HttpFetch, synthetic loopback only. */
class ManualOfflineReviewRedirectTest {
    @Test fun noNewRedirectRequestMayBeginAfterManualOfflineIsConfirmed() = runBlocking {
        val root = Files.createTempDirectory("critic-b-redirect-").toFile()
        val original = DownloadExtras.fetch
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 3000
        val first = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val offlineCalls = AtomicInteger()
        val peer = thread(isDaemon = true, name = "critic-b-loopback") {
            try {
                repeat(2) { requestIndex ->
                    server.accept().use { socket ->
                        val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        while (!input.readLine().isNullOrEmpty()) { }
                        calls.incrementAndGet()
                        if (SpotifyNetworkPolicy.manual.value) offlineCalls.incrementAndGet()
                        val response = if (requestIndex == 0) {
                            first.countDown()
                            check(release.await(5, TimeUnit.SECONDS))
                            "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:${server.localPort}/new-request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        } else {
                            "HTTP/1.1 200 OK\r\nContent-Length: 4\r\nConnection: close\r\n\r\ntest"
                        }
                        socket.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
                    }
                }
            } catch (_: java.net.SocketException) { }
              catch (_: java.net.SocketTimeoutException) { }
        }
        try {
            DownloadExtras.attach(root) { true }
            DownloadExtras.fetch = HttpFetch::toFile
            SpotifyNetworkPolicy.setManual(false)
            val work = async(Dispatchers.Default) {
                DownloadExtras.keep("http://127.0.0.1:${server.localPort}/initial", "video")
            }
            assertTrue("initial request reached loopback", first.await(5, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
            SpotifyNetworkPolicy.setManual(true)
            assertTrue(SpotifyNetworkPolicy.manual.value)
            release.countDown()
            assertNull("revoked download must not be committed", work.await())
            println("CRITIC_B redirect total=${calls.get()} afterConfirmedOffline=${offlineCalls.get()}")
            assertEquals("redirect must not admit new network work after manual offline", 0, offlineCalls.get())
        } finally {
            release.countDown(); server.close(); peer.join(1000)
            DownloadExtras.fetch = original
            SpotifyNetworkPolicy.setManual(false)
            root.deleteRecursively()
        }
    }
}
