// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.media

import dev.lelonio.square.airpods.runtime.AirPodsMediaGesture
import dev.lelonio.square.airpods.runtime.automation.*
import dev.lelonio.square.airpods.runtime.storage.HostSettings
import java.io.Closeable
import org.junit.Assert.*
import org.junit.Test
import org.junit.FixMethodOrder
import org.junit.runners.MethodSorters

/** Isolated C6 PAUSE+duck probe. Real core/policy/provider; simulated deferred platform callbacks. */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class C6PauseDuckContinuationProbeTest {
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
        var delivery: ((() -> Unit) -> Unit) = { it() }
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
            if (autoReceipt) delivery { apply(command) }
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
        init {
            env.delivery = { queue.post(it) }
            core.start(AutomationSession("pair", 7), settings); core.visible(true)
        }
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

    private fun dump(r: Rig, label: String) {
        val c = r.env.read().single()
        println("PROBE $label local=${c.local} playback=${c.playback} volume=${c.volume?.current} " +
            "transportRevision=${c.transportRevision} volumeRevision=${c.volumeRevision} " +
            "evidence=${c.evidence} route=${c.routeEvidence} pauseCorrelation=${c.correlatedPauseEffect} " +
            "volumeCorrelation=${c.correlatedVolumeEffect} actions=${r.actions()}")
        if (!c.local) {
            assertEquals(MediaEvidence.EXTERNAL_BEST_EFFORT, c.evidence)
            assertNull(c.observedPauseEffect)
            assertNull(c.observedVolumeEffect)
        }
    }

    private fun combined(external: Boolean) {
        val r = Rig(external, enabled.copy(conversationPause = true, conversationDuck = true))
        dump(r, "initial")
        r.core.conversation(true); r.queue.drain()
        assertEquals(Playback.PAUSED, r.env.main.playback)
        r.step(300)
        dump(r, "owned-pause-and-duck")
        assertEquals(listOf(MediaAction.PAUSE, MediaAction.SET_VOLUME), r.actions())
        assertEquals(4, r.env.main.volume!!.current)
        r.core.conversation(false); r.queue.drain()
        dump(r, "after-own-playback-callback")
        assertEquals(Playback.PLAYING, r.env.main.playback)
        r.step(300)
        dump(r, "after-restore-deadline")
        assertEquals("Owned conversation duck must restore after its own resume callback", 10,
            r.env.main.volume!!.current)
        assertEquals(listOf(MediaAction.PAUSE, MediaAction.SET_VOLUME, MediaAction.PLAY,
            MediaAction.SET_VOLUME), r.actions())
    }

    @Test fun a_localCombinedPauseAndDuckRestores() = combined(external = false)

    @Test fun b_externalDuckOnlyRestores() {
        val r = Rig(true, enabled.copy(conversationDuck = true))
        r.core.conversation(true); r.queue.drain(); r.step(300)
        dump(r, "external-duck-only-owned")
        assertEquals(4, r.env.main.volume!!.current)
        r.core.conversation(false); r.queue.drain(); r.step(300)
        dump(r, "external-duck-only-restored")
        assertEquals(listOf(4, 10), r.volumes())
        assertEquals(listOf(MediaAction.SET_VOLUME, MediaAction.SET_VOLUME), r.actions())
    }

    @Test fun c_externalPauseOnlyResumes() {
        val r = Rig(true, enabled.copy(conversationPause = true))
        r.core.conversation(true); r.queue.drain()
        dump(r, "external-pause-only-owned")
        r.core.conversation(false); r.queue.drain()
        dump(r, "external-pause-only-resumed")
        assertEquals(listOf(MediaAction.PAUSE, MediaAction.PLAY), r.actions())
        assertEquals(10, r.env.main.volume!!.current)
    }

    @Test fun d_externalCombinedPauseAndDuckRestores() = combined(external = true)

    companion object {
        private val enabled = HostSettings(automationsEnabled = true, otherAppsEnabled = true,
            fluidifyBackgroundEnabled = true, earPause = true, earResume = true)
    }
}
