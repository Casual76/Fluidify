// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.media

import dev.lelonio.square.airpods.runtime.automation.*
import org.junit.Assert.*
import org.junit.Test

class C6ResumeCorrelationTest {
    private class Rig {
        var now = 1_000L
        val provider = ExternalBestEffortProvider(AutomationClock { now })
        var raw = listOf(MediaCandidate(MediaTarget("music", "session", 1, "unverified", "account"),
            Playback.PLAYING, MediaVolume(10, 15), false, true, false, false,
            foreignDuckActive = false, unavailableReason = "SESSION_ACTIVE_ROUTE_UNOBSERVABLE"))
        var facts = ExternalMediaFacts(true, true, true, true, "pair-route", false)
        init { provider.configure(true); read() }
        fun read() = provider.candidates(raw, facts).first()
        fun ownPause(): MediaCandidate {
            val c = read()
            provider.requested(c, MediaAction.PAUSE, null, 7)
            raw = listOf(raw.first().copy(playback = Playback.PAUSED))
            facts = facts.copy(exactPairStreaming = false)
            read() // Android rereads facts before correlating callbacks.
            provider.playbackObserved(c.target, Playback.PAUSED, now)
            return read().also { assertEquals(7L, it.correlatedPauseEffect) }
        }
        fun ownResume(): MediaTarget {
            val c = ownPause()
            provider.requested(c, MediaAction.SET_VOLUME, 4, 8)
            raw = listOf(raw.first().copy(volume = MediaVolume(4, 15)))
            provider.volumeObserved(c.target, 4)
            provider.requested(read(), MediaAction.PLAY, null, 9)
            return c.target
        }
        fun playing(target: MediaTarget, timestamp: Long = now): MediaCandidate {
            raw = listOf(raw.first().copy(playback = Playback.PLAYING))
            facts = facts.copy(exactPairStreaming = true)
            read()
            provider.playbackObserved(target, Playback.PLAYING, timestamp)
            return read()
        }
    }

    @Test fun ownResumePreservesExistingVolumeCorrelationWithoutLocalReceiptOrPauseOwnership() {
        val r = Rig(); val target = r.ownResume(); val before = r.read()
        val after = r.playing(target)
        assertEquals(before.transportRevision, after.transportRevision)
        assertEquals(8L, after.correlatedVolumeEffect)
        assertNull(after.correlatedPauseEffect)
        assertNull(after.observedPauseEffect); assertNull(after.observedVolumeEffect)
        assertEquals(MediaEvidence.EXTERNAL_BEST_EFFORT, after.evidence)
        assertEquals(RouteEvidence.EXTERNAL_STREAMING_INFERENCE, after.routeEvidence)
        // The consumed PLAY cannot hide a subsequent observable pause.
        r.provider.playbackObserved(target, Playback.PAUSED, r.now)
        assertTrue(r.read().transportRevision > after.transportRevision)
    }

    @Test fun spontaneousPlayAfterOwnedPauseStillRevokes() {
        val r = Rig(); val c = r.ownPause()
        assertTrue(r.playing(c.target).transportRevision > c.transportRevision)
    }

    @Test fun expiredOwnResumeCannotHideAnOverride() {
        val r = Rig(); val target = r.ownResume(); val before = r.read()
        r.now += 2_000
        assertTrue(r.playing(target).transportRevision > before.transportRevision)
    }

    // Corrective-7 contract change (prepared, not executed): an UNKNOWN that the platform marks as
    // an interruption (stop/error/skip/seek/none) keeps revoking exactly as before; a transitional
    // UNKNOWN (BUFFERING/CONNECTING) inside the bound of our own correlated PLAY no longer does.
    @Test fun duplicatePauseOrInterruptionWhileResumePendingRevokesIt() {
        for (event in listOf(Playback.PAUSED, Playback.UNKNOWN)) {
            val r = Rig(); val target = r.ownResume(); val before = r.read()
            r.provider.playbackObserved(target, event, r.now, interruption = event == Playback.UNKNOWN)
            assertTrue(r.read().transportRevision > before.transportRevision)
            val revoked = r.read().transportRevision
            assertTrue(r.playing(target).transportRevision > revoked)
        }
    }

