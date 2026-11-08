package dev.lelonio.square.auth

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Playback and Web API OAuth share one registered port. A replacement must wait
// for the previous owner's finally/close, including cancellation before accept.
private val oauthListenerOwner = Mutex()

internal suspend fun <T> withOAuthLoopbackReceiver(
    port: Int,
    doneMessage: String,
    block: suspend (OAuthLoopbackReceiver) -> T,
): T = oauthListenerOwner.withLock {
    withContext(Dispatchers.IO) {
        OAuthLoopbackReceiver(port, doneMessage).use { block(it) }
    }
}

/** Single request, IPv4 loopback only. Cancellation closes both accept and peer reads. */
internal class OAuthLoopbackReceiver(
    port: Int,
    doneMessage: String,
    private val onAccept: () -> Unit = {},
) : AutoCloseable {
    private val lock = Any()
    private var closed = false
    private var peer: Socket? = null
    private val server = ServerSocket().also {
        try {
            it.reuseAddress = true
            it.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 1)
            it.soTimeout = REDIRECT_TIMEOUT_MS
        } catch (error: Exception) {
            runCatching { it.close() }
            throw error
        }
    }
    val boundPort: Int = server.localPort
    val redirectUri = "http://127.0.0.1:$boundPort/login"
    private val response = buildString {
        val page = "<html><body><h2>$doneMessage</h2></body></html>"
        append("HTTP/1.1 200 OK\r\n")
        append("Content-Type: text/html; charset=utf-8\r\n")
        append("Content-Length: ${page.toByteArray(Charsets.UTF_8).size}\r\n")
        append("Connection: close\r\n\r\n")
        append(page)
    }

    suspend fun awaitRequest(): String = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            // Registered before any blocking I/O. The cancelling caller closes
            // sockets directly; it need not wait for an IO dispatcher thread.
            continuation.invokeOnCancellation { close() }
            try {
                continuation.resume(readRequest())
            } catch (error: Exception) {
                continuation.resumeWithException(error)
            }
        }
    }

    private fun readRequest(): String {
        val socket = server.accept()
        val accepted = synchronized(lock) {
            if (closed) false else { peer = socket; true }
        }
        if (!accepted) {
            socket.close()
            throw SocketException("OAuth receiver closed")
        }
        try {
            socket.soTimeout = REDIRECT_TIMEOUT_MS
            onAccept()
            val line = socket.getInputStream().bufferedReader(Charsets.UTF_8).readLine()
                ?: error("empty redirect request")
            val target = line.split(' ').getOrNull(1) ?: error("malformed redirect request")
            socket.getOutputStream().writer(Charsets.UTF_8).apply { write(response); flush() }
            return target
        } finally {
            synchronized(lock) { if (peer === socket) peer = null }
            socket.close()
        }
    }

    override fun close() {
        val active = synchronized(lock) {
            if (closed) return
            closed = true
            peer.also { peer = null }
        }
        runCatching { server.close() }
        runCatching { active?.close() }
    }

    private companion object {
        const val REDIRECT_TIMEOUT_MS = 5 * 60 * 1000
    }
}
