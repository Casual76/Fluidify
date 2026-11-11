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

class ExpectedAutomationContractProbeTest {
    private class Queue : MediaRuntimeQueue {
        val work = ArrayDeque<() -> Unit>()
        val timers = mutableListOf<() -> Unit>()
        override fun post(block: () -> Unit) { work.addLast(block) }
        override fun later(delayMillis: Long, block: () -> Unit): Closeable {
            timers.add(block); return Closeable { timers.remove(block) }
        }
        fun drain() { var guard = 0; while (work.isNotEmpty() && guard++ < 10_000) work.removeFirst()() }
        fun fire() { val t = timers.toList(); timers.clear(); t.forEach { it() } }
    }

    /** Local Fluidify player routed to the pair; optional extra (external) session. */
    private class Env : MediaRuntimeEnvironment {
        var local = MediaCandidate(MediaTarget("dev.lelonio.square", "local", 1, "pair-route", "account-1"),
            Playback.PLAYING, MediaVolume(10, 15), local = true, permitted = true, routedToPair = true,
            callActive = false, foreignDuckActive = false)
        var extra: MediaCandidate? = null
        val effects = mutableListOf<Triple<MediaAction, Int?, Long?>>()
        var observer: (() -> Unit)? = null
        var autoReceipt = false
        override fun readPhoneVolume() = local.volume?.let { PhoneStreamVolume("pair", it) }
        override fun read() = listOfNotNull(local, extra)
        override fun observe(changed: () -> Unit): Closeable {
            observer = changed
            return Closeable { if (observer === changed) observer = null }
        }
        override fun execute(target: MediaTarget, action: MediaAction, value: Int?, effectId: Long?): Boolean {
            effects.add(Triple(action, value, effectId))
            if (autoReceipt) {
                local = when (action) {
                    MediaAction.PAUSE -> local.copy(playback = Playback.PAUSED, observedPauseEffect = effectId)
                    MediaAction.PLAY -> local.copy(playback = Playback.PLAYING, observedPauseEffect = null)
                    MediaAction.SET_VOLUME -> local.copy(volume = MediaVolume(value!!, local.volume!!.maximum),
                        observedVolumeEffect = effectId)
                }
                observer?.invoke()
            }
            return true
        }
        override fun gesture(target: MediaTarget, action: AirPodsMediaGesture) = true
    }

    private class Rig(settings: HostSettings) {
        val env = Env().apply { autoReceipt = true }
        val queue = Queue()
        var now = 0L
        val core = MediaRuntimeCore(env, queue, AutomationClock { now })
        init { core.start(AutomationSession("pair", 7), settings) }
        fun actions() = env.effects.map { it.first }
        fun step(ms: Long) { now += ms; queue.fire(); queue.drain() }
    }

    private val earSettings = HostSettings(automationsEnabled = true, fluidifyBackgroundEnabled = true,
        earPause = true, earResume = true)

    // ---- Finding: visibility transition with background consent drops the ear baseline ----

    @Test fun control_singleRemovalAfterBaselinePauses() {
        val r = Rig(earSettings)
        r.core.visible(true)
        r.core.ear(EarState.BOTH); r.queue.drain()          // first RX = baseline (C5)
        r.core.ear(EarState.LEFT); r.queue.drain()          // B -> L: C5 requires PAUSE
        assertEquals(listOf(MediaAction.PAUSE), r.actions())
    }

    @Test fun contract_singleRemovalPausesAfterBackgroundTransitionWithConsent() {
        val r = Rig(earSettings)                             // fluidifyBackgroundEnabled = true
        r.core.visible(true)
        r.core.ear(EarState.BOTH); r.queue.drain()          // baseline while app visible
        r.core.visible(false)                                // user locks phone / switches app
        r.core.ear(EarState.LEFT); r.queue.drain()          // B -> L, authorization unchanged
        assertEquals("C5 B->L must PAUSE when background stays authorized", listOf(MediaAction.PAUSE), r.actions())
    }

    @Test fun contract_reinsertionResumesOwnedPauseAfterVisibilityTransition() {
        val r = Rig(earSettings)
        r.core.visible(true)
        r.core.ear(EarState.BOTH); r.core.ear(EarState.LEFT); r.queue.drain()
        assertEquals(listOf(MediaAction.PAUSE), r.actions())  // owned, corroborated pause
        r.core.visible(false)                                  // screen off while paused
        r.core.ear(EarState.BOTH); r.queue.drain()             // L -> B: C5 R would PLAY
        assertEquals(listOf(MediaAction.PAUSE, MediaAction.PLAY), r.actions())
    }

    @Test fun control_conversationDuckRestoresWithoutVisibilityChange() {
        val r = Rig(HostSettings(automationsEnabled = true, fluidifyBackgroundEnabled = true, conversationDuck = true))
        r.core.visible(true)
        r.core.conversation(true); r.queue.drain()
        repeat(12) { r.step(50) }
        val ducked = r.env.local.volume!!.current
        assertTrue(ducked < 10)
        r.core.conversation(false); r.queue.drain()
        repeat(12) { r.step(50) }
        assertEquals(10, r.env.local.volume!!.current)
    }

    @Test fun contract_ownedConversationDuckRestoresAfterVisibilityTransition() {
        val r = Rig(HostSettings(automationsEnabled = true, fluidifyBackgroundEnabled = true, conversationDuck = true))
        r.core.visible(true)
        r.core.conversation(true); r.queue.drain()
        repeat(12) { r.step(50) }
        val ducked = r.env.local.volume!!.current
        assertTrue(ducked < 10)
        r.core.visible(false)                                  // screen off during conversation
        r.core.conversation(false); r.queue.drain()
        repeat(12) { r.step(50) }
        assertEquals("owned duck must restore its original volume", 10, r.env.local.volume!!.current)
    }

    // ---- Finding: any second media session (NLS granted) defeats local resume ----

    @Test fun control_localResumeWithSingleSession() {
        val r = Rig(earSettings)
        r.core.visible(true)
        r.core.ear(EarState.BOTH); r.core.ear(EarState.NONE); r.queue.drain()
        r.core.ear(EarState.BOTH); r.queue.drain()
        assertEquals(listOf(MediaAction.PAUSE, MediaAction.PLAY), r.actions())
    }

    @Test fun contract_unrelatedPausedSessionDoesNotPreventOwnedLocalResume() {
        val r = Rig(earSettings)                               // otherAppsEnabled = false
        // NLS granted for any reason; another app keeps an active but PAUSED session.
        r.env.extra = MediaCandidate(MediaTarget("com.other.music", "android-session-1", 1, "x", "external-1"),
            Playback.PAUSED, MediaVolume(10, 15), local = false, permitted = false, routedToPair = false,
            callActive = false, foreignDuckActive = false, evidence = MediaEvidence.EXTERNAL_BEST_EFFORT)
        r.core.visible(true)
        r.core.ear(EarState.BOTH); r.core.ear(EarState.NONE); r.queue.drain()
        assertEquals(listOf(MediaAction.PAUSE), r.actions())   // Fluidify paused (only PLAYING session)
        r.core.ear(EarState.BOTH); r.queue.drain()             // N -> B with owned pause
        assertEquals("an unrelated paused session must not erase pause ownership", listOf(MediaAction.PAUSE, MediaAction.PLAY), r.actions())
    }
}
