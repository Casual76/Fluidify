// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.media

import dev.lelonio.square.airpods.runtime.AirPodsMediaGesture
import dev.lelonio.square.airpods.runtime.automation.*
import dev.lelonio.square.airpods.runtime.storage.HostSettings
import java.io.Closeable
import org.junit.Assert.*
import org.junit.Test

/** I5 software regressions prepared only. No firmware, route or hardware proof. */
class I5PhoneStreamVolumeTest {
    private class Queue : MediaRuntimeQueue {
        val jobs = ArrayDeque<() -> Unit>()
        val timers = mutableListOf<() -> Unit>()
        override fun post(block: () -> Unit) { jobs.addLast(block) }
        override fun later(delayMillis: Long, block: () -> Unit): Closeable {
            timers.add(block); return Closeable { timers.remove(block) }
        }
        fun drain() { while (jobs.isNotEmpty()) jobs.removeFirst()() }
        fun fire() { if (timers.isNotEmpty()) timers.removeAt(0)() }
    }
    private class Env : MediaRuntimeEnvironment {
        var phone: PhoneStreamVolume? = PhoneStreamVolume("pair", MediaVolume(8, 10))
        var candidates = emptyList<MediaCandidate>()
        var callback: (() -> Unit)? = null
        val callbacks = mutableListOf<() -> Unit>()
        val writes = mutableListOf<Int>()
        val effects = mutableListOf<MediaEffect>()
        var gestures = 0
        var fail = false
        var beforeWrite: () -> Unit = {}
        var afterPhoneRead: () -> Unit = {}
        var externalEnabled = false
        override fun configureExternal(enabled: Boolean) { externalEnabled = enabled }
        override fun readPhoneVolume(): PhoneStreamVolume? {
            val observed = phone
            afterPhoneRead()
            return observed
        }
        override fun writePhoneVolume(expected: PhoneStreamVolume, value: Int): Boolean {
            beforeWrite()
            val fresh = phone ?: return false
            if (fresh.domain != expected.domain || fresh.volume.maximum != expected.volume.maximum ||
                value !in 0..fresh.volume.maximum || fail) return false
            writes.add(value)
            phone = fresh.copy(volume = MediaVolume(value, fresh.volume.maximum))
            callback?.invoke()
            return true
        }
        override fun read() = candidates
        override fun observe(changed: () -> Unit): Closeable {
            callbacks.add(changed); callback = changed
            return Closeable { if (callback === changed) callback = null }
        }
        override fun execute(target: MediaTarget, action: MediaAction, value: Int?, effectId: Long?): Boolean {
            effects.add(MediaEffect(effectId!!, 0, AutomationSession("pair", 1), target, action, value))
            candidates = candidates.map { c -> if (c.target != target) c else when (action) {
                MediaAction.SET_VOLUME -> {
                    phone = PhoneStreamVolume("pair", MediaVolume(value!!, 10))
                    c.copy(volume = phone!!.volume, observedVolumeEffect = effectId)
                }
                MediaAction.PAUSE -> c.copy(playback = Playback.PAUSED, observedPauseEffect = effectId)
                MediaAction.PLAY -> c.copy(playback = Playback.PLAYING)
            } }
            callback?.invoke(); return true
        }
        override fun gesture(target: MediaTarget, action: AirPodsMediaGesture): Boolean { gestures++; return true }
    }
    private class Rig(val settings: HostSettings = HostSettings()) {
        val env = Env(); val queue = Queue(); var time = 0L
        val observations = mutableListOf<MediaVolume?>()
        val core = MediaRuntimeCore(env, queue, AutomationClock { time }) { observations.add(it) }
        init { core.start(AutomationSession("pair", 1), settings); core.visible(true) }
        fun local() { env.candidates = listOf(candidate()) }
        fun step(dispatch: Boolean = true) { time += 300; queue.fire(); if (dispatch) queue.drain() }
    }
    @Test fun zeroCandidatesAndAllAutomationOffStillReadAndWritePhoneStream() {
        val f = Rig(); assertEquals(MediaVolume(8, 10), f.observations.last())
        assertTrue(f.core.manualVolume(4)); f.queue.drain()
        assertEquals(listOf(4), f.env.writes); assertEquals(MediaVolume(4, 10), f.observations.last())
        assertTrue(f.env.effects.isEmpty()); assertEquals(0, f.env.gestures); assertFalse(f.env.externalEnabled)
    }
    @Test fun externalOnlyC6OffAllowsManualButDoesNotAuthorizeAutomaticOrTransport() {
        val f = Rig(automatic.copy(otherAppsEnabled = false))
        f.env.candidates = listOf(candidate().copy(local = false, evidence = MediaEvidence.EXTERNAL_BEST_EFFORT))
        assertTrue(f.core.manualVolume(5))
        f.core.ear(EarState.BOTH); f.core.ear(EarState.NONE); f.core.conversation(true); f.step()
        assertFalse(f.core.gesture(AirPodsMediaGesture.PLAY))
        assertTrue(f.env.effects.isEmpty()); assertEquals(0, f.env.gestures); assertFalse(f.env.externalEnabled)
    }
    @Test fun hiddenAndStaleForegroundActionsAreRejectedEvenWithBackgroundConsent() {
        val f = Rig(automatic); f.core.visible(false)
        assertFalse(f.core.manualVolume(5)); f.core.visible(true)
        var current = true
        f.queue.post { assertFalse(f.core.manualVolume(5) { current }) }
        current = false; f.queue.drain(); assertTrue(f.env.writes.isEmpty())
    }
    @Test fun queuedActionRechecksSessionAndAdmissionChanges() {
        for (change in 0..3) {
            val f = Rig(); var admitted = true
            f.queue.post { assertFalse(f.core.manualVolume(5) { admitted }) }
            when (change) {
                0 -> f.core.stop()
                1 -> { f.core.start(AutomationSession("pair", 2), HostSettings()); admitted = false }
                2 -> { f.env.phone = PhoneStreamVolume("replacement-pair", MediaVolume(8, 10)); admitted = false }
                3 -> admitted = false // revoked owner permission/bond/session before handler dispatch
            }
            f.queue.drain(); assertTrue(f.env.writes.isEmpty())
        }
    }
    @Test fun queuedModePermissionAndInvalidMaximumOrRangeCannotWrite() {
        for (invalid in 0..3) {
            val f = Rig()
            f.queue.post { assertFalse(f.core.manualVolume(5)) }
            // Adapter maps permission loss, call/non-normal mode, zero maximum to unavailable.
            f.env.phone = if (invalid < 3) null else PhoneStreamVolume("pair", MediaVolume(2, 3))
            f.queue.drain(); assertTrue(f.env.writes.isEmpty())
        }
    }
    @Test fun pairRangeAndPermissionRereadAfterRevocationAndInsidePlatformDispatch() {
        for (atWrite in listOf(false, true)) for (change in 0..2) {
            val f = Rig()
            val mutate = { f.env.phone = when (change) {
                0 -> null
                1 -> PhoneStreamVolume("new-pair", MediaVolume(8, 10))
                else -> PhoneStreamVolume("pair", MediaVolume(2, 3))
            } }
            // Race the two phone reads without depending on a full coordinator reconfigure.
            if (atWrite) f.env.beforeWrite = mutate else f.env.afterPhoneRead = mutate
            assertFalse(f.core.manualVolume(5)); assertTrue(f.env.writes.isEmpty())
        }
    }
    @Test fun unchangedManualLevelCancelsQueuedDuckAndCannotBecomeOriginReceipt() {
        val f = Rig(automatic); f.local(); f.core.conversation(true); f.step(false)
        assertTrue(f.core.manualVolume(8)); f.queue.drain(); f.core.conversation(false); f.step()
        assertEquals(listOf(8), f.env.writes); assertTrue(f.env.effects.isEmpty())
        assertNull(f.env.candidates.single().observedVolumeEffect)
    }
    @Test fun manualRevokesAlreadyQueuedRestoreAndNeverPlaysOrRollsBack() {
        val f = Rig(automatic); f.local(); f.core.conversation(true); f.step()
        assertEquals(1, f.env.effects.size)
        f.core.conversation(false); f.step(false)
        assertTrue(f.core.manualVolume(6)); f.queue.drain(); f.step()
        assertEquals(listOf(6), f.env.writes)
        assertEquals(listOf(MediaAction.SET_VOLUME), f.env.effects.map { it.action })
    }
    @Test fun failedSetReturnsFalsePublishesActualAndStillRevokesQueuedDuck() {
        val f = Rig(automatic); f.local(); f.core.conversation(true); f.step(false)
        f.env.fail = true; assertFalse(f.core.manualVolume(5)); f.queue.drain()
        f.core.conversation(false); f.step()
        assertTrue(f.env.effects.isEmpty()); assertTrue(f.env.writes.isEmpty())
        assertEquals(MediaVolume(8, 10), f.observations.last())
    }
    @Test fun observerWithZeroMediaSessionsPublishesActualAndStaleCallbacksCannotResurrectStop() {
        val f = Rig(); val stale = f.env.callbacks.last()
        f.env.phone = PhoneStreamVolume("pair", MediaVolume(3, 10)); stale(); f.queue.drain()
        assertEquals(MediaVolume(3, 10), f.observations.last())
        f.core.stop(); stale(); f.queue.drain(); assertNull(f.observations.last())
        assertNull(f.env.callback); assertTrue(f.queue.timers.isEmpty())
    }
    @Test fun reconfigureReadsPhoneAndDropsOldObserverWithoutCallbackLoop() {
        val f = Rig(); val stale = f.env.callbacks.last()
        // A real settings change must replace the observer; equal settings now preserve it.
        f.env.phone = PhoneStreamVolume("pair", MediaVolume(2, 10)); f.core.configure(f.settings.copy(earPause = true))
        val count = f.observations.size; stale(); f.queue.drain()
        assertEquals(count, f.observations.size); assertEquals(MediaVolume(2, 10), f.observations.last())
        assertTrue(f.core.manualVolume(7)); f.queue.drain(); assertTrue(f.queue.jobs.isEmpty())
        assertEquals(listOf(7), f.env.writes)
    }
    @Test fun manualVolumeRevokesOwnedPauseWithoutPlayOnReinsertion() {
        val f = Rig(automatic); f.local()
        f.core.ear(EarState.BOTH); f.core.ear(EarState.NONE); f.queue.drain()
        assertEquals(listOf(MediaAction.PAUSE), f.env.effects.map { it.action })
        assertTrue(f.core.manualVolume(8)); f.queue.drain()
        f.core.ear(EarState.BOTH); f.queue.drain()
        assertEquals(listOf(MediaAction.PAUSE), f.env.effects.map { it.action })
    }
    @Test fun manualWithAllAutomationOffCannotEnableNewLocalEffects() {
        val f = Rig(); f.local(); assertTrue(f.core.manualVolume(5))
        f.core.ear(EarState.BOTH); f.core.ear(EarState.NONE); f.core.conversation(true); f.step()
        assertTrue(f.env.effects.isEmpty())
    }
    companion object {
        private val automatic = HostSettings(automationsEnabled = true, otherAppsEnabled = true,
            fluidifyBackgroundEnabled = true, earPause = true, earResume = true, conversationDuck = true)
        private fun candidate() = MediaCandidate(MediaTarget("local", "token", 1, "route", "account"),
            Playback.PLAYING, MediaVolume(8, 10), true, true, true, false, false)
    }
}
