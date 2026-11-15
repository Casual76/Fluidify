package dev.lelonio.square.auth

import android.app.Application
import android.content.Context
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real monitors and inline observer, bounded daemon workers; no provider, JNI or real credentials. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RootAccountTokenLockTest {
    @Test fun inlineAccountRevocationCanReadTokensWhileAnotherThreadMutatesTheirIdentity() {
        for (mutation in listOf("clear", "save", "saveIfCurrent")) {
            val context = RuntimeEnvironment.getApplication()
            val key = "root-lock-" + UUID.randomUUID()
            var receipt: String? = null
            val authority = LocalSpotifyAuthority({ receipt }, { receipt = it }, {
                LocalSpotifyAuthority.KeptIdentity("a", "synthetic-a")
            })
            val tokens = TokenStore(context.getSharedPreferences(key, Context.MODE_PRIVATE), key,
                { "synthetic-client" }, { _, _ -> error("no refresh") }, { 0L }, localAuthority = authority)
            val answer = SpotifyOAuth.Tokens("synthetic", "synthetic-refresh", Long.MAX_VALUE)
            tokens.save(answer)
            val expected = tokens.generation
            val accountHeld = CountDownLatch(1)
            val revoke = CountDownLatch(1)
            val observerRead = CountDownLatch(1)
            authority.observe { tokens.isLoggedIn; observerRead.countDown() }
            val executor = Executors.newFixedThreadPool(2) { task -> Thread(task, key).apply { isDaemon = true } }
            try {
                val main = executor.submit {
                    authority.locked {
                        accountHeld.countDown()
                        check(revoke.await(2, TimeUnit.SECONDS))
                        authority.invalidate() // The production observer reads isLoggedIn inline here.
                    }
                }
                assertTrue(accountHeld.await(2, TimeUnit.SECONDS))
                val entered = CountDownLatch(1)
                lateinit var mutatorThread: Thread
                val worker = executor.submit {
                    mutatorThread = Thread.currentThread()
                    entered.countDown()
                    when (mutation) {
                        "clear" -> tokens.clear()
                        "save" -> tokens.save(answer)
                        else -> tokens.saveIfCurrent(expected, answer)
                    }
                }
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (mutatorThread.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
                assertEquals("mutation must be waiting for the held account monitor", Thread.State.BLOCKED, mutatorThread.state)
                revoke.countDown()
                assertTrue("token monitor must remain readable during revocation: $mutation",
                    observerRead.await(2, TimeUnit.SECONDS))
                main.get(2, TimeUnit.SECONDS)
                worker.get(2, TimeUnit.SECONDS)
            } finally {
                revoke.countDown()
                executor.shutdownNow()
            }
        }
    }
}
