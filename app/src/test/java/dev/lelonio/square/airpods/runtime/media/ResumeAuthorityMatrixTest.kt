// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.media

import dev.lelonio.square.airpods.runtime.AirPodsMediaGesture
import dev.lelonio.square.airpods.runtime.automation.*
import dev.lelonio.square.airpods.runtime.storage.HostSettings
import java.io.Closeable
import org.junit.Assert.*
import org.junit.Test

/**
 * Corrective-7 policy matrix, prepared and NOT executed in the source-only turn. Real core,
 * coordinator and C6 provider; platform callbacks are simulated in AndroidMediaEnvironment order
 * (fresh read, then correlation, then signal). Default ear preferences unless stated: earPause
 * and earResume OFF. The real local player/provider chain is LocalResumeAuthorityRuntimeTest.
 */
class ResumeAuthorityMatrixTest {
    private class Queue : MediaRuntimeQueue {
        val work = ArrayDeque<() -> Unit>()
        val timers = mutableListOf<() -> Unit>()
        override fun post(block: () -> Unit) { work.addLast(block) }
        override fun later(delayMillis: Long, block: () -> Unit): Closeable {
            timers.add(block); return Closeable { timers.remove(block) }
        }
        fun drain() { var n = 0; while (work.isNotEmpty()) { check(++n < 2_000); work.removeFirst()() } }
        fun fire() { val due = timers.toList(); timers.clear(); due.forEach { it() } }
    }

    private class Env(val external: Boolean, val clock: AutomationClock) : MediaRuntimeEnvironment {
        val provider = ExternalBestEffortProvider(clock)
        var main = MediaCandidate(MediaTarget(if (external) "other.music" else "fluidify", "session-1", 1,
            if (external) "unverified-local-output" else "pair-route", "account-1"),
            Playback.PLAYING, MediaVolume(10, 15), !external, true, !external, false, false,
            unavailableReason = if (external) "SESSION_ACTIVE_ROUTE_UNOBSERVABLE" else null)
        var facts = ExternalMediaFacts(true, true, true, true, "pair-route", false)
        /** Reports after our PLAY, in order; the flag marks a platform interruption state. */
        var resumeVia: List<Pair<Playback, Boolean>> = emptyList()
        /** Whether the target finally reports PLAYING (on the next looper turn). */
        var resumeConfirms = true
        val deferred = ArrayDeque<() -> Unit>()
        val sent = mutableListOf<Pair<MediaAction, Int?>>()
        var observer: (() -> Unit)? = null
        override fun configureExternal(enabled: Boolean) { provider.configure(enabled) }
        override fun read() = provider.candidates(listOf(main), facts)
        override fun observe(changed: () -> Unit): Closeable {
            observer = changed
            return Closeable { if (observer === changed) observer = null }
        }
        override fun readPhoneVolume() = PhoneStreamVolume("pair", main.volume!!)
        override fun execute(target: MediaTarget, action: MediaAction, value: Int?, effectId: Long?): Boolean {
            val current = read().singleOrNull { it.target == target } ?: return false
            if (!current.permitted || !current.routedToPair || current.callActive) return false
            sent += action to value
            if (external) provider.requested(current, action, value, effectId)
            when (action) {
                MediaAction.PAUSE -> transition(Playback.PAUSED, pauseReceipt = effectId)
                MediaAction.PLAY -> {
                    resumeVia.forEach { (state, interruption) -> transition(state, interruption = interruption) }
                    if (resumeConfirms) deferred.addLast { transition(Playback.PLAYING) }
                }
                MediaAction.SET_VOLUME -> {
                    main = main.copy(volume = MediaVolume(value!!, 15),
                        observedVolumeEffect = effectId.takeUnless { external })
                    read(); if (external) provider.currentTarget()?.let { provider.volumeObserved(it, value) }
                    observer?.invoke()
                }
            }
            return true
        }
        fun transition(state: Playback, pauseReceipt: Long? = null, interruption: Boolean = false) {
            main = main.copy(playback = state,
                observedPauseEffect = pauseReceipt.takeIf { state == Playback.PAUSED && !external })
            facts = facts.copy(exactPairStreaming = state == Playback.PLAYING)
            read()
            if (external) provider.currentTarget()?.let {
                provider.playbackObserved(it, state, clock.nowMillis(), interruption)
            }
            observer?.invoke()
        }
        /** A command outside the automation, as the local provider reports it (revision). */
        fun localOverride() {
            main = main.copy(transportRevision = main.transportRevision + 1)
            observer?.invoke()
        }
        override fun gesture(target: MediaTarget, action: AirPodsMediaGesture) = true
    }

