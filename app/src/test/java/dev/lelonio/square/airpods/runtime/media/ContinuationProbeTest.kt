// SPDX-License-Identifier: GPL-3.0-or-later
// Corrective-6 regression prepared from the frozen ROOT triage fixture.
// Same seven expectations; NOT COMPILED OR EXECUTED in this source-only turn.
package dev.lelonio.square.airpods.runtime.media

import dev.lelonio.square.airpods.runtime.AirPodsMediaGesture
import dev.lelonio.square.airpods.runtime.automation.*
import dev.lelonio.square.airpods.runtime.storage.HostSettings
import java.io.Closeable
import org.junit.Assert.*
import org.junit.Test

/** ROOT triage only: real core/coordinator/C6 provider, simulated platform facts and receipts. */
class ContinuationProbeTest {
    private class Queue : MediaRuntimeQueue {
        private val work = ArrayDeque<() -> Unit>()
        override fun post(block: () -> Unit) { work.addLast(block) }
        override fun later(delayMillis: Long, block: () -> Unit) = Closeable {}
        fun drain() {
            var count = 0
            while (work.isNotEmpty()) {
                check(++count < 1000) { "Unexpected callback cycle" }
                work.removeFirst()()
            }
        }
    }

    private class Env(val externalTarget: Boolean) : MediaRuntimeEnvironment {
        val provider = ExternalBestEffortProvider(AutomationClock { 100L })
        var main = MediaCandidate(
            MediaTarget(if (externalTarget) "other.music" else "dev.pampa.fluidify",
                "session-1", 1, if (externalTarget) "unverified-output" else "pair-route", "account-1"),
            Playback.PLAYING, MediaVolume(10, 15), local = !externalTarget,
            permitted = true, routedToPair = !externalTarget, callActive = false,
            foreignDuckActive = false,
            unavailableReason = if (externalTarget) "SESSION_ACTIVE_ROUTE_UNOBSERVABLE" else null)
        var extra: MediaCandidate? = null
        var facts = ExternalMediaFacts(true, true, true, true, "pair-route", false)
        val actions = mutableListOf<MediaAction>()
        var observer: (() -> Unit)? = null

        override fun configureExternal(enabled: Boolean) = provider.configure(enabled)
        override fun read() = provider.candidates(listOfNotNull(main, extra), facts)
        override fun observe(changed: () -> Unit): Closeable {
            observer = changed
            return Closeable { if (observer === changed) observer = null }
        }
        override fun readPhoneVolume() = PhoneStreamVolume("pair", main.volume!!)
        override fun writePhoneVolume(expected: PhoneStreamVolume, value: Int): Boolean {
            if (readPhoneVolume() != expected) return false
            actions.add(MediaAction.SET_VOLUME)
            main = main.copy(volume = MediaVolume(value, expected.volume.maximum))
            observer?.invoke()
            return true
        }
        override fun execute(target: MediaTarget, action: MediaAction, value: Int?, effectId: Long?): Boolean {
            val current = read().firstOrNull { it.target == target } ?: return false
            if (!current.permitted || !current.routedToPair) return false
            actions.add(action)
            if (externalTarget) provider.requested(current, action, value, effectId)
            main = when (action) {
                MediaAction.PAUSE -> main.copy(playback = Playback.PAUSED,
                    observedPauseEffect = effectId.takeUnless { externalTarget })
                MediaAction.PLAY -> main.copy(playback = Playback.PLAYING, observedPauseEffect = null)
                MediaAction.SET_VOLUME -> main.copy(volume = MediaVolume(value!!, 15),
                    observedVolumeEffect = effectId.takeUnless { externalTarget })
            }
            facts = facts.copy(exactPairStreaming = main.playback == Playback.PLAYING)
            // AndroidMediaEnvironment rereads facts before accepting its NLS callback.
            read()
            if (externalTarget) provider.currentTarget()?.let { callbackTarget ->
                if (action == MediaAction.SET_VOLUME) provider.volumeObserved(callbackTarget, value!!)
                else provider.playbackObserved(callbackTarget, main.playback, 100L)
            }
            observer?.invoke()
            return true
        }
        override fun gesture(target: MediaTarget, action: AirPodsMediaGesture) = true
    }

