// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.media

import dev.lelonio.square.airpods.runtime.AirPodsMediaGesture
import dev.lelonio.square.airpods.runtime.automation.*
import dev.lelonio.square.airpods.runtime.storage.HostSettings
import java.io.Closeable
import org.junit.Assert.*
import org.junit.Test

/** Prepared, not executed in the source-only turn. Real policy/core/C6; simulated platform. */
class AutomationContinuitySafetyTest {
    private class Queue : MediaRuntimeQueue {
        val work = ArrayDeque<() -> Unit>()
        val timers = mutableListOf<() -> Unit>()
        override fun post(block: () -> Unit) { work.addLast(block) }
        override fun later(delayMillis: Long, block: () -> Unit): Closeable {
            timers.add(block)
            return Closeable { timers.remove(block) }
        }
        fun drain() {
            var count = 0
            while (work.isNotEmpty()) {
                check(++count < 1_000) { "Unexpected callback cycle" }
                work.removeFirst()()
            }
        }
        fun fire() { val due = timers.toList(); timers.clear(); due.forEach { it() } }
    }

    private data class Sent(val target: MediaTarget, val action: MediaAction, val value: Int?, val id: Long?)

    private class Env(val externalTarget: Boolean, val clock: AutomationClock) : MediaRuntimeEnvironment {
        val provider = ExternalBestEffortProvider(clock)
        var main = MediaCandidate(MediaTarget(if (externalTarget) "other.music" else "fluidify",
            "session-1", 1, if (externalTarget) "unverified-output" else "pair-route", "account-1"),
            Playback.PLAYING, MediaVolume(10, 15), !externalTarget, true, !externalTarget, false, false,
            unavailableReason = if (externalTarget) "SESSION_ACTIVE_ROUTE_UNOBSERVABLE" else null)
        var extra: MediaCandidate? = null
        var facts = ExternalMediaFacts(true, true, true, true, "pair-route", false)
        var autoReceipt = true
        val sent = mutableListOf<Sent>()
        val gestures = mutableListOf<AirPodsMediaGesture>()
        val configurations = mutableListOf<Boolean>()
        var registrations = 0
        var observer: (() -> Unit)? = null
        override fun configureExternal(enabled: Boolean) {
            configurations.add(enabled)
            provider.configure(enabled)
        }
        override fun read() = provider.candidates(listOfNotNull(main, extra), facts)
        override fun observe(changed: () -> Unit): Closeable {
            registrations++
            observer = changed
            return Closeable { if (observer === changed) observer = null }
        }
        override fun readPhoneVolume() = PhoneStreamVolume("pair", main.volume!!)
        override fun writePhoneVolume(expected: PhoneStreamVolume, value: Int): Boolean {
            if (readPhoneVolume() != expected) return false
            sent.add(Sent(main.target, MediaAction.SET_VOLUME, value, null))
            main = main.copy(volume = MediaVolume(value, expected.volume.maximum))
            read()
            provider.currentTarget()?.let { provider.volumeObserved(it, value) }
            observer?.invoke()
            return true
        }
        override fun execute(target: MediaTarget, action: MediaAction, value: Int?, effectId: Long?): Boolean {
            val current = read().singleOrNull { it.target == target } ?: return false
            if (!current.permitted || !current.routedToPair || current.callActive) return false
            val command = Sent(target, action, value, effectId)
            sent.add(command)
            if (externalTarget) provider.requested(current, action, value, effectId)
            if (autoReceipt) apply(command)
            return true
        }
        fun apply(command: Sent) {
            main = when (command.action) {
                MediaAction.PAUSE -> main.copy(playback = Playback.PAUSED,
                    observedPauseEffect = command.id.takeUnless { externalTarget })
                MediaAction.PLAY -> main.copy(playback = Playback.PLAYING, observedPauseEffect = null)
                MediaAction.SET_VOLUME -> main.copy(volume = MediaVolume(command.value!!, 15),
                    observedVolumeEffect = command.id.takeUnless { externalTarget })
            }
            facts = facts.copy(exactPairStreaming = main.playback == Playback.PLAYING)
            // Match AndroidMediaEnvironment: fresh selection/facts BEFORE callback correlation.
            read()
            if (externalTarget) provider.currentTarget()?.let {
                if (command.action == MediaAction.SET_VOLUME) provider.volumeObserved(it, command.value!!)
                else provider.playbackObserved(it, main.playback, clock.nowMillis())
            }
            observer?.invoke()
        }
        override fun gesture(target: MediaTarget, action: AirPodsMediaGesture): Boolean {
            gestures.add(action)
            if (externalTarget) provider.invalidate()
            main = main.copy(transportRevision = main.transportRevision + 1)
            observer?.invoke()
            return true
        }
    }

