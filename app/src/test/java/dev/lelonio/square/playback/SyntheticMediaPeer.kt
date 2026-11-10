package dev.lelonio.square.playback

import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

internal class SyntheticMediaPeer(private val bytes: ByteArray, private val held: Boolean = false,
    private val redirect: Boolean = false) : AutoCloseable {
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    val url = "http://127.0.0.1:${server.localPort}/synthetic"
    val received = CountDownLatch(1)
    val release = CountDownLatch(if (held) 1 else 0)
    val requests = AtomicInteger()
    val afterOffline = AtomicInteger()
    @Volatile var lastRequest = ""
    private val worker = thread(isDaemon = true, name = "synthetic-media-peer") {
        try {
            while (!server.isClosed) server.accept().use { socket ->
                val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                val head = StringBuilder()
                var length = 0
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    head.appendLine(line)
                    if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                }
                repeat(length) { check(input.read() >= 0) }
                lastRequest = head.toString()
                val count = requests.incrementAndGet()
                if (dev.lelonio.square.data.SpotifyNetworkPolicy.manual.value) afterOffline.incrementAndGet()
                received.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                val response = if (redirect && count == 1)
                    "HTTP/1.1 302 Found\r\nLocation: $url/redirected\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    else "HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                try {
                    socket.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
                    if (!(redirect && count == 1)) socket.getOutputStream().write(bytes)
                } catch (_: java.io.IOException) { /* Revoked clients may close admitted work. */ }
            }
        } catch (_: SocketException) { }
    }
    override fun close() { release.countDown(); server.close(); worker.join(1_000); check(!worker.isAlive) }
}
