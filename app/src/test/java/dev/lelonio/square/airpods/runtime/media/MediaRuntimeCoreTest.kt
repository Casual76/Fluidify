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
    @Test fun queuedPauseRechecksPermission() {
        val f = Fixture(); f.removal()
        f.env.candidate = f.env.candidate.copy(permitted = false); f.queue.drain()
        assertTrue(f.env.effects.isEmpty())
    }
    @Test fun queuedPauseRechecksRouteAndCall() {
        for (remote in listOf(true, false)) {
            val f = Fixture(); f.removal()
            f.env.candidate = if (remote) f.env.candidate.copy(routedToPair = false)
                else f.env.candidate.copy(callActive = true)
            f.queue.drain(); assertTrue(f.env.effects.isEmpty())
        }
    }
    @Test fun logoutAndNewAccountWithSameSessionTokenRejectOldEffect() {
        val f = Fixture(); f.removal()
        f.env.candidate = f.env.candidate.copy(target = f.env.candidate.target.copy(accountEpoch = "account-2"))
        f.queue.drain(); assertTrue(f.env.effects.isEmpty())
        // New account starts with ear baseline, without replay of the old removal.
        f.core.ear(EarState.NONE); f.queue.drain(); assertTrue(f.env.effects.isEmpty())
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
    @Test fun genericPausedCallbackDoesNotOwnPause() {
        val f = Fixture(); f.removal(); f.queue.drain()
        f.env.candidate = f.env.candidate.copy(playback = Playback.PAUSED)
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
        f.core.conversation(false); f.core.conversation(true)
        f.now = 300; val timer = f.queue.timers.first(); timer()
        assertTrue(f.core.manualVolume(7)); f.queue.drain()
        f.core.conversation(false); f.queue.drain()
        assertEquals(listOf(Triple(MediaAction.SET_VOLUME, 7, null)), f.env.effects)
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
    @Test fun matchingVolumeReadIsNotReceiptAndManualChangeAbandonsRestore() {
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
    companion object {
        private val enabled = HostSettings(automationsEnabled = true, fluidifyBackgroundEnabled = true,
            earPause = true, earResume = true)
    }
}