    private class Rig(external: Boolean, val settings: HostSettings = enabled) {
        var now = 100L
        var authorized = true
        val clock = AutomationClock { now }
        val env = Env(external, clock)
        val queue = Queue()
        val core = MediaRuntimeCore(env, queue, clock, authority = { authorized })
        init { core.start(AutomationSession("pair", 7), settings); core.visible(true) }
        fun actions() = env.sent.map { it.action }
        fun volumes() = env.sent.filter { it.action == MediaAction.SET_VOLUME }.map { it.value }
        fun observe() { env.observer?.invoke(); queue.drain() }
        fun step(ms: Long, dispatch: Boolean = true) {
            now += ms; queue.fire(); if (dispatch) queue.drain()
        }
        fun removal() { core.ear(EarState.BOTH); core.ear(EarState.NONE) }
        fun pause() {
            removal(); queue.drain()
            assertEquals(listOf(MediaAction.PAUSE), actions())
        }
        fun resume() { core.ear(EarState.BOTH); queue.drain() }
    }

    private fun bystander(playback: Playback = Playback.PAUSED, local: Boolean = true) = MediaCandidate(
        MediaTarget("bystander", "session-2", 2, "pair-route", "account-2"), playback,
        MediaVolume(10, 15), local, true, local, false, false)

    @Test fun visibilityPreservesBaselineAndQueuedPauseForLocalAndC6() {
        for (external in listOf(false, true)) for (hideBeforeRemoval in listOf(false, true)) {
            val r = Rig(external)
            r.env.extra = bystander(local = !external)
            val registrations = r.env.registrations
            val configurations = r.env.configurations.toList()
            r.core.ear(EarState.BOTH)
            if (hideBeforeRemoval) r.core.visible(false)
            r.core.ear(EarState.LEFT)
            if (!hideBeforeRemoval) r.core.visible(false)
            r.queue.drain()
            assertEquals(listOf(MediaAction.PAUSE), r.actions())
            r.core.visible(true)
            r.core.ear(EarState.BOTH); r.queue.drain()
            assertEquals(listOf(MediaAction.PAUSE, MediaAction.PLAY), r.actions())
            assertEquals(registrations, r.env.registrations)
            assertEquals(configurations, r.env.configurations)
        }
    }

    @Test fun conversationDuckRetainsOneBaselineAcrossVisibilityAndPausedBystander() {
        for (external in listOf(false, true)) {
            val r = Rig(external, enabled.copy(conversationDuck = true))
            r.core.conversation(true); r.step(150)
            r.core.visible(false)
            r.env.extra = bystander(local = !external)
            r.step(150)
            assertEquals(4, r.env.main.volume!!.current)
            r.core.conversation(false); r.step(150)
            r.core.visible(true); r.step(150)
            assertEquals(listOf(7, 4, 7, 10), r.volumes())
        }
    }

    @Test fun bothPauseOriginsStillRequireLastBlockerAcrossVisibility() {
        for (external in listOf(false, true)) for (earFirst in listOf(false, true)) {
            val r = Rig(external, enabled.copy(conversationPause = true))
            r.core.ear(EarState.BOTH)
            if (earFirst) r.core.ear(EarState.NONE) else r.core.conversation(true)
            r.queue.drain()
            if (earFirst) r.core.conversation(true) else r.core.ear(EarState.NONE)
            r.step(300)
            r.core.visible(false)
            r.env.extra = bystander(local = !external)
            r.core.ear(EarState.BOTH); r.queue.drain()
            assertEquals(1, r.env.sent.count { it.action == MediaAction.PAUSE })
            assertFalse(r.actions().contains(MediaAction.PLAY))
            r.core.conversation(false); r.queue.drain(); r.step(300)
            assertEquals(1, r.env.sent.count { it.action == MediaAction.PLAY })
            assertTrue(r.volumes().isEmpty())
        }
    }