    private class Rig(external: Boolean, background: Boolean = true) {
        val env = Env(external)
        val queue = Queue()
        val core = MediaRuntimeCore(env, queue, AutomationClock { 100L })
        init {
            core.start(AutomationSession("pair", 7), HostSettings(automationsEnabled = true,
                fluidifyBackgroundEnabled = background, otherAppsEnabled = external,
                earPause = true, earResume = true))
            core.visible(true)
        }
        fun pause() {
            core.ear(EarState.BOTH)
            core.ear(EarState.NONE)
            queue.drain()
            assertEquals(listOf(MediaAction.PAUSE), env.actions)
        }
        fun resume() { core.ear(EarState.BOTH); queue.drain() }
    }

    private fun unrelated(local: Boolean, playback: Playback) = MediaCandidate(
        MediaTarget(if (local) "dev.pampa.fluidify" else "third.music", "session-2", 2, "pair-route", "account-2"),
        playback, MediaVolume(10, 15), local, true, local, false, false,
        unavailableReason = if (local) null else "SESSION_ACTIVE_ROUTE_UNOBSERVABLE")

    @Test fun control_externalOwnedPauseResumesWithSingleSession() {
        val r = Rig(external = true)
        r.pause()
        assertNotNull(r.env.read().single().correlatedPauseEffect)
        assertNull(r.env.read().single().observedPauseEffect)
        r.resume()
        assertEquals(listOf(MediaAction.PAUSE, MediaAction.PLAY), r.env.actions)
    }

    @Test fun contract_externalOwnedPauseResumesDespiteUnrelatedPausedLocalSession() {
        val r = Rig(external = true)
        r.env.extra = unrelated(local = true, playback = Playback.PAUSED)
        r.pause()
        r.resume()
        assertEquals("A paused unrelated local session must not erase owned C6 continuation",
            listOf(MediaAction.PAUSE, MediaAction.PLAY), r.env.actions)
    }

    @Test fun contract_providerKeepsOwnedHistoryWhenAnotherPausedSessionAppears() {
        val env = Env(externalTarget = true)
        env.configureExternal(true)
        val playing = env.read().single()
        env.provider.requested(playing, MediaAction.PAUSE, null, 41)
        env.main = env.main.copy(playback = Playback.PAUSED)
        env.facts = env.facts.copy(exactPairStreaming = false)
        env.provider.playbackObserved(playing.target, Playback.PAUSED, 100L)
        assertEquals(41L, env.read().single().correlatedPauseEffect)
        env.extra = unrelated(local = true, playback = Playback.PAUSED)
        val owned = env.read().first { !it.local }
        assertTrue("An unrelated paused session must preserve historical eligibility", owned.routedToPair)
        assertEquals(RouteEvidence.EXTERNAL_HISTORICAL_INFERENCE, owned.routeEvidence)
        assertEquals(41L, owned.correlatedPauseEffect)
        assertNull(owned.observedPauseEffect)
    }

    @Test fun safety_anotherPlayingSessionStillPreventsOldTargetResume() {
        val r = Rig(external = true)
        r.pause()
        r.env.extra = unrelated(local = true, playback = Playback.PLAYING)
        r.env.facts = r.env.facts.copy(exactPairStreaming = true)
        r.env.observer?.invoke()
        r.queue.drain()
        r.resume()
        assertEquals(listOf(MediaAction.PAUSE), r.env.actions)
    }

    @Test fun safety_backgroundOffCancelsQueuedExternalPause() {
        val r = Rig(external = true, background = false)
        r.core.ear(EarState.BOTH)
        r.core.ear(EarState.NONE)
        r.core.visible(false)
        r.queue.drain()
        assertTrue(r.env.actions.isEmpty())
    }

    @Test fun safety_externalPermissionRevocationStillPreventsResume() {
        val r = Rig(external = true)
        r.pause()
        r.env.facts = r.env.facts.copy(permitted = false)
        r.env.observer?.invoke()
        r.queue.drain()
        r.resume()
        assertEquals(listOf(MediaAction.PAUSE), r.env.actions)
    }

    @Test fun contract_manualVolumeRevokesEffectsWithoutErasingValidEarBaseline() {
        val r = Rig(external = false)
        r.core.ear(EarState.BOTH)
        r.queue.drain()
        assertTrue(r.core.manualVolume(9))
        r.queue.drain()
        r.core.ear(EarState.LEFT)
        r.queue.drain()
        assertEquals("Manual volume must not suppress the next independently observed removal",
            listOf(MediaAction.SET_VOLUME, MediaAction.PAUSE), r.env.actions)
    }
}
