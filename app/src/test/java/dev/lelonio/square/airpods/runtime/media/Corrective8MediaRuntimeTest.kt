// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.media

import dev.lelonio.square.airpods.runtime.AirPodsMediaGesture
import dev.lelonio.square.airpods.runtime.automation.*
import dev.lelonio.square.airpods.runtime.storage.HostSettings
import java.io.Closeable
import org.junit.Assert.*
import org.junit.Test

/** Real runtime/coordinator/C6 entrances; queue, platform facts and local receipts are simulated.
 * The actual local producer's empty-player fact is covered in PlaybackAirPodsMediaBridgeTest.
 * Prepared only: this suite is not evidence of execution or physical audio behavior.
 */
class Corrective8MediaRuntimeTest {
    private class Queue : MediaRuntimeQueue {
        val work = ArrayDeque<() -> Unit>()
        val timers = mutableListOf<() -> Unit>()
        override fun post(block: () -> Unit) { work.addLast(block) }
        override fun later(delayMillis: Long, block: () -> Unit): Closeable {
            timers.add(block)
            return Closeable { timers.remove(block) }
        }
        fun drain() {
            var n = 0
            while (work.isNotEmpty()) { check(++n < 2_000); work.removeFirst()() }
        }
        fun fire() { val due = timers.toList(); timers.clear(); due.forEach { it() } }
    }

    private class Env(val external: Boolean, private val clock: AutomationClock) : MediaRuntimeEnvironment {
        val provider = ExternalBestEffortProvider(clock)
        val idleTarget = MediaTarget(if (external) "other.music" else "fluidify", "session-1", 1,
            if (external) "unverified-local-output" else "unavailable", "account-1")
        val playingTarget = if (external) idleTarget else idleTarget.copy(routeId = "audiotrack:1:7")
        var main = MediaCandidate(idleTarget, Playback.PAUSED, MediaVolume(10, 15), !external,
            true, false, false, false,
            unavailableReason = if (external) "SESSION_ACTIVE_ROUTE_UNOBSERVABLE" else "LOCAL_RENDERER_ROUTE_UNAVAILABLE")
        var extra: MediaCandidate? = null
        var present = true
        var facts = ExternalMediaFacts(true, true, false, true, "pair-route", false)
        var observer: (() -> Unit)? = null
        val registrations = mutableListOf<() -> Unit>()
        val sent = mutableListOf<Triple<MediaAction, Int?, Long?>>()
        val awaiting = ArrayDeque<Triple<MediaAction, Int?, Long?>>()
        var applyImmediately = true
        var configureCount = 0
        override fun configureExternal(enabled: Boolean) { configureCount++; provider.configure(enabled) }
        override fun read() = provider.candidates(listOfNotNull(main.takeIf { present }, extra), facts)
        override fun observe(changed: () -> Unit): Closeable {
            observer = changed; registrations.add(changed)
            return Closeable { if (observer === changed) observer = null }
        }
        override fun readPhoneVolume() = PhoneStreamVolume("pair", main.volume!!)
        override fun execute(target: MediaTarget, action: MediaAction, value: Int?, effectId: Long?): Boolean {
            val current = read().singleOrNull { it.target == target } ?: return false
            if (!current.permitted || !current.routedToPair || current.callActive) return false
            val effect = Triple(action, value, effectId)
            sent.add(effect)
            if (external) provider.requested(current, action, value, effectId)
            if (applyImmediately) apply(effect) else awaiting.addLast(effect)
            return true
        }
        fun apply(effect: Triple<MediaAction, Int?, Long?>) {
            when (effect.first) {
                MediaAction.PAUSE -> transition(Playback.PAUSED, effect.third)
                MediaAction.PLAY -> transition(Playback.PLAYING)
                MediaAction.SET_VOLUME -> {
                    main = main.copy(volume = MediaVolume(effect.second!!, 15),
                        observedVolumeEffect = effect.third.takeUnless { external })
                    read()
                    if (external) provider.currentTarget()?.let { provider.volumeObserved(it, effect.second!!) }
                    observer?.invoke()
                }
            }
        }
        fun transition(state: Playback, localReceipt: Long? = null) {
            main = main.copy(playback = state,
                observedPauseEffect = localReceipt.takeIf { state == Playback.PAUSED && !external })
            facts = facts.copy(exactPairStreaming = state == Playback.PLAYING)
            // Exactly the environment's read -> callback correlation -> observer order.
            read()
            if (external) provider.currentTarget()?.let {
                provider.playbackObserved(it, state, clock.nowMillis())
            }
            observer?.invoke()
        }
        fun startPlayback() {
            present = true
            main = main.copy(target = playingTarget, routedToPair = !external,
                knownEmptyLocal = false,
                unavailableReason = if (external) "SESSION_ACTIVE_ROUTE_UNOBSERVABLE" else null)
            transition(Playback.PLAYING)
        }
        fun manualPause() {
            if (external) transition(Playback.PAUSED) else {
                main = main.copy(transportRevision = main.transportRevision + 1, observedPauseEffect = null)
                observer?.invoke()
            }
        }
        override fun gesture(target: MediaTarget, action: AirPodsMediaGesture) = true
    }