    private class Rig(external: Boolean, val settings: HostSettings) {
        var now = 100L
        val clock = AutomationClock { now }
        val env = Env(external, clock)
        val queue = Queue()
        val core = MediaRuntimeCore(env, queue, clock)
        init { core.start(AutomationSession("pair", 7), settings); core.visible(true) }
        fun transport() = env.sent.map { it.first }.filter { it != MediaAction.SET_VOLUME }
        fun volume() = env.main.volume!!.current
        fun step(ms: Long) {
            while (env.deferred.isNotEmpty()) { env.deferred.removeFirst()(); queue.drain() }
            now += ms; queue.fire(); queue.drain()
        }
        fun ramp() = repeat(10) { step(50) }
    }

    private enum class Via { DIRECT, BUFFERING, UNCONFIRMED, BUFFERING_UNCONFIRMED }

    private val defaults = HostSettings(automationsEnabled = true, otherAppsEnabled = true,
        fluidifyBackgroundEnabled = true)

    @Test fun conversationPauseResumesAndOwnedDuckRestoresWithDefaultEarPreferences() {
        for (external in listOf(false, true)) for (duck in listOf(false, true)) for (via in Via.values()) {
            val label = "external=$external duck=$duck via=$via"
            val r = Rig(external, defaults.copy(conversationPause = true, conversationDuck = duck))
            r.env.resumeVia = if (via == Via.BUFFERING || via == Via.BUFFERING_UNCONFIRMED)
                listOf(Playback.UNKNOWN to false) else emptyList()
            r.env.resumeConfirms = via == Via.DIRECT || via == Via.BUFFERING
            r.core.conversation(true); r.queue.drain(); r.ramp()
            assertEquals(label, if (duck) 4 else 10, r.volume())
            r.core.conversation(false); r.queue.drain(); r.ramp()
            assertEquals(label, listOf(MediaAction.PAUSE, MediaAction.PLAY), r.transport())
            assertEquals(label, 10, r.volume())
            val sent = r.env.sent.size
            r.step(2_000); r.step(50) // past every bound: nothing replays or rolls back
            assertEquals(label, sent, r.env.sent.size)
            assertEquals(label, 10, r.volume())
        }
    }

    @Test fun earPauseWithDefaultResumeOffNeverResumesOnReinsertion() {
        for (external in listOf(false, true)) {
            val r = Rig(external, defaults.copy(earPause = true))
            r.core.ear(EarState.BOTH); r.core.ear(EarState.NONE); r.queue.drain()
            r.core.ear(EarState.BOTH); r.queue.drain(); r.step(50)
            assertEquals("external=$external", listOf(MediaAction.PAUSE), r.transport())
        }
    }

    @Test fun overlapWithDefaultEarResumeFollowsOriginAndLastReleasedBlocker() {
        for (external in listOf(false, true)) for (caOrigin in listOf(false, true))
            for (earLast in listOf(false, true)) {
                val label = "external=$external caOrigin=$caOrigin earLast=$earLast"
                val r = Rig(external, defaults.copy(earPause = true, conversationPause = true))
                r.core.ear(EarState.BOTH)
                if (caOrigin) r.core.conversation(true) else r.core.ear(EarState.LEFT)
                r.queue.drain()
                if (caOrigin) r.core.ear(EarState.LEFT) else r.core.conversation(true)
                r.queue.drain()
                if (earLast) { r.core.conversation(false); r.core.ear(EarState.BOTH) }
                else { r.core.ear(EarState.BOTH); r.core.conversation(false) }
                r.queue.drain(); r.step(50)
                val resumes = caOrigin && !earLast
                assertEquals(label, if (resumes) listOf(MediaAction.PAUSE, MediaAction.PLAY)
                    else listOf(MediaAction.PAUSE), r.transport())
            }
    }

