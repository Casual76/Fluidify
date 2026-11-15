// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.media

import dev.lelonio.square.airpods.runtime.automation.*
import org.junit.Assert.*
import org.junit.Test

/** C6 remains explicitly weaker than a local causal receipt. No Android/device execution. */
class I02ExternalVolumeHistoryTest {
    private class Rig {
        var now = 100L
        val provider = ExternalBestEffortProvider(AutomationClock { now })
        var raw = MediaCandidate(MediaTarget("other.music", "token", 1, "unverified", "account"),
            Playback.PLAYING, MediaVolume(50, 100), false, true, false, false)
        var facts = ExternalMediaFacts(true, true, true, true, "pair-route", false)
        init { provider.configure(true) }
        fun read() = provider.candidates(listOf(raw), facts).single()
        fun duck(confirm: Boolean = true): MediaTarget {
            val c = read()
            provider.retainAutomation(c.target)
            provider.requested(c, MediaAction.SET_VOLUME, 20, 9)
            raw = raw.copy(volume = MediaVolume(20, 100))
            if (confirm) provider.volumeObserved(c.target, 20)
            return c.target
        }
        fun pause(target: MediaTarget, explicit: Boolean) {
            if (explicit) provider.manualTransport(target)
            raw = raw.copy(playback = Playback.PAUSED)
            facts = facts.copy(exactPairStreaming = false)
            read() // Android validates facts before forwarding the state callback.
            provider.playbackObserved(target, Playback.PAUSED, ++now)
        }
    }

    @Test fun manualPauseKeepsOnlyLiveVolumeCorrelationWithoutGrantingResumeOrLocalReceipt() {
        for (explicit in listOf(false, true)) {
            val r = Rig(); val target = r.duck()
            val before = r.read()
            r.pause(target, explicit)
            r.now += 600_000
            val after = r.read()
            assertEquals(target, after.target)
            assertTrue(after.routedToPair)
            assertEquals(RouteEvidence.EXTERNAL_HISTORICAL_INFERENCE, after.routeEvidence)
            assertEquals(MediaEvidence.EXTERNAL_BEST_EFFORT, after.evidence)
            assertTrue(after.transportRevision > before.transportRevision)
            assertEquals(before.volumeRevision, after.volumeRevision)
            assertEquals(9L, after.correlatedVolumeEffect)
            assertNull(after.correlatedPauseEffect)
            assertNull(after.observedVolumeEffect)
            assertNull(after.observedPauseEffect)
        }
    }

    @Test fun lingeringVolumeCorrelationWithoutPolicyRetentionCannotAuthorizePausedRoute() {
        val r = Rig(); val target = r.duck()
        r.pause(target, explicit = true)
        assertTrue(r.read().routedToPair)
        r.provider.retainAutomation(null)
        val released = r.read()
        assertFalse(released.routedToPair)
        assertNull(released.correlatedVolumeEffect)
    }

    @Test fun pendingVolumeHistoryKeepsItsOriginalDeadlineAndLateObservationCannotReviveIt() {
        val r = Rig(); val target = r.duck(confirm = false)
        r.pause(target, explicit = true)
        assertTrue(r.read().routedToPair)
        assertNull(r.read().correlatedVolumeEffect)
        r.now = 2_100
        assertFalse(r.read().routedToPair)
        r.provider.volumeObserved(target, 20)
        assertFalse(r.read().routedToPair)
        assertNull(r.read().correlatedVolumeEffect)
    }

    @Test fun volumeChangeRouteAccountCallPermissionAndAmbiguityRevokeVolumeHistory() {
        for (change in 0..5) {
            val r = Rig(); val target = r.duck()
            r.pause(target, explicit = true)
            when (change) {
                0 -> {
                    r.raw = r.raw.copy(volume = MediaVolume(27, 100))
                    r.provider.volumeObserved(target, 27)
                }
                1 -> r.facts = r.facts.copy(routeKey = "other-route")
                2 -> r.raw = r.raw.copy(target = r.raw.target.copy(accountEpoch = "new-account"))
                3 -> r.facts = r.facts.copy(callActive = true)
                4 -> r.facts = r.facts.copy(permitted = false)
                5 -> r.facts = r.facts.copy(exactPairConnected = false)
            }
            val after = r.read()
            assertFalse("change=$change", after.routedToPair)
            assertNull(after.correlatedVolumeEffect)
            assertNull(after.correlatedPauseEffect)
        }
        val r = Rig(); val target = r.duck(); r.pause(target, explicit = true)
        val contender = r.raw.copy(target = r.raw.target.copy(sessionGeneration = 2), playback = Playback.UNKNOWN)
        assertTrue(r.provider.candidates(listOf(r.raw, contender), r.facts).none { it.routedToPair })
    }
}