    private class Rig(external: Boolean, val settings: HostSettings = all, playing: Boolean = true) {
        var now = 100L
        val clock = AutomationClock { now }
        val env = Env(external, clock)
        val queue = Queue()
        val core = MediaRuntimeCore(env, queue, clock)
        init {
            core.start(AutomationSession("pair", 7), settings); core.visible(true)
            if (playing) env.startPlayback()
            queue.drain()
        }
        fun observe() { env.observer?.invoke(); queue.drain() }
        fun removal() { core.ear(EarState.BOTH); core.ear(EarState.NONE) }
        fun pause() { removal(); queue.drain(); assertEquals(listOf(MediaAction.PAUSE), actions()) }
        fun actions() = env.sent.map { it.first }
        fun volumes() = env.sent.filter { it.first == MediaAction.SET_VOLUME }.map { it.second }
        fun step(ms: Long = 150) { now += ms; queue.fire(); queue.drain() }
        fun complete() { env.apply(env.awaiting.removeFirst()); queue.drain() }
    }

    @Test fun liveEarBeforeFirstPlaybackStillPausesOnFirstRemoval() {
        for (external in listOf(false, true)) for (idle in 0..2)
            for (removed in listOf(EarState.LEFT, EarState.RIGHT, EarState.NONE)) {
            val r = Rig(external, playing = false)
            if (idle == 1) r.env.main = r.env.main.copy(playback = Playback.UNKNOWN, knownEmptyLocal = !external)
            if (idle == 2) r.env.present = false
            r.core.ear(EarState.BOTH); r.queue.drain()
            r.env.startPlayback(); r.queue.drain()
            assertTrue("media recovery must be silent", r.actions().isEmpty())
            r.core.ear(removed); r.queue.drain()
            assertEquals("external=$external idle=$idle removed=$removed", listOf(MediaAction.PAUSE), r.actions())
        }
    }

    @Test fun noReplayOfRemovalBeforePlaybackOrUnknownSensorBaseline() {
        for (external in listOf(false, true)) {
            val r = Rig(external, playing = false)
            r.core.ear(EarState.BOTH); r.core.ear(EarState.NONE); r.queue.drain()
            r.env.startPlayback(); r.queue.drain()
            r.core.ear(EarState.NONE); r.queue.drain()
            assertTrue(r.actions().isEmpty())
            r.core.ear(EarState.UNKNOWN); r.core.ear(EarState.LEFT); r.queue.drain()
            assertTrue(r.actions().isEmpty())
            r.core.ear(EarState.NONE); r.queue.drain()
            assertEquals(listOf(MediaAction.PAUSE), r.actions())
        }
    }

    @Test fun identicalReapplyRetainsSensorAndDoesNotReconfigureProviderOrObserver() {
        for (external in listOf(false, true)) {
            val r = Rig(external)
            r.core.ear(EarState.BOTH); r.queue.drain()
            val calls = r.env.configureCount
            val observer = r.env.observer
            r.core.configure(all.copy()); r.queue.drain()
            assertEquals(calls, r.env.configureCount); assertSame(observer, r.env.observer)
            assertTrue(r.actions().isEmpty())
            r.core.ear(EarState.LEFT); r.queue.drain()
            assertEquals(listOf(MediaAction.PAUSE), r.actions())
        }
    }

    @Test fun identicalReapplyRetainsOwnedQueuedAndAwaitingEarPause() {
        for (external in listOf(false, true)) for (stage in 0..2) {
            val r = Rig(external)
            r.env.applyImmediately = stage != 2
            r.removal()
            if (stage != 1) r.queue.drain() // stage 1: PAUSE still queued; stage 2: receipt pending.
            r.core.configure(all.copy()); r.queue.drain()
            if (stage == 2) { r.complete(); r.env.applyImmediately = true }
            r.core.ear(EarState.BOTH); r.queue.drain()
            assertEquals("external=$external stage=$stage", listOf(MediaAction.PAUSE, MediaAction.PLAY), r.actions())
        }
    }

