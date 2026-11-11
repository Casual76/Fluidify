// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.android.platform

import dev.lelonio.square.airpods.runtime.storage.PairAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

/** Lock order of the cold audio path: owner lock -> operations monitor, never the reverse.
 * Deterministic interleavings use latches and thread states; every wait is finite. */
class OrdinaryAudioLockOrderTest {
    private class Profile : AudioProfilePort {
        @Volatile var warm = false
        @Volatile var callback: (() -> Unit)? = null
        @Volatile var syncReady = false
        val calls = java.util.Collections.synchronizedList(mutableListOf<AudioOperation>())
        override fun ready() = warm
        override fun prepare(onReady: () -> Unit): Boolean {
            callback = onReady
            if (syncReady) { warm = true; onReady() }
            return true
        }
        override fun invoke(pair: PairAddress, operation: AudioOperation): PlatformResult { calls += operation; return PlatformResult.RequestAccepted }
        override fun close() {}
    }
    private val action = PlatformAction(PairAddress("00:11:22:33:44:55"), 3, 4, 100)
    private val facts = AudioAuthorization(true, true, true, true)

    @Test fun completionsAreNeverDeliveredUnderTheOperationsMonitor() {
        val paths: List<Pair<String, (Profile, OrdinaryAudioOperations, (PlatformResult) -> Unit) -> Unit>> = listOf(
            "cold-callback" to { p, a, done -> a.request(action, AudioOperation.CONNECT, AudioOrigin.MANUAL, { true }, done); p.warm = true; p.callback!!.invoke() },
            "synchronous-prepare" to { p, a, done -> p.syncReady = true; a.request(action, AudioOperation.CONNECT, AudioOrigin.MANUAL, { true }, done) },
            "cancel-pending" to { _, a, done -> a.request(action, AudioOperation.CONNECT, AudioOrigin.MANUAL, { true }, done); a.cancelPending() },
            "close" to { _, a, done -> a.request(action, AudioOperation.CONNECT, AudioOrigin.MANUAL, { true }, done); a.close() },
        )
        paths.forEach { (name, run) ->
            val profile = Profile()
            val audio = OrdinaryAudioOperations(profile, { 100L }, { facts })
            val underMonitor = mutableListOf<Boolean>()
            run(profile, audio) { underMonitor += Thread.holdsLock(audio) }
            assertEquals(name, listOf(false), underMonitor)
        }
    }

    /** Reproduces the SDK35 dump: callback inside the operations monitor, stop holding the owner
     * lock and closing. Before the fix the callback completion took the owner lock under the monitor. */
    @Test fun coldCallbackCompletionAndCloseUnderOwnerLockBothTerminate() {
        val ownerLock = Any()
        val profile = Profile()
        val audio = OrdinaryAudioOperations(profile, { 100L }, { facts })
        val armed = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val outcomes = java.util.Collections.synchronizedList(mutableListOf<PlatformResult>())
        val stillAuthorized = {
            if (armed.compareAndSet(true, false)) { entered.countDown(); release.await(10, TimeUnit.SECONDS) }
            true
        }
        assertEquals(PlatformResult.Queued, audio.request(action, AudioOperation.CONNECT, AudioOrigin.MANUAL, stillAuthorized) {
            synchronized(ownerLock) { outcomes += it } // the owner records results under its own lock
        })
        armed.set(true)
        val callback = Thread({ profile.warm = true; profile.callback!!.invoke() }, "cold-audio-callback").apply { isDaemon = true }
        val holdsOwner = CountDownLatch(1)
        val stop = Thread({ synchronized(ownerLock) { holdsOwner.countDown(); audio.close() } }, "owner-stop").apply { isDaemon = true }
        callback.start()
        assertTrue("callback never entered the operations monitor", entered.await(5, TimeUnit.SECONDS))
        stop.start()
        assertTrue("stop never took the owner lock", holdsOwner.await(5, TimeUnit.SECONDS))
        awaitState(stop, Thread.State.BLOCKED) // stop is now waiting for the operations monitor
        release.countDown()
        callback.join(5_000); stop.join(5_000)
        assertTerminated(callback, stop)
        // Stop began (and was waiting) before the callback's final check: no command, one STALE outcome.
        assertTrue(profile.calls.isEmpty())
        assertEquals(listOf<PlatformResult>(PlatformResult.Denied(PlatformFailure.STALE)), outcomes.toList())
    }

