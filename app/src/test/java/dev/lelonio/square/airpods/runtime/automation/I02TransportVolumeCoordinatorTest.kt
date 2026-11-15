// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.automation

import org.junit.Assert.*
import org.junit.Test

/** Intent and receipt ordering regressions. Prepared only; no device or sink qualification. */
class I02TransportVolumeCoordinatorTest {
    private class Rig : MediaHost {
        var now = 100L
        val sent = mutableListOf<MediaEffect>()
        val cancelled = mutableListOf<MediaEffect>()
        val coordinator = AirPodsAutomationCoordinator(this, AutomationClock { now })
        val session = AutomationSession("pair", 1)
        var snapshot = MediaSnapshot(session, 1, MediaTarget("fluidify", "session", 1, "pair-route", "account"),
            Playback.PLAYING, MediaVolume(50, 100), true, true, true)
        init {
            coordinator.configure(AutomationConfig("pair", enabled = true,
                conversationEnabled = true, conversationPause = true, conversationDuck = true), 1)
            coordinator.onMedia(snapshot)
        }
        override fun submit(effect: MediaEffect): Boolean { sent.add(effect); return true }
        override fun cancel(lease: Long) {}
        override fun cancelEffect(effect: MediaEffect) { cancelled.add(effect) }
        fun observe(change: (MediaSnapshot) -> MediaSnapshot = { it }) {
            snapshot = change(snapshot).copy(sequence = snapshot.sequence + 1)
            coordinator.onMedia(snapshot)
        }
        fun voice(active: Boolean) = coordinator.onConversation(session, active)
        fun step(millis: Long) { now += millis; coordinator.tick() }
        fun volumeReceipt(effect: MediaEffect = sent.last { it.action == MediaAction.SET_VOLUME }) {
            observe { it.copy(volume = MediaVolume(effect.volume!!, 100), observedVolumeEffect = effect.id) }
        }
        fun volumes() = sent.filter { it.action == MediaAction.SET_VOLUME }.map { it.volume }
    }

    @Test fun manualTransportCancelsExactQueuedPauseAndKeepsPendingVolumeLeaseAndDeadline() {
        val r = Rig()
        r.voice(true); r.step(150)
        val pause = r.sent.first()
        val write = r.sent.last()
        val before = r.coordinator.status()
        assertEquals(MediaAction.PAUSE, pause.action)
        assertEquals(35, write.volume)
        r.coordinator.onManualTransportIntent()
        assertEquals(listOf(pause), r.cancelled)
        assertEquals(before.lease, r.coordinator.status().lease)
        assertEquals(before.inputEpoch, r.coordinator.status().inputEpoch)
        assertTrue(r.coordinator.status().rampActive)
        assertFalse(r.coordinator.status().pausePending)
        r.voice(false); r.step(100)
        assertEquals(listOf(35), r.volumes())
        r.volumeReceipt(write); r.step(300)
        assertEquals(listOf(35, 50), r.volumes())
        r.volumeReceipt()
        assertFalse(r.sent.any { it.action == MediaAction.PLAY })
        assertFalse(r.coordinator.status().volumeOwned)
        assertFalse(r.coordinator.status().rampActive)
    }

    @Test fun latePauseReceiptAfterTransportOverrideCannotReacquireResumeOrLoseVolume() {
        val r = Rig()
        r.voice(true); r.step(300); r.volumeReceipt()
        val pause = r.sent.first()
        r.observe { it.copy(playback = Playback.PAUSED, transportOverride = true) }
        assertEquals(listOf(pause), r.cancelled)
        r.observe { it.copy(transportOverride = false, observedPauseEffect = pause.id) }
        assertFalse(r.coordinator.status().ownsPause)
        assertTrue(r.coordinator.status().volumeOwned)
        r.voice(false); r.step(300); r.volumeReceipt()
        assertEquals(listOf(20, 50), r.volumes())
        assertFalse(r.sent.any { it.action == MediaAction.PLAY })
    }

    @Test fun manualTransportCannotTurnForeignOrStaleReceiptIntoAuthority() {
        for (receipt in listOf(-44L, -99L)) {
            val r = Rig()
            r.observe { it.copy(observedVolumeEffect = -44) }
            r.voice(true); r.step(150)
            val write = r.sent.last()
            r.coordinator.onManualTransportIntent()
            r.observe { it.copy(volume = MediaVolume(35, 100), observedVolumeEffect = receipt) }
            r.voice(false); r.step(100)
            assertEquals(listOf(35), r.volumes())
            if (receipt == -99L) {
                r.volumeReceipt(write); r.step(300)
                assertEquals("a late own ACK cannot replace foreign evidence", listOf(35), r.volumes())
                assertFalse(r.coordinator.status().rampActive)
            } else {
                assertFalse("a retained baseline receipt grants nothing", r.coordinator.status().volumeOwned)
                r.volumeReceipt(write); r.step(300); r.volumeReceipt()
                assertEquals(listOf(35, 50), r.volumes())
            }
            assertFalse(r.sent.any { it.action == MediaAction.PLAY })
        }
    }

    @Test fun manualTransportLoadingKeepsOnlySameTargetVolumeAndExplicitVolumeStillWins() {
        for (volumeIntent in listOf(false, true)) {
            val r = Rig()
            r.voice(true); r.step(300); r.volumeReceipt()
            r.observe { it.copy(playback = Playback.UNKNOWN, transportOverride = true) }
            r.now += 600_000
            r.observe { it.copy(transportOverride = false, volumeOverride = volumeIntent) }
            r.voice(false); r.step(300)
            assertEquals(if (volumeIntent) listOf(20) else listOf(20, 50), r.volumes())
            if (!volumeIntent) r.volumeReceipt()
            assertFalse(r.sent.any { it.action == MediaAction.PLAY })
            assertFalse(r.coordinator.status().volumeOwned)
            assertFalse(r.coordinator.status().rampActive)
        }
    }
}
