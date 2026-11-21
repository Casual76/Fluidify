// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.media

import dev.lelonio.square.airpods.runtime.AirPodsMediaGesture
import dev.lelonio.square.airpods.runtime.automation.*
import dev.lelonio.square.airpods.runtime.storage.HostSettings
import java.io.Closeable
import org.junit.Assert.*
import org.junit.Test

class MediaRuntimeCoreTest {
    private class Queue : MediaRuntimeQueue {
        val work = ArrayDeque<() -> Unit>()
        val timers = mutableListOf<() -> Unit>()
        override fun post(block: () -> Unit) { work.addLast(block) }
        override fun later(delayMillis: Long, block: () -> Unit): Closeable {
            timers.add(block); return Closeable { timers.remove(block) }
        }
        fun drain() { while (work.isNotEmpty()) work.removeFirst()() }
    }
    private class Env : MediaRuntimeEnvironment {
        var candidate = MediaCandidate(MediaTarget("local", "token", 1, "pair-route", "account-1"),
            Playback.PLAYING, MediaVolume(8, 10), true, true, true, false, false)
        var extra: MediaCandidate? = null
        val callbacks = mutableListOf<() -> Unit>()
        val effects = mutableListOf<Triple<MediaAction, Int?, Long?>>()
        var observer: (() -> Unit)? = null
        // Explicit phone port shares this fake's stream; manual writes never mint origin receipts.
        override fun readPhoneVolume() = candidate.volume?.let { PhoneStreamVolume("pair", it) }
        override fun writePhoneVolume(expected: PhoneStreamVolume, value: Int): Boolean {
            if (readPhoneVolume()?.volume?.maximum != expected.volume.maximum) return false
            effects.add(Triple(MediaAction.SET_VOLUME, value, null))
            candidate = candidate.copy(volume = MediaVolume(value, expected.volume.maximum))
            observer?.invoke()
            return true
        }
        override fun read() = listOfNotNull(candidate, extra)
        override fun observe(changed: () -> Unit): Closeable {
            callbacks.add(changed); observer = changed
            return Closeable { if (observer === changed) observer = null }
        }
        override fun execute(target: MediaTarget, action: MediaAction, value: Int?, effectId: Long?): Boolean {
            effects.add(Triple(action, value, effectId)); return true
        }
        override fun gesture(target: MediaTarget, action: AirPodsMediaGesture) = true
    }
    private class Fixture(settings: HostSettings = enabled) {
        val env = Env(); val queue = Queue()
        var now = 0L
        val core = MediaRuntimeCore(env, queue, AutomationClock { now })
        init { core.start(AutomationSession("pair", 7), settings) }
        fun removal() { core.ear(EarState.BOTH); core.ear(EarState.NONE) }
        fun observe() { env.observer?.invoke(); queue.drain() }
        fun ownedPause() {
            removal(); queue.drain()
            env.candidate = env.candidate.copy(playback = Playback.PAUSED,
                observedPauseEffect = env.effects.single().third)
            observe()
        }
    }
    @Test fun noEffectBeforeOptIn() {
        val f = Fixture(HostSettings()); f.removal(); f.queue.drain()
        assertTrue(f.env.effects.isEmpty())
    }
    @Test fun firstRemovalAfterEnablingUsesTheAlreadyObservedLiveBaseline() {
        for (off in listOf(enabled.copy(automationsEnabled = false), enabled.copy(earPause = false))) {
            val f = Fixture(off)
            f.core.ear(EarState.BOTH); f.queue.drain()
            f.core.configure(enabled); f.queue.drain()
            assertTrue("Enabling must not replay a sensor action", f.env.effects.isEmpty())
            f.core.ear(EarState.LEFT); f.queue.drain()
            assertEquals(listOf(MediaAction.PAUSE), f.env.effects.map { it.first })
        }
    }
    @Test fun reconfigureKeepsOnlySensorHistoryAndCancelsEveryOldEffect() {
        val f = Fixture()
        f.core.ear(EarState.BOTH); f.core.ear(EarState.LEFT)
        f.core.configure(enabled.copy(conversationDuck = true)); f.queue.drain()
        assertTrue(f.env.effects.isEmpty()) // the queued old PAUSE lost its lease
        f.core.ear(EarState.LEFT); f.queue.drain()
        assertTrue(f.env.effects.isEmpty()) // no replay of the last observation
        f.core.ear(EarState.BOTH); f.core.ear(EarState.LEFT); f.queue.drain()
        assertEquals(listOf(MediaAction.PAUSE), f.env.effects.map { it.first })
    }
    @Test fun aNewSessionMustObserveItsOwnEarBaseline() {
        val f = Fixture()
        f.core.ear(EarState.BOTH)
        f.core.start(AutomationSession("pair", 8), enabled)
        f.core.ear(EarState.LEFT); f.queue.drain()
        assertTrue(f.env.effects.isEmpty())
        f.core.ear(EarState.BOTH); f.core.ear(EarState.LEFT); f.queue.drain()
        assertEquals(listOf(MediaAction.PAUSE), f.env.effects.map { it.first })
    }
    @Test fun liveUnknownWhileDisabledIsIgnoredSoTheObservedBaselineSurvivesEnable() {
        val f = Fixture(enabled.copy(automationsEnabled = false))
        f.core.ear(EarState.BOTH); f.core.ear(EarState.UNKNOWN)
        f.core.configure(enabled); f.queue.drain()
        assertTrue("Enabling must not replay a sensor action", f.env.effects.isEmpty())
        f.core.ear(EarState.LEFT); f.queue.drain()
        assertEquals(listOf(MediaAction.PAUSE), f.env.effects.map { it.first })
    }
    @Test fun queuedPauseRechecksPermission() {
        val f = Fixture(); f.removal()
        f.env.candidate = f.env.candidate.copy(permitted = false); f.queue.drain()
        assertTrue(f.env.effects.isEmpty())
    }
    @Test fun queuedPauseRechecksCall() {
        val f = Fixture(); f.removal()
        f.env.candidate = f.env.candidate.copy(callActive = true)
        f.queue.drain(); assertTrue(f.env.effects.isEmpty())
    }
    @Test fun routeToPairIsNotAConditionOfTheQueuedPause() {
        val f = Fixture(); f.removal()
        f.env.candidate = f.env.candidate.copy(routedToPair = false)
        f.queue.drain()
        assertEquals(listOf(MediaAction.PAUSE), f.env.effects.map { it.first })
    }
    @Test fun logoutAndNewAccountWithSameSessionTokenRejectOldEffect() {
        val f = Fixture(); f.removal()
        f.env.candidate = f.env.candidate.copy(target = f.env.candidate.target.copy(accountEpoch = "account-2"))
        f.queue.drain(); assertTrue(f.env.effects.isEmpty())
        // The old removal is never replayed. A NONE sample after the switch is a repeated NONE
        // on the (new) playing target, so it pauses exactly once: that is a new decision.
        f.core.ear(EarState.NONE); f.queue.drain()
        assertEquals(listOf(MediaAction.PAUSE), f.env.effects.map { it.first })
    }
    @Test fun ambiguityOrReplacementCannotInheritTarget() {
        for (ambiguity in listOf(true, false)) {
            val f = Fixture(); f.removal()
            val other = f.env.candidate.copy(target = f.env.candidate.target.copy(sessionGeneration = 2))
            if (ambiguity) f.env.extra = other else f.env.candidate = other
            f.queue.drain(); assertTrue(f.env.effects.isEmpty())
        }
    }
    @Test fun staleCallbackAndEffectAfterStopOrReconfigureDoNothing() {
        for (stop in listOf(true, false)) {
            val f = Fixture(); f.removal(); val callback = f.env.callbacks.last()
            if (stop) f.core.stop() else f.core.configure(enabled.copy(earResume = false))
            callback(); f.queue.drain(); assertTrue(f.env.effects.isEmpty())
        }
    }
    @Test fun dispatchedPauseOwnsOnPausedWithoutReceiptAndResumes() {
        val f = Fixture(); f.removal(); f.queue.drain() // PAUSE accepted by the target (dispatched)
        f.env.candidate = f.env.candidate.copy(playback = Playback.PAUSED) // no receipt id
        f.observe(); f.core.ear(EarState.BOTH); f.queue.drain()
        assertEquals(listOf(MediaAction.PAUSE, MediaAction.PLAY), f.env.effects.map { it.first })
    }
    @Test fun pausedWithAForeignReceiptDoesNotOwnEvenAfterDispatch() {
        val f = Fixture(); f.removal(); f.queue.drain()
        f.env.candidate = f.env.candidate.copy(playback = Playback.PAUSED,
            observedPauseEffect = f.env.effects.single().third!! + 1000)
        f.observe(); f.core.ear(EarState.BOTH); f.queue.drain()
        assertEquals(listOf(MediaAction.PAUSE), f.env.effects.map { it.first })
    }
    @Test fun asynchronousLocalReceiptAfterApplicationPausedCanStillResume() {
        val f = Fixture(); f.removal(); f.queue.drain()
        val pause = f.env.effects.single()
        f.env.candidate = f.env.candidate.copy(playback = Playback.PAUSED)
        f.observe()
        assertEquals(listOf(MediaAction.PAUSE), f.env.effects.map { it.first })
        f.env.candidate = f.env.candidate.copy(observedPauseEffect = pause.third)
        f.observe(); f.core.ear(EarState.BOTH); f.queue.drain()
        assertEquals(listOf(MediaAction.PAUSE, MediaAction.PLAY), f.env.effects.map { it.first })
    }
    @Test fun corroboratedPauseCanResumeButManualPauseWhilePausedRevokes() {
        for (manual in listOf(true, false)) {
            val f = Fixture(); f.ownedPause()
            if (manual) {
                f.env.candidate = f.env.candidate.copy(transportRevision = 1)
                f.observe()
            }
            f.core.ear(EarState.BOTH); f.queue.drain()
            assertEquals(if (manual) listOf(MediaAction.PAUSE) else
                listOf(MediaAction.PAUSE, MediaAction.PLAY), f.env.effects.map { it.first })
        }
    }
    @Test fun backgroundAndOtherAppsHaveSeparateOptIns() {
        val f = Fixture(enabled.copy(fluidifyBackgroundEnabled = false))
        f.removal(); f.queue.drain(); assertTrue(f.env.effects.isEmpty())
        f.core.visible(true); f.removal(); f.queue.drain(); assertEquals(1, f.env.effects.size)
        val external = Fixture(); external.env.candidate = external.env.candidate.copy(local = false)
        external.removal(); external.queue.drain(); assertTrue(external.env.effects.isEmpty())
    }
    @Test fun manualVolumeCancelsQueuedDuckAndNeverRestoresOldBaseline() {
        val f = Fixture(enabled.copy(conversationDuck = true))
        f.core.visible(true) // Foreground intent, before queuing duck.
        f.core.conversation(false); f.core.conversation(true)
        f.now = 300; val timer = f.queue.timers.first(); timer()
        assertTrue(f.core.manualVolume(7)); f.queue.drain()
        f.core.conversation(false); f.queue.drain()
        // The manual level rebases the duck (it no longer destroys it) but a step queued BEFORE the
        // manual intent must not run after it and overwrite the level the user just chose.
        assertEquals(listOf(Triple(MediaAction.SET_VOLUME, 7, null)), f.env.effects)
    }
    @Test fun manualVolumeNeedsNoVisibilityAndRebasesInsteadOfDestroyingTheDuck() {
        // Background-enabled settings, never made visible: the tile/quick-settings case.
        val f = Fixture(enabled.copy(conversationDuck = true))
        f.core.conversation(false); f.core.conversation(true)
        assertTrue(f.core.manualVolume(7))
        assertEquals(listOf(Triple(MediaAction.SET_VOLUME, 7, null)), f.env.effects)
        // A refused guard still wins over everything.
        assertFalse(f.core.manualVolume(6) { false })
        assertEquals(1, f.env.effects.size)
    }
    @Test fun foreignFocusDuckPreventsSecondAttenuation() {
        val f = Fixture(enabled.copy(conversationDuck = true))
        f.env.candidate = f.env.candidate.copy(foreignDuckActive = true)
        f.core.conversation(false); f.core.conversation(true)
        assertTrue(f.queue.timers.isEmpty()); assertTrue(f.env.effects.isEmpty())
    }
    @Test fun cleanupDoesNotPlayOrRollback() {
        val f = Fixture(); f.ownedPause(); f.core.stop(); f.queue.drain()
        assertEquals(listOf(MediaAction.PAUSE), f.env.effects.map { it.first })
        assertTrue(f.queue.timers.isEmpty())
    }
    @Test fun matchingVolumeReadIsNotReceiptAndManualChangeRebasesInsteadOfRestoring() {
        for (manual in listOf(false, true)) {
            val f = Fixture(enabled.copy(conversationDuck = true))
            f.core.conversation(false); f.core.conversation(true)
            f.now = 300; f.queue.timers.first()(); f.queue.drain()
            val write = f.env.effects.single()
            assertEquals(MediaAction.SET_VOLUME, write.first)
            // A matching read without origin receipt must never authorize restoration.
            f.env.candidate = f.env.candidate.copy(volume = MediaVolume(write.second!!, 10),
                volumeRevision = if (manual) 1 else 0)
            f.observe(); f.core.conversation(false); f.queue.drain()
            assertEquals(1, f.env.effects.size)
        }
    }
    @Test fun externalDedicatedConsentDispatchesAndRevocationCancelsQueuedEffects() {
        for (revoke in listOf(false, true)) {
            val settings = enabled.copy(otherAppsEnabled = true)
            val f = Fixture(settings)
            f.env.candidate = f.env.candidate.copy(local = false,
                evidence = MediaEvidence.EXTERNAL_BEST_EFFORT,
                routeEvidence = RouteEvidence.EXTERNAL_STREAMING_INFERENCE)
            f.removal()
            if (revoke) f.core.configure(settings.copy(otherAppsEnabled = false))
            f.queue.drain()
            assertEquals(if (revoke) 0 else 1, f.env.effects.size)
        }
    }
    @Test fun externalRequiresBothGeneralAndDedicatedConsentEvenForGesture() {
        for (settings in listOf(enabled.copy(otherAppsEnabled = false),
            enabled.copy(otherAppsEnabled = true, automationsEnabled = false))) {
            val f = Fixture(settings)
            f.env.candidate = f.env.candidate.copy(local = false, evidence = MediaEvidence.EXTERNAL_BEST_EFFORT)
            f.removal(); f.queue.drain()
            assertTrue(f.env.effects.isEmpty()); assertFalse(f.core.gesture(AirPodsMediaGesture.PLAY))
        }
    }
    private fun MediaRuntimeCore.lease(): Long {
        val field = MediaRuntimeCore::class.java.getDeclaredField("coordinator").apply { isAccessible = true }
        return (field.get(this) as AirPodsAutomationCoordinator).status().lease
    }
    private fun Fixture.effect(lease: Long, session: AutomationSession = AutomationSession("pair", 7), id: Long = 900) =
        MediaEffect(id, lease, session, env.candidate.target, MediaAction.PAUSE)
    @Test fun submitWithStaleLeaseOrForeignSessionIsRefusedSynchronouslyAndQueuesNothing() {
        val f = Fixture(); val lease = f.core.lease()
        f.core.configure(enabled.copy(earResume = false)) // new lease: the old one is now stale
        assertNotEquals(lease, f.core.lease())
        assertFalse(f.core.submit(f.effect(lease)))
        assertFalse(f.core.submit(f.effect(f.core.lease(), session = AutomationSession("pair", 99))))
        assertTrue("no effect may be queued for a refused submit", f.queue.work.isEmpty())
        f.queue.drain(); assertTrue(f.env.effects.isEmpty())
    }
    @Test fun submitOfACancelledEffectOrLeaseIsRefusedSynchronously() {
        val f = Fixture(); val lease = f.core.lease(); val e = f.effect(lease)
        f.core.cancelEffect(e)
        assertFalse(f.core.submit(e))
        assertTrue(f.queue.work.isEmpty())
        val g = Fixture(); val gLease = g.core.lease()
        g.core.cancel(gLease)
        assertFalse(g.core.submit(g.effect(gLease)))
        assertTrue(g.queue.work.isEmpty())
    }
    @Test fun submitWithoutAuthorityOrAfterStopIsRefusedSynchronously() {
        var granted = true
        val env = Env(); val queue = Queue()
        val core = MediaRuntimeCore(env, queue, AutomationClock { 0 }, authority = { granted })
        core.start(AutomationSession("pair", 7), enabled)
        val e = MediaEffect(900, core.lease(), AutomationSession("pair", 7), env.candidate.target, MediaAction.PAUSE)
        granted = false
        assertFalse(core.submit(e)); assertTrue(queue.work.isEmpty())
        granted = true
        core.stop()
        assertFalse(core.submit(e)); assertTrue(queue.work.isEmpty())
    }
    @Test fun submitOfACurrentEffectIsAcceptedAndExecutesOnTheQueue() {
        val f = Fixture(); val e = f.effect(f.core.lease())
        assertTrue(f.core.submit(e))
        assertEquals(1, f.queue.work.size); assertTrue(f.env.effects.isEmpty())
        f.queue.drain()
        assertEquals(listOf(Triple(MediaAction.PAUSE, null, 900L)), f.env.effects)
    }
    companion object {
        private val enabled = HostSettings(automationsEnabled = true, fluidifyBackgroundEnabled = true,
            earPause = true, earResume = true)
    }
}