    @Test fun identicalReapplyRetainsOwnedAndAwaitingDuckAndOriginalVolume() {
        val settings = all.copy(conversationDuck = true)
        for (external in listOf(false, true)) for (pendingReceipt in listOf(false, true)) {
            val r = Rig(external, settings)
            r.env.applyImmediately = !pendingReceipt
            r.core.conversation(true); r.queue.drain(); r.step(300)
            r.core.configure(settings.copy()); r.queue.drain()
            if (pendingReceipt) { r.complete(); r.env.applyImmediately = true }
            assertEquals(4, r.env.main.volume!!.current)
            r.core.conversation(false); r.queue.drain(); r.step(300)
            assertEquals("external=$external pending=$pendingReceipt", listOf(4, 10), r.volumes())
            assertEquals(10, r.env.main.volume!!.current)
        }
    }

    @Test fun foregroundRoundTripRetainsSensorButNeverRevivesOwnedEffects() {
        for (external in listOf(false, true)) {
            val settings = all.copy(fluidifyBackgroundEnabled = false)
            val fresh = Rig(external, settings)
            fresh.core.ear(EarState.BOTH); fresh.queue.drain()
            fresh.core.visible(false); fresh.core.visible(true); fresh.queue.drain()
            assertTrue(fresh.actions().isEmpty())
            fresh.core.ear(EarState.LEFT); fresh.queue.drain()
            assertEquals(listOf(MediaAction.PAUSE), fresh.actions())

            val owned = Rig(external, settings); owned.pause()
            owned.core.visible(false); owned.core.visible(true); owned.queue.drain()
            owned.core.ear(EarState.BOTH); owned.queue.drain()
            assertEquals("discarded ownership cannot return", listOf(MediaAction.PAUSE), owned.actions())
        }
    }

    @Test fun hiddenRemovalAndQueuedEffectsCannotRunAfterForegroundRecovery() {
        for (external in listOf(false, true)) for (queued in listOf(false, true)) {
            val r = Rig(external, all.copy(fluidifyBackgroundEnabled = false))
            r.core.ear(EarState.BOTH)
            if (queued) r.core.ear(EarState.NONE)
            r.core.visible(false)
            if (!queued) r.core.ear(EarState.NONE)
            r.core.visible(true); r.queue.drain()
            r.core.ear(EarState.NONE); r.queue.drain()
            assertTrue("external=$external queued=$queued", r.actions().isEmpty())
        }
    }

    @Test fun emptyLocalUnknownDoesNotBlockOwnedExternalResume() {
        val r = Rig(true)
        r.env.extra = emptyLocal()
        r.pause()
        val own = r.env.read().first { !it.local }
        assertEquals(RouteEvidence.EXTERNAL_HISTORICAL_INFERENCE, own.routeEvidence)
        assertNotNull(own.correlatedPauseEffect); assertNull(own.observedPauseEffect)
        r.core.ear(EarState.BOTH); r.queue.drain()
        assertEquals(listOf(MediaAction.PAUSE, MediaAction.PLAY), r.actions())
    }

    @Test fun realUnknownOrPlayingContenderRevokesExternalPauseAndCannotRecoverIt() {
        val ambiguous = listOf(emptyLocal().copy(knownEmptyLocal = false),
            emptyLocal().copy(local = false), // An external UNKNOWN is never attested empty.
            emptyLocal().copy(playback = Playback.PLAYING), // Even a contradictory empty bit cannot hide PLAYING.
            emptyLocal().copy(knownEmptyLocal = false, unavailableReason = "REMOTE_PLAYBACK"))
        for (bystander in ambiguous) {
            val r = Rig(true); r.pause()
            r.env.extra = bystander
            r.observe()
            r.env.extra = emptyLocal(); r.observe()
            r.core.ear(EarState.BOTH); r.queue.drain()
            assertEquals(listOf(MediaAction.PAUSE), r.actions())
        }
    }

    @Test fun multiplePlayingEvenWithEmptyBitCannotAcquirePause() {
        for (external in listOf(false, true)) {
            val r = Rig(external)
            r.env.extra = emptyLocal().copy(playback = Playback.PLAYING)
            r.removal(); r.queue.drain()
            assertTrue(r.actions().isEmpty())
        }
    }

