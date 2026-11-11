package dev.lelonio.square.auth

import java.net.Socket
import java.net.SocketException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Real loopback sockets: no browser, Spotify, Android runtime or fixed production port. */
class OAuthLoopbackReceiverTest {
    @Test fun cancellationDuringAcceptReleasesPortAndPublishesNothing() = runBlocking {
        val receiver = OAuthLoopbackReceiver(0, "Done")
        val port = receiver.boundPort
        val entered = CompletableDeferred<Unit>()
        var published = false
        val waiter = launch(Dispatchers.IO) {
            entered.complete(Unit)
            receiver.awaitRequest()
            published = true
        }
        try {
            entered.await()
            withTimeout(2_000) { waiter.cancelAndJoin() }
            assertFalse(published)
            OAuthLoopbackReceiver(port, "Next").use { assertEquals(port, it.boundPort) }
        } finally { receiver.close() }
    }

    @Test fun cancellationDuringPartialRequestClosesAcceptedPeerAndReleasesPort() = runBlocking {
        val accepted = CompletableDeferred<Unit>()
        val receiver = OAuthLoopbackReceiver(0, "Done", onAccept = { accepted.complete(Unit) })
        val waiter = launch(Dispatchers.IO) { receiver.awaitRequest(); fail("partial request published") }
        try {
            Socket("127.0.0.1", receiver.boundPort).use { browser ->
                browser.soTimeout = 2_000
                browser.getOutputStream().apply { write("GET /login?code=incomplete".toByteArray()); flush() }
                withTimeout(2_000) { accepted.await(); waiter.cancelAndJoin() }
                val peerClosed = try { browser.getInputStream().read() == -1 } catch (_: SocketException) { true }
                assertTrue("Cancellation must close the accepted socket too", peerClosed)
                OAuthLoopbackReceiver(receiver.boundPort, "Next").use { assertEquals(receiver.boundPort, it.boundPort) }
            }
        } finally { receiver.close() }
    }

    @Test fun replacementWaitsForCancelledOwnerBeforeBindingSamePort() = runBlocking {
        val port = CompletableDeferred<Int>()
        val cleanup = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val old = launch(Dispatchers.IO) {
            withOAuthLoopbackReceiver(0, "Old") { receiver ->
                port.complete(receiver.boundPort)
                try { awaitCancellation() }
                finally {
                    withContext(NonCancellable) { cleanup.complete(Unit); releaseCleanup.await() }
                }
            }
        }
        val bound = port.await()
        old.cancel()
        cleanup.await()
        val replacement = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            withOAuthLoopbackReceiver(bound, "New") { it.boundPort }
        }
        try {
            assertFalse("New bind must wait for the previous receiver's use/close", replacement.isCompleted)
            releaseCleanup.complete(Unit)
            withTimeout(2_000) { old.join(); assertEquals(bound, replacement.await()) }
        } finally { releaseCleanup.complete(Unit); old.cancelAndJoin(); replacement.cancelAndJoin() }
    }

    @Test fun completedRedirectReturnsTargetAndServesUtf8Response() = runBlocking {
        val receiver = OAuthLoopbackReceiver(0, "Puoi tornare all’app")
        val target = async(Dispatchers.IO) { receiver.awaitRequest() }
        try {
            val response = withContext(Dispatchers.IO) {
                Socket("127.0.0.1", receiver.boundPort).use { browser ->
                    browser.soTimeout = 2_000
                    browser.getOutputStream().apply {
                        write("GET /login?code=ok&state=expected HTTP/1.1\r\n\r\n".toByteArray())
                        flush()
                    }
                    browser.getInputStream().bufferedReader(Charsets.UTF_8).readText()
                }
            }
            assertEquals("/login?code=ok&state=expected", withTimeout(2_000) { target.await() })
            assertTrue(response.startsWith("HTTP/1.1 200 OK\r\n"))
            assertTrue(response.contains("Puoi tornare all’app"))
            val body = response.substringAfter("\r\n\r\n")
            assertTrue(response.contains("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n"))
        } finally { receiver.close(); target.cancelAndJoin() }
    }
}