    @Test fun transitionalUnknownInsideBoundKeepsOwnResumeUntilPlaying() {
        val r = Rig(); val target = r.ownResume(); val before = r.read()
        r.raw = listOf(r.raw.first().copy(playback = Playback.UNKNOWN))
        val during = r.read() // fresh reread precedes the callback, as on Android
        assertTrue(during.routedToPair)
        assertEquals(before.transportRevision, during.transportRevision)
        r.provider.playbackObserved(target, Playback.UNKNOWN, r.now)
        assertEquals(before.transportRevision, r.read().transportRevision)
        assertEquals(8L, r.read().correlatedVolumeEffect)
        val after = r.playing(target)
        assertEquals(before.transportRevision, after.transportRevision)
        assertEquals(8L, after.correlatedVolumeEffect)
        assertNull(after.correlatedPauseEffect)
        assertNull(after.observedPauseEffect); assertNull(after.observedVolumeEffect)
        assertEquals(RouteEvidence.EXTERNAL_STREAMING_INFERENCE, after.routeEvidence)
    }

    @Test fun transitionalUnknownCannotOutliveTheResumeBound() {
        val r = Rig(); val target = r.ownResume(); val before = r.read()
        r.raw = listOf(r.raw.first().copy(playback = Playback.UNKNOWN))
        r.read(); r.provider.playbackObserved(target, Playback.UNKNOWN, r.now)
        r.now += 2_000
        val expired = r.read()
        assertFalse(expired.routedToPair)
        assertTrue(expired.transportRevision > before.transportRevision)
        assertNull(expired.correlatedVolumeEffect)
        assertTrue(r.playing(target).transportRevision > expired.transportRevision)
    }

    @Test fun pausedAfterTransitionalUnknownStillRevokes() {
        val r = Rig(); val target = r.ownResume(); val before = r.read()
        r.provider.playbackObserved(target, Playback.UNKNOWN, r.now)
        assertEquals(before.transportRevision, r.read().transportRevision)
        r.provider.playbackObserved(target, Playback.PAUSED, r.now)
        val after = r.read()
        assertTrue(after.transportRevision > before.transportRevision)
        assertFalse(after.routedToPair); assertNull(after.correlatedVolumeEffect)
    }

    @Test fun transitionalUnknownWithoutOwnResumeStillRevokesAsBefore() {
        val r = Rig(); val c = r.ownPause()
        r.provider.playbackObserved(c.target, Playback.UNKNOWN, r.now)
        assertTrue(r.read().transportRevision > c.transportRevision)
    }

    @Test fun preRequestOrWrongTargetCallbackCannotConsumeTheExpectedResume() {
        val r = Rig(); val target = r.ownResume(); val before = r.read()
        r.provider.playbackObserved(target.copy(sessionGeneration = 99), Playback.PLAYING, r.now)
        r.provider.playbackObserved(target, Playback.PLAYING, r.now - 1)
        assertEquals(before.transportRevision, r.read().transportRevision)
        assertEquals(before.transportRevision, r.playing(target).transportRevision)
    }

    @Test fun changedRouteAccountPermissionCallConsentOrExplicitInvalidationCannotRetainResume() {
        for (change in 0..5) {
            val r = Rig(); val target = r.ownResume(); val before = r.read()
            when (change) {
                0 -> r.facts = r.facts.copy(routeKey = "replacement-route")
                1 -> r.raw = listOf(r.raw.first().copy(target = r.raw.first().target.copy(accountEpoch = "replacement")))
                2 -> r.facts = r.facts.copy(permitted = false)
                3 -> r.facts = r.facts.copy(callActive = true)
                4 -> r.provider.configure(false)
                5 -> r.provider.invalidate()
            }
            r.read()
            val after = r.playing(target)
            assertTrue(after.transportRevision > before.transportRevision)
            assertNull(after.correlatedVolumeEffect)
            assertNull(after.observedVolumeEffect)
        }
    }

    @Test fun untrackedPlayRequestDoesNotSuppressTheTransportRevision() {
        val r = Rig(); val c = r.ownPause()
        r.provider.requested(c, MediaAction.PLAY, null, null)
        assertTrue(r.playing(c.target).transportRevision > c.transportRevision)
    }
}
