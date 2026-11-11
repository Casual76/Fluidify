// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.media

import dev.lelonio.square.airpods.runtime.AirPodsMediaGesture
import dev.lelonio.square.airpods.runtime.automation.*
import dev.lelonio.square.airpods.runtime.storage.HostSettings
import java.io.Closeable
import org.junit.Assert.*
import org.junit.Test

/** PREPARED ONLY: actual coordinator/queue, no Android media or radio evidence. */
class AirPodsQualificationMediaFenceTest {
    private class Queue : MediaRuntimeQueue {
        val work = ArrayDeque<() -> Unit>()
        override fun post(block: () -> Unit) { work.addLast(block) }
        override fun later(delayMillis: Long, block: () -> Unit) = Closeable {}
        fun drain() { while(work.isNotEmpty()) work.removeFirst()() }
    }
    private class Environment : MediaRuntimeEnvironment {
        var candidate = MediaCandidate(MediaTarget("local", "session", 1, "pair", "account"),
            Playback.PLAYING, MediaVolume(8, 10), true, true, true, false, false)
        val effects = mutableListOf<MediaAction>()
        override fun read() = listOf(candidate)
        override fun observe(changed: () -> Unit) = Closeable {}
        override fun execute(target: MediaTarget, action: MediaAction, value: Int?, effectId: Long?): Boolean { effects += action; return true }
        override fun gesture(target: MediaTarget, action: AirPodsMediaGesture): Boolean { effects += MediaAction.PLAY; return true }
        override fun readPhoneVolume() = PhoneStreamVolume("pair", MediaVolume(8, 10))
        override fun writePhoneVolume(expected: PhoneStreamVolume, value: Int): Boolean { effects += MediaAction.SET_VOLUME; return true }
    }
    @Test fun expiryOrRevocationAtQueuedEffectBarrierPreventsPausePlayAndVolume() {
        var authority = true
        val env = Environment(); val queue = Queue()
        val core = MediaRuntimeCore(env, queue, AutomationClock { 1 }, authority = { authority })
        core.start(AutomationSession("pair", 7), HostSettings(automationsEnabled = true,
            earPause = true, earResume = true, fluidifyBackgroundEnabled = true))
        core.visible(true)
        core.ear(EarState.BOTH); core.ear(EarState.NONE)
        assertTrue(queue.work.isNotEmpty()) // positive admission, then revocation before real dispatch
        authority = false; queue.drain()
        assertTrue(env.effects.isEmpty())
        assertFalse(core.manualVolume(5)); assertFalse(core.gesture(AirPodsMediaGesture.PLAY))
        core.stop(); assertTrue(env.effects.isEmpty())
    }
    @Test fun validAuthorityStillAllowsEarPauseAndManualVolumeWithoutCleanupReplay() {
        val env = Environment(); val queue = Queue()
        val core = MediaRuntimeCore(env, queue, AutomationClock { 1 }, authority = { true })
        core.start(AutomationSession("pair", 7), HostSettings(automationsEnabled = true,
            earPause = true, earResume = true, fluidifyBackgroundEnabled = true))
        core.visible(true); core.ear(EarState.BOTH); core.ear(EarState.NONE); queue.drain()
        assertEquals(listOf(MediaAction.PAUSE), env.effects)
        assertTrue(core.manualVolume(5))
        core.stop(); queue.drain()
        assertEquals(listOf(MediaAction.PAUSE, MediaAction.SET_VOLUME), env.effects)
    }
}
