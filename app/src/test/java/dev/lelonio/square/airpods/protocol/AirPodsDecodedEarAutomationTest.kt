// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.protocol

import dev.lelonio.square.airpods.AacpCodec
import dev.lelonio.square.airpods.AacpEvent
import dev.lelonio.square.airpods.runtime.AirPodsMediaGesture
import dev.lelonio.square.airpods.runtime.automation.*
import dev.lelonio.square.airpods.runtime.media.*
import dev.lelonio.square.airpods.runtime.storage.HostSettings
import java.io.Closeable
import org.junit.Assert.*
import org.junit.Test

/** PREPARED, NOT EXECUTED. Independent SDU bytes through real decoder + MediaRuntimeCore.
 * The local target, execution and receipts are synthetic; not an Android route/hardware proof. */
class AirPodsDecodedEarAutomationTest {
    private class Queue : MediaRuntimeQueue {
        private val work = ArrayDeque<() -> Unit>()
        override fun post(block: () -> Unit) { work.addLast(block) }
        override fun later(delayMillis: Long, block: () -> Unit) = Closeable { }
        fun drain() {
            var count = 0
            while (work.isNotEmpty()) { check(++count < 2_000); work.removeFirst()() }
        }
    }
    private class Environment : MediaRuntimeEnvironment {
        var candidate = MediaCandidate(MediaTarget("fluidify", "session", 1, "pair-route", "account"),
            Playback.PLAYING, MediaVolume(10, 15), true, true, true, false, false)
        val actions = mutableListOf<MediaAction>()
        private var changed: (() -> Unit)? = null
        override fun read() = listOf(candidate)
        override fun observe(changed: () -> Unit): Closeable {
            this.changed = changed; return Closeable { this.changed = null }
        }
        override fun execute(target: MediaTarget, action: MediaAction, value: Int?, effectId: Long?): Boolean {
            check(target == candidate.target)
            actions += action
            candidate = candidate.copy(playback = if (action == MediaAction.PAUSE) Playback.PAUSED else Playback.PLAYING,
                observedPauseEffect = effectId.takeIf { action == MediaAction.PAUSE })
            changed?.invoke(); return true
        }
        override fun gesture(target: MediaTarget, action: AirPodsMediaGesture) = true
        fun manualPlay() {
            candidate = candidate.copy(playback = Playback.PLAYING, observedPauseEffect = null,
                transportRevision = candidate.transportRevision + 1)
            changed?.invoke()
        }
    }
    private class Rig : AutoCloseable {
        val environment = Environment(); val queue = Queue()
        val core = MediaRuntimeCore(environment, queue, AutomationClock { 0L })
        init {
            core.start(AutomationSession("pair", 1), HostSettings(automationsEnabled = true,
                fluidifyBackgroundEnabled = true, earPause = true, earResume = true))
            core.visible(true); queue.drain()
        }
        fun sdu(hex: String) {
            val events = AacpCodec.decodeSdu(hex.split(" ").map { it.toInt(16).toByte() }.toByteArray())
            for (event in events) {
                if (event is AacpEvent.Ear)
                    core.ear(dev.lelonio.square.airpods.runtime.automation.EarState.valueOf(event.sample.normalized.name))
                queue.drain()
            }
        }
        override fun close() = core.stop()
    }

    @Test fun storingOneRemovedBudDoesNotHideTheLaterRemovalOfTheManuallyPlayingMonoBud() {
        for (remainingPrimary in listOf(false, true)) Rig().use { r ->
            r.sdu("04 00 04 00 06 00 00 00") // B baseline
            r.sdu(if (remainingPrimary) "04 00 04 00 06 00 00 01" else "04 00 04 00 06 00 01 00")
            assertEquals(listOf(MediaAction.PAUSE), r.environment.actions)
            r.sdu(if (remainingPrimary) "04 00 04 00 06 00 00 02" else "04 00 04 00 06 00 02 00")
            r.environment.manualPlay(); r.queue.drain()
            r.sdu(if (remainingPrimary) "04 00 04 00 06 00 01 02" else "04 00 04 00 06 00 02 01")
            assertEquals(listOf(MediaAction.PAUSE, MediaAction.PAUSE), r.environment.actions)
        }
    }

    @Test fun directRemovalIntoTheCaseStillPausesTheLastWornBud() {
        for (frame in listOf("04 00 04 00 06 00 00 02", "04 00 04 00 06 00 02 00")) Rig().use { r ->
            r.sdu(frame) // mono baseline, other bud already in case
            r.sdu("04 00 04 00 06 00 02 02")
            assertEquals(listOf(MediaAction.PAUSE), r.environment.actions)
        }
    }

    @Test fun unknownOrTruncatedCodesCannotInventTheMissingRemoval() {
        Rig().use { r ->
            r.sdu("04 00 04 00 06 00 02 00")
            r.sdu("04 00 04 00 06 00 02") // truncated, no event
            assertTrue(r.environment.actions.isEmpty())
            r.sdu("04 00 04 00 06 00 03 01") // unknown invalidates baseline
            r.sdu("04 00 04 00 06 00 02 01") // first complete known sample is baseline
            assertTrue(r.environment.actions.isEmpty())
        }
    }
}