    @Test fun manualVolumeOrGestureKeepsBaselineForAnIndependentRemoval() {
        for (external in listOf(false, true)) for (volume in listOf(false, true)) {
            val r = Rig(external)
            r.core.ear(EarState.BOTH)
            if (volume) assertTrue(r.core.manualVolume(9))
            else assertTrue(r.core.gesture(AirPodsMediaGesture.NEXT))
            r.queue.drain()
            r.core.ear(EarState.LEFT); r.queue.drain()
            assertEquals(1, r.env.sent.count { it.action == MediaAction.PAUSE })
            assertEquals(if (volume) emptyList<AirPodsMediaGesture>() else listOf(AirPodsMediaGesture.NEXT),
                r.env.gestures)
        }
    }

    @Test fun manualIntentCancelsQueuedPauseWithoutReplayingDuplicateEar() {
        for (external in listOf(false, true)) {
            val r = Rig(external)
            r.removal()
            assertTrue(r.core.manualVolume(10)) // Equal level is still a manual intent.
            r.queue.drain()
            r.core.ear(EarState.NONE); r.queue.drain()
            assertEquals(listOf(MediaAction.SET_VOLUME), r.actions())
            r.core.ear(EarState.BOTH)
            r.core.ear(EarState.LEFT); r.queue.drain()
            assertEquals(listOf(MediaAction.SET_VOLUME, MediaAction.PAUSE), r.actions())
        }
    }

    @Test fun manualIntentRevokesOwnedOrQueuedResumeEvenWithRetainedReceipt() {
        for (external in listOf(false, true)) for (queuedResume in listOf(false, true)) {
            val r = Rig(external)
            r.pause()
            if (queuedResume) r.core.ear(EarState.BOTH)
            assertTrue(r.core.manualVolume(10))
            r.queue.drain()
            r.resume()
            assertEquals(listOf(MediaAction.PAUSE, MediaAction.SET_VOLUME), r.actions())
        }
    }

    @Test fun manualVolumeCancelsQueuedRestoreWithRealC6Provider() {
        for (external in listOf(false, true)) {
            val r = Rig(external, enabled.copy(conversationDuck = true))
            r.core.conversation(true); r.step(300)
            r.core.conversation(false); r.step(300, dispatch = false)
            assertTrue(r.core.manualVolume(9))
            r.queue.drain(); r.step(300)
            assertEquals(listOf(4, 9), r.volumes())
        }
    }

    @Test fun visibilityWithoutBackgroundConsentStillRevokesQueuedAndOwnedPause() {
        for (external in listOf(false, true)) for (owned in listOf(false, true)) {
            val r = Rig(external, enabled.copy(fluidifyBackgroundEnabled = false))
            r.removal()
            if (owned) r.queue.drain()
            r.core.visible(false); r.queue.drain()
            r.core.visible(true); r.resume()
            assertEquals(if (owned) listOf(MediaAction.PAUSE) else emptyList<MediaAction>(), r.actions())
        }
    }

    @Test fun visibilityWithoutBackgroundConsentNeverRestoresOwnedDuck() {
        for (external in listOf(false, true)) {
            val r = Rig(external, enabled.copy(fluidifyBackgroundEnabled = false, conversationDuck = true))
            r.core.conversation(true); r.step(300)
            r.core.visible(false)
            r.core.conversation(false); r.step(300)
            r.core.visible(true)
            r.core.conversation(false); r.step(300)
            assertEquals(listOf(4), r.volumes())
        }
    }

    @Test fun dispatchRechecksPermissionRouteAccountGenerationCallAuthorityConsentAndStop() {
        for (external in listOf(false, true)) for (change in 0..9) {
            val r = Rig(external)
            r.pause()
            r.core.ear(EarState.BOTH) // Queue PLAY while all facts are still valid.
            when (change) {
                0 -> {
                    r.env.main = r.env.main.copy(permitted = false)
                    r.env.facts = r.env.facts.copy(permitted = false)
                }
                1 -> {
                    r.env.main = r.env.main.copy(target = r.env.main.target.copy(routeId = "new-route"))
                    r.env.facts = r.env.facts.copy(routeKey = "new-route")
                }
                2 -> r.env.main = r.env.main.copy(target = r.env.main.target.copy(accountEpoch = "new-account"))
                3 -> r.env.main = r.env.main.copy(target = r.env.main.target.copy(sessionGeneration = 2))
                4 -> {
                    r.env.main = r.env.main.copy(callActive = true)
                    r.env.facts = r.env.facts.copy(callActive = true)
                }
                5 -> r.authorized = false
                6 -> r.core.configure(r.settings.copy(automationsEnabled = false))
                7 -> r.core.stop()
                8 -> r.core.start(AutomationSession("pair", 8), r.settings)
                9 -> {
                    r.env.main = r.env.main.copy(routedToPair = false)
                    r.env.facts = r.env.facts.copy(exactPairConnected = false)
                }
            }
            r.queue.drain()
            assertEquals("external=$external change=$change", listOf(MediaAction.PAUSE), r.actions())
        }
    }

