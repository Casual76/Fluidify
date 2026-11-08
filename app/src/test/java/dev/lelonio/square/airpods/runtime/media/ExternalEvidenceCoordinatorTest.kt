// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.media

import dev.lelonio.square.airpods.runtime.automation.*
import org.junit.Assert.*
import org.junit.Test

/** Shared arbiter contract: C6 correlations cannot weaken local receipt attribution. */
class ExternalEvidenceCoordinatorTest {
    private class Fixture(val external: Boolean = true, consent: Boolean = true) {
        var now = 0L
        val effects = mutableListOf<MediaEffect>()
        val host = object : MediaHost {
            override fun submit(effect: MediaEffect): Boolean { effects.add(effect); return true }
            override fun cancel(lease: Long) {}
            override fun cancelEffect(effect: MediaEffect) {}
        }
        val p = AirPodsAutomationCoordinator(host, AutomationClock { now })
        val session = AutomationSession("pair", 1)
        var snapshot = MediaSnapshot(session, 1, MediaTarget("app", "token", 1, "route", "account"),
            Playback.PLAYING, MediaVolume(8, 10), true, true, true,
            evidence = if (external) MediaEvidence.EXTERNAL_BEST_EFFORT else MediaEvidence.LOCAL_CORROBORATED,
            externalBestEffortConsented = consent)
        init {
            p.configure(AutomationConfig("pair", true, true, true, true, false, true,
                rampMillis = 0), 1)
            p.onMedia(snapshot)
        }
        fun observe() { snapshot = snapshot.copy(sequence = snapshot.sequence + 1); p.onMedia(snapshot) }
        fun pause() {
            p.onEar(session, EarState.BOTH); p.onEar(session, EarState.NONE)
        }
    }
    @Test fun c6OffRejectsEvenMatchingExternalCorrelation() {
        val f = Fixture(consent = false); f.pause(); assertTrue(f.effects.isEmpty())
    }
    @Test fun correlationOnlyOwnsExternalWhileLocalRemainsStrict() {
        for (external in listOf(false, true)) {
            val f = Fixture(external); f.pause()
            f.snapshot = f.snapshot.copy(playback = Playback.PAUSED, correlatedPauseEffect = f.effects.single().id)
            f.observe(); assertEquals(external, f.p.status().ownsPause)
            f.p.onEar(f.session, EarState.BOTH)
            assertEquals(if (external) 2 else 1, f.effects.size)
        }
    }
    @Test fun localReceiptDoesNotMasqueradeAsExternalCorrelation() {
        val f = Fixture(); f.pause()
        f.snapshot = f.snapshot.copy(playback = Playback.PAUSED, observedPauseEffect = f.effects.single().id)
        f.observe(); assertFalse(f.p.status().ownsPause)
    }
    @Test fun revocationDuringPendingDiscardsResumeAndCleanupNeverPlays() {
        val f = Fixture(); f.pause()
        f.snapshot = f.snapshot.copy(externalBestEffortConsented = false, playback = Playback.PAUSED,
            correlatedPauseEffect = f.effects.single().id)
        f.observe(); f.p.onEar(f.session, EarState.BOTH); f.p.stop()
        assertEquals(listOf(MediaAction.PAUSE), f.effects.map { it.action })
    }
    @Test fun singleCaBaselineSurvivesDuplicateActivityAndEarOverlap() {
        val f = Fixture()
        f.p.onConversation(f.session, false); f.p.onConversation(f.session, true)
        val write = f.effects.single(); assertEquals(3, write.volume)
        f.snapshot = f.snapshot.copy(volume = MediaVolume(3, 10), correlatedVolumeEffect = write.id)
        f.observe(); assertTrue(f.p.status().volumeOwned)
        f.p.onConversation(f.session, true); f.pause()
        assertEquals(1, f.effects.count { it.action == MediaAction.SET_VOLUME })
        f.p.onConversation(f.session, false)
        assertEquals(8, f.effects.last().volume)
        assertEquals(2, f.effects.count { it.action == MediaAction.SET_VOLUME })
    }
    @Test fun localVolumeRequiresCorroborationAndForeignDuckForfeitsRestore() {
        for (local in listOf(false, true)) {
            val f = Fixture(external = !local)
            f.p.onConversation(f.session, false); f.p.onConversation(f.session, true)
            f.snapshot = f.snapshot.copy(volume = MediaVolume(3, 10), correlatedVolumeEffect = f.effects.single().id)
            f.observe(); assertEquals(!local, f.p.status().volumeOwned)
            f.snapshot = f.snapshot.copy(foreignDuckActive = true); f.observe()
            f.p.onConversation(f.session, false); f.p.stop()
            assertEquals(1, f.effects.size)
        }
    }
    @Test fun observedManualVolumeOrTransportRevokesExternalOwnership() {
        for (volume in listOf(true, false)) {
            val f = Fixture()
            if (volume) {
                f.p.onConversation(f.session, false); f.p.onConversation(f.session, true)
                f.snapshot = f.snapshot.copy(volume = MediaVolume(3, 10), correlatedVolumeEffect = f.effects.single().id)
                f.observe(); f.snapshot = f.snapshot.copy(volumeOverride = true)
            } else {
                f.pause(); f.snapshot = f.snapshot.copy(playback = Playback.PAUSED,
                    correlatedPauseEffect = f.effects.single().id)
                f.observe(); f.snapshot = f.snapshot.copy(transportOverride = true)
            }
            f.observe(); f.p.onConversation(f.session, false); f.p.onEar(f.session, EarState.BOTH)
            assertEquals(1, f.effects.size)
        }
    }
}
