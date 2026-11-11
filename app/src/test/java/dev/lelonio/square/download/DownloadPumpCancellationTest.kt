package dev.lelonio.square.download

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

class DownloadPumpCancellationTest {
    private fun CountDownLatch.awaitProof() = assertTrue("Timed out waiting for barrier", await(5, SECONDS))

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun logoutBlocksLateWakeAfterJoinUntilNewAuthentication() = kotlinx.coroutines.test.runTest {
        var accountAllowed = true
        var calls = 0
        val pump = DownloadPump(backgroundScope, {}, {}, {}, canStart = { accountAllowed }) { calls++ }
        pump.wake()
        runCurrent()
        assertEquals(1, calls)
        accountAllowed = false
        pump.stopAndJoin()
        pump.wake()
        runCurrent()
        assertEquals("late observers cannot revive the logged-out account", 1, calls)
        accountAllowed = true
        pump.wake()
        runCurrent()
        assertEquals(2, calls)
    }

    @Test fun stopCancelsBlockingTransferAndWakeWaitsForRealReturn() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val enteredNew = CountDownLatch(1)
        val releaseNew = CountDownLatch(1)
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val runs = AtomicInteger()
        val finishes = AtomicInteger()
        val runningStatus = AtomicBoolean()
        val cancellationEscaped = AtomicBoolean()
        lateinit var pump: DownloadPump
        pump = DownloadPump(scope, { cancelled.countDown() },
            { runningStatus.set(true) }, { finishes.incrementAndGet(); runningStatus.set(false) }) {
            val run = runs.incrementAndGet()
            try {
                pump.transfer("same-uri") {
                    val count = active.incrementAndGet()
                    maxActive.updateAndGet { maxOf(it, count) }
                    try {
                        if (run == 1) {
                            entered.countDown()
                            cancelled.awaitProof()
                            releaseOld.awaitProof()
                        } else {
                            enteredNew.countDown()
                            releaseNew.awaitProof()
                        }
                    } finally { active.decrementAndGet() }
                }
            } catch (cancelled: CancellationException) {
                cancellationEscaped.set(true)
                throw cancelled
            }
        }
        try {
            pump.wake()
            entered.awaitProof()
            pump.stop()
            cancelled.awaitProof()
            pump.wake()
            assertEquals(1, runs.get())
            releaseOld.countDown()
            enteredNew.awaitProof()
            assertEquals(1, maxActive.get())
            assertTrue(cancellationEscaped.get())
            assertEquals("handoff must not emit transient idle or release the network watch", 0, finishes.get())
            assertTrue("old completion must not erase new status", runningStatus.get())
            releaseNew.countDown()
            pump.stopAndJoin()
            assertFalse(runningStatus.get())
        } finally {
            releaseOld.countDown(); releaseNew.countDown(); scope.cancel()
        }
    }

    @Test fun cancellationIsRepeatedAfterNativeEntryClearsEarlyFlagAndJoinSuppressesWake() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val atEntry = CountDownLatch(1)
        val earlyCancel = CountDownLatch(1)
        val resumeEntry = CountDownLatch(1)
        val laterCancel = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cleared = AtomicBoolean()
        val runs = AtomicInteger()
        lateinit var pump: DownloadPump
        pump = DownloadPump(scope, {
            if (cleared.get()) laterCancel.countDown() else earlyCancel.countDown()
        }, {}, {}) {
            runs.incrementAndGet()
            pump.transfer("uri") {
                atEntry.countDown()
                resumeEntry.awaitProof()
                // Simulates Rust's clear_cancel at the start of fetch().
                cleared.set(true)
                laterCancel.awaitProof()
                release.awaitProof()
                throw IllegalStateException("cancelled")
            }
        }
        try {
            pump.wake(); atEntry.awaitProof()
            pump.stop(); earlyCancel.awaitProof()
            val join = async(start = CoroutineStart.UNDISPATCHED) { pump.stopAndJoin() }
            pump.wake()
            assertFalse(join.isCompleted)
            resumeEntry.countDown(); laterCancel.awaitProof()
            assertFalse(join.isCompleted)
            release.countDown()
            withTimeout(5_000) { join.await() }
            assertEquals("stop/join must not restart the queue", 1, runs.get())
        } finally { resumeEntry.countDown(); release.countDown(); scope.cancel() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun stopBeforeDispatchNeverEntersNativeAndDoesNotRestartItself() = kotlinx.coroutines.test.runTest {
        val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        var calls = 0
        lateinit var pump: DownloadPump
        pump = DownloadPump(scope, { error("No transfer to cancel") }, {}, {}) {
            pump.transfer("uri") { calls++ }
        }
        pump.wake()
        pump.stop()
        runCurrent()
        pump.stopAndJoin()
        assertEquals(0, calls)
        scope.cancel()
    }
}