    @Test fun identicalReapplyStillRereadsManualOverrideAndRevokesResume() {
        for (external in listOf(false, true)) {
            val r = Rig(external); r.pause()
            r.env.manualPause() // Do not drain observer: configure must perform its own fresh read.
            r.core.configure(all.copy()); r.queue.drain()
            r.core.ear(EarState.BOTH); r.queue.drain()
            assertEquals(listOf(MediaAction.PAUSE), r.actions())
        }
    }

    @Test fun targetAccountRouteCallAndPermissionChangesStillRevokeOwnedPause() {
        for (external in listOf(false, true)) for (change in 0..6) {
            val r = Rig(external); r.pause()
            when (change) {
                0 -> r.env.main = r.env.main.copy(target = r.env.main.target.copy(accountEpoch = "account-2"))
                1 -> r.env.main = r.env.main.copy(target = r.env.main.target.copy(sessionGeneration = 2))
                2 -> if (external) r.env.facts = r.env.facts.copy(routeKey = "new-route")
                    else r.env.main = r.env.main.copy(target = r.env.main.target.copy(routeId = "speaker"))
                3 -> if (external) r.env.facts = r.env.facts.copy(callActive = true)
                    else r.env.main = r.env.main.copy(callActive = true)
                4 -> if (external) r.env.facts = r.env.facts.copy(permitted = false)
                    else r.env.main = r.env.main.copy(permitted = false)
                5 -> if (external) r.env.facts = r.env.facts.copy(exactPairConnected = false)
                    else r.env.main = r.env.main.copy(routedToPair = false)
                6 -> r.env.extra = emptyLocal().copy(knownEmptyLocal = false)
            }
            r.core.configure(all.copy()); r.queue.drain()
            r.core.ear(EarState.BOTH); r.queue.drain()
            assertEquals("external=$external change=$change", listOf(MediaAction.PAUSE), r.actions())
        }
    }

    @Test fun realConsentConfigurationAndSessionChangesStillFenceOldWork() {
        for (external in listOf(false, true)) for (queued in listOf(false, true)) for (change in 0..3) {
            val r = Rig(external)
            r.removal(); if (!queued) r.queue.drain()
            val staleObserver = r.env.registrations.last()
            when (change) {
                0 -> r.core.configure(all.copy(automationsEnabled = false))
                1 -> r.core.configure(all.copy(earResume = false))
                2 -> r.core.start(AutomationSession("pair", 8), all)
                3 -> r.core.start(AutomationSession("second-pair", 7), all)
            }
            staleObserver(); r.queue.drain()
            r.core.ear(EarState.BOTH); r.queue.drain()
            assertEquals("external=$external queued=$queued change=$change",
                if (queued) emptyList<MediaAction>() else listOf(MediaAction.PAUSE), r.actions())
        }
    }

    @Test fun externalConsentRevocationCannotBeUndoneByIdenticalReapply() {
        val r = Rig(true); r.pause()
        val revoked = all.copy(otherAppsEnabled = false)
        r.core.configure(revoked); r.core.configure(revoked.copy()); r.queue.drain()
        r.core.ear(EarState.BOTH); r.queue.drain()
        assertEquals(listOf(MediaAction.PAUSE), r.actions())
        assertFalse(r.env.read().first().routedToPair)
    }

    @Test fun manualVolumeOverrideStillForfeitsDuckAfterIdenticalReapply() {
        for (external in listOf(false, true)) {
            val settings = all.copy(conversationDuck = true)
            val r = Rig(external, settings)
            r.core.conversation(true); r.queue.drain(); r.step(300)
            r.env.main = r.env.main.copy(volume = MediaVolume(5, 15), volumeRevision = 1,
                observedVolumeEffect = null)
            if (external) r.env.provider.currentTarget()?.let { r.env.provider.volumeObserved(it, 5) }
            r.core.configure(settings.copy()); r.queue.drain()
            r.core.conversation(false); r.queue.drain(); r.step(300)
            assertEquals(listOf(4), r.volumes())
            assertEquals(5, r.env.main.volume!!.current)
        }
    }

    companion object {
        private val all = HostSettings(automationsEnabled = true, otherAppsEnabled = true,
            fluidifyBackgroundEnabled = true, earPause = true, earResume = true)
        private fun emptyLocal() = MediaCandidate(MediaTarget("fluidify", "empty", 9, "unavailable", "0"),
            Playback.UNKNOWN, MediaVolume(10, 15), true, true, false, false, false,
            unavailableReason = "LOCAL_RENDERER_ROUTE_UNAVAILABLE", knownEmptyLocal = true)
    }
}
