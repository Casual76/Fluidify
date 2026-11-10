// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.media

import dev.lelonio.square.airpods.runtime.AirPodsMediaGesture
import dev.lelonio.square.airpods.runtime.automation.*
import dev.lelonio.square.airpods.runtime.storage.HostSettings
import java.io.Closeable
import org.junit.Assert.*
import org.junit.Test

/** Composes the real core queue/refresh/coordinator path. No receipt is wiped on read. */
class CorrectiveMediaRuntimeTest {
    private class Queue : MediaRuntimeQueue {
        val work = ArrayDeque<() -> Unit>()
        val timers = mutableListOf<() -> Unit>()
        override fun post(block: () -> Unit) { work.addLast(block) }
        override fun later(delayMillis: Long, block: () -> Unit): Closeable {
            timers.add(block)
            return Closeable { timers.remove(block) }
        }
        fun drain() { while (work.isNotEmpty()) work.removeFirst()() }
        fun fire() { if (timers.isNotEmpty()) timers.removeAt(0)() }
    }
    private class Env(external: Boolean) : MediaRuntimeEnvironment {
        var candidate = MediaCandidate(MediaTarget("app", "session", 1, "music-pair", "account"),
            Playback.PLAYING, MediaVolume(50, 100), !external, true, true, false, false,
            evidence = if (external) MediaEvidence.EXTERNAL_BEST_EFFORT else MediaEvidence.LOCAL_CORROBORATED,
            routeEvidence = if (external) RouteEvidence.EXTERNAL_STREAMING_INFERENCE else RouteEvidence.LOCAL_RENDERER)
        var observer: (() -> Unit)? = null
        var externalEnabled = false
        var autoApply = true
        val sent = mutableListOf<Triple<MediaAction, Int?, Long?>>()
        override fun configureExternal(enabled: Boolean) {
            externalEnabled = enabled
            candidate = candidate.copy(correlatedPauseEffect = null, correlatedVolumeEffect = null)
        }
        // Explicit phone port shares this fake's stream; manual writes never mint origin receipts.
        override fun readPhoneVolume() = candidate.volume?.let { PhoneStreamVolume("pair", it) }
        override fun writePhoneVolume(expected: PhoneStreamVolume, value: Int): Boolean {
            if (readPhoneVolume()?.volume?.maximum != expected.volume.maximum) return false
            sent.add(Triple(MediaAction.SET_VOLUME, value, null))
            candidate = candidate.copy(volume = MediaVolume(value, expected.volume.maximum))
            observer?.invoke()
            return true
        }
        override fun read() = listOf(candidate)
        override fun observe(changed: () -> Unit): Closeable {
            observer = changed
            return Closeable { if (observer === changed) observer = null }
        }
        fun receipt(id: Long?) {
            candidate = if (candidate.local) candidate.copy(observedVolumeEffect = id)
                else candidate.copy(correlatedVolumeEffect = id)
        }
        fun apply(action: MediaAction, value: Int?, id: Long?) {
            when (action) {
                MediaAction.SET_VOLUME -> {
                    candidate = candidate.copy(volume = MediaVolume(value!!, 100)); receipt(id)
                }
                MediaAction.PAUSE -> candidate = candidate.copy(playback = Playback.PAUSED,
                    observedPauseEffect = id.takeIf { candidate.local },
                    correlatedPauseEffect = id.takeUnless { candidate.local })
                MediaAction.PLAY -> candidate = candidate.copy(playback = Playback.PLAYING)
            }
            observer?.invoke()
        }
        override fun execute(target: MediaTarget, action: MediaAction, value: Int?, effectId: Long?): Boolean {
            sent.add(Triple(action, value, effectId))
            if (autoApply) apply(action, value, effectId)
            return true
        }
        override fun gesture(target: MediaTarget, action: AirPodsMediaGesture) = true
    }
    private class Rig(external: Boolean, val settings: HostSettings = enabled) {
        val env = Env(external)
        val queue = Queue()
        var now = 0L
        val core = MediaRuntimeCore(env, queue, AutomationClock { now })
        init { core.start(AutomationSession("pair", 1), settings) }
        fun observe() { env.observer?.invoke(); queue.drain() }
        fun step(ms: Long, dispatch: Boolean = true) {
            now += ms; queue.fire(); if (dispatch) queue.drain()
        }
        fun removal() { core.ear(EarState.BOTH); core.ear(EarState.NONE) }
        fun volumes() = env.sent.filter { it.first == MediaAction.SET_VOLUME }.map { it.second }
    }
    @Test fun visibilityBackgroundMatrixForLocalAndExternalEarAndFirstLiveConversation() {
        for (external in listOf(false, true)) for (visible in listOf(false, true))
            for (background in listOf(false, true)) for (voice in listOf(false, true)) {
                val f = Rig(external, enabled.copy(fluidifyBackgroundEnabled = background,
                    conversationPause = voice))
                f.core.visible(visible)
                if (voice) f.core.conversation(true) else f.removal()
                f.queue.drain()
                assertEquals("external=$external visible=$visible background=$background voice=$voice",
                    if (visible || background) 1 else 0, f.env.sent.size)
                assertEquals(visible || background, f.env.externalEnabled)
            }
    }
    @Test fun lossOfVisibilityOrBackgroundConsentRevokesQueuedPauseAndDuckAndCorrelations() {
        for (external in listOf(false, true)) for (hide in listOf(false, true))
            for (voice in listOf(false, true)) {
                val settings = enabled.copy(fluidifyBackgroundEnabled = !hide, conversationDuck = voice)
                val f = Rig(external, settings); f.core.visible(true)
                if (voice) { f.core.conversation(true); f.step(150, dispatch = false) }
                else f.removal()
                if (hide) f.core.visible(false) else {
                    f.core.visible(false)
                    f.core.configure(settings.copy(fluidifyBackgroundEnabled = false))
                }
                f.queue.drain()
                f.removal(); f.core.conversation(true); f.step(300)
                assertTrue(f.env.sent.isEmpty()); assertFalse(f.env.externalEnabled)
                assertNull(f.env.candidate.correlatedVolumeEffect)
                assertNull(f.env.candidate.correlatedPauseEffect)
            }
    }
    @Test fun persistentReceiptsPermitTwoDownStepsRestoreAndSecondConversation() {
        for (external in listOf(false, true)) {
            val f = Rig(external, enabled.copy(conversationDuck = true))
            f.env.receipt(-44) // truthful pre-existing receipt before a new duck
            repeat(2) {
                f.core.conversation(true); f.step(150); f.step(150)
                assertEquals(listOf(35, 20), f.volumes().takeLast(2))
                f.core.conversation(true); f.step(50) // duplicate must not recapture 20
                f.core.conversation(false); f.step(150); f.step(150)
                assertEquals(listOf(35, 50), f.volumes().takeLast(2))
                assertEquals(50, f.env.candidate.volume!!.current)
            }
            assertEquals(listOf(35, 20, 35, 50, 35, 20, 35, 50), f.volumes())
            assertTrue(f.env.sent.all { it.first == MediaAction.SET_VOLUME })
        }
    }
    @Test fun immediateFullStepWithPreexistingReceiptRestoresForLocalAndC6() {
        for (external in listOf(false, true)) {
            val f = Rig(external, enabled.copy(conversationDuck = true)); f.env.receipt(-44)
            f.core.conversation(true); f.step(300)
            f.core.conversation(false); f.step(300)
            assertEquals(listOf(20, 50), f.volumes())
        }
    }
    @Test fun incompatibleChangesRevokeQueuedNextStepAndLateReceiptCannotRestore() {
        val changes: List<(MediaCandidate) -> MediaCandidate> = listOf(
            { it.copy(volume = MediaVolume(34, 100)) },
            { it.copy(volumeRevision = 1) },
            { it.copy(volume = MediaVolume(35, 101)) },
            { it.copy(target = it.target.copy(routeId = "different-domain")) },
            { it.copy(target = it.target.copy(sessionGeneration = 2)) },
            { it.copy(permitted = false) },
            { it.copy(foreignDuckActive = true) },
            { it.copy(observedVolumeEffect = -99, correlatedVolumeEffect = -99) },
        )
        for (external in listOf(false, true)) for (change in changes) {
            val f = Rig(external, enabled.copy(conversationDuck = true))
            f.core.conversation(true); f.step(150)
            f.step(150, dispatch = false)
            val original = f.env.candidate
            f.env.candidate = change(original); f.queue.drain()
            f.env.candidate = original; f.observe()
            f.core.conversation(false); f.step(300); f.core.stop(); f.queue.drain()
            assertEquals(listOf(35), f.volumes())
        }
    }
    @Test fun timeoutWrongReceiptAndMatchingLevelAloneCannotAcquireOrRestore() {
        for (external in listOf(false, true)) for (failure in 0..2) {
            val f = Rig(external, enabled.copy(conversationDuck = true))
            f.env.autoApply = false; f.env.receipt(-44)
            f.core.conversation(true); f.step(300)
            val write = f.env.sent.single()
            when (failure) {
                0 -> f.step(2_000) // old receipt at unchanged level waits, then times out
                1 -> { f.env.receipt(-99); f.observe() }
                2 -> { f.env.candidate = f.env.candidate.copy(volume = MediaVolume(20, 100)); f.observe() }
            }
            f.env.apply(write.first, write.second, write.third); f.queue.drain()
            f.core.conversation(false); f.step(300)
            assertEquals(listOf(20), f.volumes())
        }
    }
    @Test fun cleanupAndRevocationAfterOwnedDuckNeverRollBackOrPlay() {
        for (external in listOf(false, true)) for (stop in listOf(false, true)) {
            val f = Rig(external, enabled.copy(conversationDuck = true, conversationPause = true))
            f.core.conversation(true); f.queue.drain(); f.step(300)
            val count = f.env.sent.size
            if (stop) f.core.stop() else f.core.configure(enabled.copy(automationsEnabled = false))
            f.core.conversation(false); f.step(300)
            assertEquals(count, f.env.sent.size)
            assertFalse(f.env.sent.any { it.first == MediaAction.PLAY })
        }
    }
    @Test fun preexistingReceiptAtNewOwnersInitialCounterDoesNotBreakNewDuckAndRestore() {
        for (external in listOf(false, true)) {
            val f = Rig(external, enabled.copy(conversationDuck = true))
            // The player/provider can outlive its previous AirPods coordinator.
            // Its last corroborated write may use the new owner's initial counter.
            f.env.receipt(1); f.observe()
            f.core.conversation(true); f.step(150); f.step(150)
            f.core.conversation(false); f.step(300)
            assertEquals(listOf(35, 20, 50), f.volumes())
        }
    }
    @Test fun preexistingReceiptIsNotReusedOrAcknowledgedByMatchingLevel() {
        for (external in listOf(false, true)) {
            val f = Rig(external, enabled.copy(conversationDuck = true))
            f.env.receipt(1) // retained by a player that outlived its previous coordinator
            f.env.autoApply = false
            f.core.conversation(true); f.step(300)
            assertNotEquals(1L, f.env.sent.single().third!!)
            f.env.candidate = f.env.candidate.copy(volume = MediaVolume(20, 100))
            f.observe(); f.core.conversation(false); f.step(300)
            assertEquals(listOf(20), f.volumes())
        }
    }
    @Test fun queuedRestoreIsRevokedByStopOrManualVolumeWithoutRollback() {
        for (external in listOf(false, true)) for (stop in listOf(false, true)) {
            val f = Rig(external, enabled.copy(conversationDuck = true))
            f.core.visible(true) // Foreground intent, before queuing duck/restore.
            f.core.conversation(true); f.step(300)
            f.core.conversation(false); f.step(300, dispatch = false)
            if (stop) f.core.stop() else assertTrue(f.core.manualVolume(27))
            f.queue.drain(); f.step(300)
            assertEquals(if (stop) listOf(20) else listOf(20, 27), f.volumes())
        }
    }
    @Test fun firstLivePauseDuplicatesManualPlayReconfigureAndTargetRecovery() {
        for (external in listOf(false, true)) {
            val f = Rig(external, enabled.copy(conversationPause = true))
            f.core.conversation(true); f.queue.drain()
            assertEquals(listOf(MediaAction.PAUSE), f.env.sent.map { it.first })
            f.core.conversation(true); f.queue.drain(); assertEquals(1, f.env.sent.size)
            f.env.candidate = f.env.candidate.copy(playback = Playback.PLAYING, transportRevision = 1)
            f.observe(); f.core.conversation(true); f.core.conversation(false); f.queue.drain()
            assertEquals(1, f.env.sent.size) // manual PLAY forfeits resume; duplicates never repause
            f.core.configure(f.settings.copy(conversationVolumePercent = 45)); f.queue.drain()
            assertEquals(1, f.env.sent.size) // no replay during configure
            f.core.conversation(true); f.queue.drain(); assertEquals(2, f.env.sent.size)
            f.env.candidate = f.env.candidate.copy(permitted = false); f.observe()
            f.env.candidate = f.env.candidate.copy(permitted = true, playback = Playback.PLAYING)
            f.observe(); assertEquals(2, f.env.sent.size) // recovery alone is inert
            f.core.conversation(true); f.queue.drain(); assertEquals(3, f.env.sent.size)
        }
    }
    companion object {
        private val enabled = HostSettings(automationsEnabled = true, otherAppsEnabled = true,
            fluidifyBackgroundEnabled = true, earPause = true, earResume = true)
    }
}