    @Test fun revocationDuringLiveChecksPreventsThePlatformCall() {
        val profile = Profile()
        lateinit var audio: OrdinaryAudioOperations
        var revokeOnCheck = false
        audio = OrdinaryAudioOperations(profile, { 100L }, {
            if (revokeOnCheck) audio.revoke() // the owner revokes while the callback holds the monitor
            facts
        })
        val outcomes = mutableListOf<PlatformResult>()
        assertEquals(PlatformResult.Queued, audio.request(action, AudioOperation.CONNECT, AudioOrigin.MANUAL, { true }, outcomes::add))
        revokeOnCheck = true
        profile.warm = true; profile.callback!!.invoke()
        assertTrue(profile.calls.isEmpty())
        assertEquals(listOf<PlatformResult>(PlatformResult.Denied(PlatformFailure.STALE)), outcomes)
        assertEquals(PlatformResult.Denied(PlatformFailure.CLOSED), audio.request(action, AudioOperation.CONNECT, AudioOrigin.MANUAL, { true }))
        audio.close(); profile.callback!!.invoke()
        assertEquals(1, outcomes.size); assertTrue(profile.calls.isEmpty())
    }

    @Test fun revokedPendingCompletesStaleOnceWithoutRunningChecks() {
        val profile = Profile()
        var checks = 0
        val audio = OrdinaryAudioOperations(profile, { 100L }, { checks++; facts })
        val outcomes = mutableListOf<PlatformResult>()
        assertEquals(PlatformResult.Queued, audio.request(action, AudioOperation.CONNECT, AudioOrigin.MANUAL, { true }, outcomes::add))
        val before = checks
        audio.revoke()
        profile.warm = true; profile.callback!!.invoke(); profile.callback!!.invoke()
        audio.close()
        assertEquals(before, checks); assertTrue(profile.calls.isEmpty())
        assertEquals(listOf<PlatformResult>(PlatformResult.Denied(PlatformFailure.STALE)), outcomes)
    }

    @Test fun throwingCompletionStillClosesProfileAndLeavesNothingQueued() {
        var closes = 0
        val profile = object : AudioProfilePort {
            var callback: (() -> Unit)? = null
            override fun ready() = false
            override fun prepare(onReady: () -> Unit): Boolean { callback = onReady; return true }
            override fun invoke(pair: PairAddress, operation: AudioOperation) = PlatformResult.RequestAccepted
            override fun close() { closes++ }
        }
        val audio = OrdinaryAudioOperations(profile, { 100L }, { facts })
        audio.request(action, AudioOperation.CONNECT, AudioOrigin.MANUAL, { true }) { error("owner failure") }
        assertEquals("owner failure", assertThrows(IllegalStateException::class.java) { audio.close() }.message)
        assertEquals(1, closes)
        val later = mutableListOf<PlatformResult>()
        assertEquals(PlatformResult.Denied(PlatformFailure.CLOSED), audio.request(action, AudioOperation.CONNECT, AudioOrigin.MANUAL, { true }, later::add))
        audio.cancelPending(); profile.callback!!.invoke()
        assertTrue(later.isEmpty())
    }

    private fun awaitState(thread: Thread, state: Thread.State) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (thread.state != state) {
            assertTrue("${thread.name} never reached $state (now ${thread.state})", System.nanoTime() < end)
            Thread.sleep(1)
        }
    }

    companion object {
        fun assertTerminated(vararg threads: Thread) {
            val alive = threads.filter { it.isAlive }
            if (alive.isEmpty()) return
            // android.jar test classpath has no java.lang.management: reach the host JVM reflectively.
            val deadlocked = runCatching {
                val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
                (Class.forName("java.lang.management.ThreadMXBean").getMethod("findMonitorDeadlockedThreads").invoke(bean) as LongArray?)?.toList().orEmpty()
            }.getOrDefault(emptyList())
            val names = Thread.getAllStackTraces().keys.associate { it.id to it.name }
            fail("not terminated within the finite timeout: " + alive.joinToString(" | ") {
                "${it.name}=${it.state} at ${it.stackTrace.take(8).joinToString(" < ")}"
            } + "; monitor deadlock=" + deadlocked.map { names[it] ?: "tid$it" })
        }
    }
}