    @Test fun earResumeOnStillGovernsEarReleasedLastAfterConversationOrigin() {
        for (external in listOf(false, true)) {
            val r = Rig(external, defaults.copy(earPause = true, earResume = true, conversationPause = true))
            r.core.ear(EarState.BOTH); r.core.conversation(true); r.queue.drain()
            r.core.ear(EarState.LEFT); r.core.conversation(false); r.core.ear(EarState.BOTH)
            r.queue.drain(); r.step(50)
            assertEquals("external=$external", listOf(MediaAction.PAUSE, MediaAction.PLAY), r.transport())
        }
    }

    @Test fun interventionWhileOwnResumeIsBufferingRevokesRestore() {
        // 0: local command (provider revision); 1: external app paused; 2: external app stopped.
        for (kind in 0..2) {
            val external = kind > 0
            val r = Rig(external, defaults.copy(conversationPause = true, conversationDuck = true))
            r.env.resumeVia = listOf(Playback.UNKNOWN to false); r.env.resumeConfirms = false
            r.core.conversation(true); r.queue.drain(); r.ramp()
            assertEquals(4, r.volume())
            r.core.conversation(false); r.queue.drain() // our PLAY dispatched, target buffering
            when (kind) {
                0 -> r.env.localOverride()
                1 -> r.env.transition(Playback.PAUSED)
                else -> r.env.transition(Playback.UNKNOWN, interruption = true)
            }
            r.queue.drain(); r.ramp()
            r.env.transition(Playback.PLAYING); r.queue.drain(); r.ramp()
            assertEquals("kind=$kind", 4, r.volume())
            assertEquals("kind=$kind", listOf(MediaAction.PAUSE, MediaAction.PLAY), r.transport())
        }
    }

    @Test fun interventionAfterConfirmedOwnResumeRevokesRestore() {
        for (external in listOf(false, true)) {
            val r = Rig(external, defaults.copy(conversationPause = true, conversationDuck = true))
            r.core.conversation(true); r.queue.drain(); r.ramp()
            r.core.conversation(false); r.queue.drain()
            r.step(0) // our PLAY is confirmed by the target before any restore step
            if (external) r.env.transition(Playback.PAUSED) else r.env.localOverride()
            r.queue.drain(); r.ramp()
            assertEquals("external=$external", 4, r.volume())
        }
    }

    @Test fun consentRevocationWhileOwnResumeIsPendingNeverRestores() {
        for (external in listOf(false, true)) {
            val settings = defaults.copy(conversationPause = true, conversationDuck = true)
            val r = Rig(external, settings)
            r.env.resumeVia = listOf(Playback.UNKNOWN to false)
            r.core.conversation(true); r.queue.drain(); r.ramp()
            r.core.conversation(false); r.queue.drain()
            r.core.configure(if (external) settings.copy(otherAppsEnabled = false)
                else settings.copy(automationsEnabled = false))
            r.queue.drain(); r.ramp()
            assertEquals("external=$external", 4, r.volume())
            assertEquals(listOf(MediaAction.PAUSE, MediaAction.PLAY), r.transport())
        }
    }

    @Test fun unknownBeforeOurPlayIsDispatchedStillCancelsIt() {
        for (external in listOf(false, true)) {
            val r = Rig(external, defaults.copy(conversationPause = true, conversationDuck = true))
            r.core.conversation(true); r.queue.drain(); r.ramp()
            r.core.conversation(false) // PLAY queued, not yet dispatched
            r.env.transition(Playback.UNKNOWN, interruption = true)
            r.queue.drain(); r.ramp()
            assertEquals("external=$external", listOf(MediaAction.PAUSE), r.transport())
            assertEquals("external=$external", 4, r.volume())
        }
    }
}
