package dev.lelonio.square.download

import dev.lelonio.square.data.AccountLocalDataFixture
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Prepared only: fake blocking JNI, no native library or real device. */
class AccountNativeTransferTest {
    @Test fun teardownWaitsForRealReturnAndSuppressesNewStagingUntilJoin() = runBlocking {
        val fixture = AccountLocalDataFixture()
        val a = fixture.connect()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        var shutdown = false
        var lateCancels = 0
        val native = AccountNativeTransfer(scope, fixture.accounts) {
            if (shutdown) lateCancels++
            cancelled.complete(Unit)
        }
        val work = scope.async {
            native.transfer(a, "spotify:track:synthetic") {
                entered.complete(Unit); release.await(); "synthetic-sidecar"
            }
        }
        try {
            withTimeout(2_000) { entered.await() }
            fixture.logout()
            val stopping = async(start = CoroutineStart.UNDISPATCHED) { native.stopAndJoin() }
            withTimeout(2_000) { cancelled.await() }
            assertFalse(stopping.isCompleted)
            val b = fixture.connect("account-b")
            var started = false
            try { native.transfer(b, "spotify:track:synthetic") { started = true }; fail("Overlapping native staging") }
            catch (_: IllegalStateException) { }
            assertFalse(started)
            release.countDown()
            withTimeout(2_000) { stopping.await() }
            shutdown = true
            try { work.await(); fail("Cancelled transfer returned a result") }
            catch (_: CancellationException) { }
            assertEquals(0, lateCancels)
        } finally { release.countDown(); scope.cancel() }
    }
}