    @Test fun competingPlayingSessionCountsEvenWhenRemoteUnpermittedOrUnrouted() {
        for (external in listOf(false, true)) for (kind in 0..2) {
            val r = Rig(external)
            r.pause()
            r.core.ear(EarState.BOTH)
            val contender = bystander(Playback.PLAYING, local = false)
            r.env.extra = when (kind) {
                0 -> contender.copy(unavailableReason = "REMOTE_PLAYBACK")
                1 -> contender.copy(permitted = false)
                else -> contender.copy(routedToPair = false)
            }
            r.env.facts = r.env.facts.copy(exactPairStreaming = true)
            r.queue.drain()
            r.env.extra = null
            r.env.facts = r.env.facts.copy(exactPairStreaming = false)
            r.observe(); r.resume()
            assertEquals(listOf(MediaAction.PAUSE), r.actions())
        }
    }

    @Test fun unknownOrDuplicatePausedTargetIsAmbiguousAndCannotRecoverOwnership() {
        for (external in listOf(false, true)) for (duplicate in listOf(false, true)) {
            val r = Rig(external)
            r.pause()
            r.env.extra = if (duplicate) r.env.main.copy() else bystander(Playback.UNKNOWN)
            r.resume()
            r.env.extra = null
            r.observe(); r.resume()
            assertEquals(listOf(MediaAction.PAUSE), r.actions())
        }
    }

    @Test fun providerKeepsPendingHistoryThroughFactRereadBeforePauseCallback() {
        val r = Rig(external = true)
        r.env.extra = bystander()
        r.env.autoReceipt = false
        r.pause()
        val request = r.env.sent.single()
        r.env.main = r.env.main.copy(playback = Playback.PAUSED)
        r.env.facts = r.env.facts.copy(exactPairStreaming = false)
        val pending = r.env.read().single { !it.local }
        assertTrue(pending.routedToPair)
        assertEquals(RouteEvidence.EXTERNAL_HISTORICAL_INFERENCE, pending.routeEvidence)
        assertNull(pending.correlatedPauseEffect)
        r.env.provider.playbackObserved(request.target, Playback.PAUSED, r.now)
        r.observe()
        val correlated = r.env.read().single { !it.local }
        assertEquals(request.id, correlated.correlatedPauseEffect)
        assertNull(correlated.observedPauseEffect)
        r.env.autoReceipt = true
        r.resume()
        assertEquals(listOf(MediaAction.PAUSE, MediaAction.PLAY), r.actions())
    }

    @Test fun pausedBystanderCannotCreateHistoryOrCompensateForMissingOrExpiredReceipt() {
        for (external in listOf(false, true)) for (expired in listOf(false, true)) {
            val r = Rig(external)
            r.env.autoReceipt = false
            r.pause()
            r.env.extra = bystander(local = !external)
            r.env.main = r.env.main.copy(playback = Playback.PAUSED)
            r.env.facts = r.env.facts.copy(exactPairStreaming = false)
            if (expired) {
                r.now += 2_000
                if (external) r.env.provider.playbackObserved(r.env.sent.single().target, Playback.PAUSED, r.now)
            }
            r.observe(); r.resume()
            assertEquals(listOf(MediaAction.PAUSE), r.actions())
        }
        val env = Env(true, AutomationClock { 100L })
        env.configureExternal(true)
        env.read() // A prior PLAYING observation alone is not our own pause history.
        env.main = env.main.copy(playback = Playback.PAUSED)
        env.facts = env.facts.copy(exactPairStreaming = false)
        env.extra = bystander()
        val paused = env.read().single { !it.local }
        assertFalse(paused.routedToPair)
        assertNull(paused.correlatedPauseEffect)
    }

    companion object {
        private val enabled = HostSettings(automationsEnabled = true, otherAppsEnabled = true,
            fluidifyBackgroundEnabled = true, earPause = true, earResume = true)
    }
}
