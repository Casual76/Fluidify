// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.media

import dev.lelonio.square.airpods.runtime.automation.*
import org.junit.Assert.*
import org.junit.Test

class ExternalBestEffortProviderTest {
    private class Fixture {
        var now = 0L
        val p = ExternalBestEffortProvider(AutomationClock { now })
        var raw = listOf(MediaCandidate(MediaTarget("other", "token", 1, "unknown", "external-1"),
            Playback.PLAYING, MediaVolume(8, 10), false, true, false, false,
            unavailableReason = "SESSION_ACTIVE_ROUTE_UNOBSERVABLE"))
        var facts = ExternalMediaFacts(true, true, true, true, "route-1", false)
        fun read() = p.candidates(raw, facts).first()
        fun enable() { p.configure(true); read() }
        fun pause(): MediaCandidate {
            val c = read(); p.requested(c, MediaAction.PAUSE, null, 7)
            raw = listOf(raw.first().copy(playback = Playback.PAUSED))
            facts = facts.copy(exactPairStreaming = false)
            p.playbackObserved(c.target, Playback.PAUSED)
            return read()
        }
    }
    @Test fun offAndOnUseDedicatedEvidenceWithoutLocalReceipt() {
        val f = Fixture(); assertFalse(f.read().routedToPair)
        f.enable(); val c = f.read(); assertTrue(c.routedToPair)
        assertEquals(MediaEvidence.EXTERNAL_BEST_EFFORT, c.evidence)
        assertEquals(RouteEvidence.EXTERNAL_STREAMING_INFERENCE, c.routeEvidence)
        assertNull(c.observedPauseEffect)
    }
    @Test fun noBluetoothOtherPairNonStreamingCallPermissionAndSystemRouteReject() {
        val original = ExternalMediaFacts(true, true, true, true, "route-1", false)
        for (facts in listOf(original.copy(permitted = false), original.copy(exactPairConnected = false),
            original.copy(exactPairStreaming = false), original.copy(systemRouteCoherent = false),
            original.copy(callActive = true))) {
            val f = Fixture(); f.enable(); f.facts = facts; assertFalse(f.read().routedToPair)
        }
    }
    @Test fun ambiguousAndRemoteNeverBecomeEligible() {
        val f = Fixture(); f.enable()
        f.raw = f.raw + f.raw.first().copy(target = f.raw.first().target.copy(sessionGeneration = 2))
        assertFalse(f.read().routedToPair)
        f.raw = listOf(f.raw.first().copy(unavailableReason = "REMOTE_PLAYBACK"))
        assertFalse(f.read().routedToPair)
    }
    @Test fun pausedAloneWithoutOwnRequestHasNoHistoricalRoute() {
        val f = Fixture(); f.enable(); f.raw = listOf(f.raw.first().copy(playback = Playback.PAUSED))
        f.facts = f.facts.copy(exactPairStreaming = false); assertFalse(f.read().routedToPair)
    }
    @Test fun ownPauseRetainsHistoricalInferenceOnly() {
        val f = Fixture(); f.enable(); val c = f.pause()
        assertTrue(c.routedToPair)
        assertEquals(RouteEvidence.EXTERNAL_HISTORICAL_INFERENCE, c.routeEvidence)
        assertEquals(7L, c.correlatedPauseEffect); assertNull(c.observedPauseEffect)
    }
    @Test fun routeDeviceTargetPermissionOrConsentChangeInvalidatesPausedInference() {
        for (change in 0..4) {
            val f = Fixture(); f.enable(); f.pause()
            when (change) {
                0 -> f.facts = f.facts.copy(routeKey = "route-2")
                1 -> f.facts = f.facts.copy(exactPairConnected = false)
                2 -> f.raw = listOf(f.raw.first().copy(target = f.raw.first().target.copy(sessionGeneration = 2)))
                3 -> f.facts = f.facts.copy(permitted = false)
                4 -> f.p.configure(false)
            }
            assertFalse(f.read().routedToPair); assertNull(f.read().correlatedPauseEffect)
        }
    }
    @Test fun delayedOldTargetCallbackAndExpiredRequestCannotCorrelate() {
        val f = Fixture(); f.enable(); val old = f.read()
        f.p.requested(old, MediaAction.PAUSE, null, 7)
        f.raw = listOf(f.raw.first().copy(target = f.raw.first().target.copy(sessionGeneration = 2)))
        f.read(); f.p.playbackObserved(old.target, Playback.PAUSED)
        assertNull(f.read().correlatedPauseEffect)
        val next = f.read(); f.p.requested(next, MediaAction.PAUSE, null, 8)
        f.now = 2000; f.p.playbackObserved(next.target, Playback.PAUSED)
        assertNull(f.read().correlatedPauseEffect)
    }
    @Test fun observableManualTransportClearsOwnership() {
        val f = Fixture(); f.enable(); val owned = f.pause()
        f.p.playbackObserved(owned.target, Playback.PAUSED)
        assertNull(f.read().correlatedPauseEffect)
        assertFalse(f.read().routedToPair)
    }
    @Test fun volumeRequiresOwnRequestAndActualObservationThenDivergenceRevokes() {
        val f = Fixture(); f.enable(); val c = f.read()
        f.p.volumeObserved(c.target, 3); assertNull(f.read().correlatedVolumeEffect)
        f.p.requested(c, MediaAction.SET_VOLUME, 3, 9)
        assertNull(f.read().correlatedVolumeEffect)
        f.p.volumeObserved(c.target, 3)
        assertEquals(9L, f.read().correlatedVolumeEffect); assertNull(f.read().observedVolumeEffect)
        val revision = f.read().volumeRevision
        f.p.volumeObserved(c.target, 5)
        assertTrue(f.read().volumeRevision > revision); assertNull(f.read().correlatedVolumeEffect)
    }
    @Test fun foreignDuckAndRevokeDiscardPendingVolumeWithoutRestore() {
        val f = Fixture(); f.enable(); val c = f.read()
        f.p.requested(c, MediaAction.SET_VOLUME, 3, 9)
        f.facts = f.facts.copy(foreignDuckActive = true); f.read()
        f.p.volumeObserved(c.target, 3); assertNull(f.read().correlatedVolumeEffect)
        f.p.configure(false); f.p.volumeObserved(c.target, 3)
        assertFalse(f.read().permitted)
    }
    @Test fun demonstrablyPreRequestCallbackCannotOwnNewPauseOnSameToken() {
        val f = Fixture(); f.enable(); val c = f.read(); f.now = 100
        f.p.requested(c, MediaAction.PAUSE, null, 7)
        f.p.playbackObserved(c.target, Playback.PAUSED, stateUpdatedAt = 50)
        assertNull(f.read().correlatedPauseEffect)
    }
}
