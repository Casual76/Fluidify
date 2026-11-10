// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.automation

import org.junit.Assert.*
import org.junit.Test

class AirPodsAutomationCoordinatorTest {
    private class Rig(config: AutomationConfig = enabled()) : MediaHost {
        var now = 0L
        var accept = true
        val sent = mutableListOf<MediaEffect>()
        val cancelled = mutableListOf<Long>()
        val cancelledEffects = mutableListOf<MediaEffect>()
        val coordinator = AirPodsAutomationCoordinator(this, AutomationClock { now })
        val session = AutomationSession("pair", 1)
        var snapshot = MediaSnapshot(session, 1,
            MediaTarget("other.app", "session", 1, "airpods", "account-1"),
            Playback.PLAYING, MediaVolume(50, 100), true, true, true)
        init {
            coordinator.configure(config, 1)
            coordinator.onMedia(snapshot)
            coordinator.onConversation(session, false)
        }
        override fun submit(effect: MediaEffect): Boolean { sent += effect; return accept }
        override fun cancel(lease: Long) { cancelled += lease }
        override fun cancelEffect(effect: MediaEffect) { cancelledEffects += effect }
        fun ear(value: EarState, live: Boolean = true) = coordinator.onEar(session, value, live)
        fun ca(active: Boolean) = coordinator.onConversation(session, active)
        fun observe(change: (MediaSnapshot) -> MediaSnapshot = { it }) {
            snapshot = change(snapshot).copy(sequence = snapshot.sequence + 1)
            coordinator.onMedia(snapshot)
        }
        fun confirmPause() {
            val e = sent.last { it.action == MediaAction.PAUSE }
            observe { it.copy(playback = Playback.PAUSED, observedPauseEffect = e.id) }
        }
        fun confirmVolume() {
            val e = sent.last { it.action == MediaAction.SET_VOLUME }
            observe { it.copy(volume = MediaVolume(e.volume!!, 100), observedVolumeEffect = e.id) }
        }
        fun count(action: MediaAction) = sent.count { it.action == action }
        fun status() = coordinator.status()
        fun advance(ms: Long) { now += ms; coordinator.tick() }
    }

    companion object {
        private fun enabled() = AutomationConfig("pair", enabled = true, earEnabled = true,
            conversationEnabled = true, conversationPause = true, conversationDuck = false)
        private fun duck(mode: VolumeMode = VolumeMode.RELATIVE, percent: Int = 40) =
            enabled().copy(conversationPause = false, conversationDuck = true,
                volumeMode = mode, volumePercent = percent)
    }

    @Test fun bothLeftBothResumesOnlyObservedOwnedPause() {
        val r = Rig(); r.ear(EarState.BOTH); r.ear(EarState.LEFT)
        assertTrue(r.status().pausePending); assertFalse(r.status().ownsPause)
        r.confirmPause(); assertTrue(r.status().ownsPause)
        r.ear(EarState.BOTH)
        assertEquals(listOf(MediaAction.PAUSE, MediaAction.PLAY), r.sent.map { it.action })
    }
    @Test fun bothRightNoneLeftReleasesForMonoWithOwnedPause() {
        val r = Rig(); r.ear(EarState.BOTH); r.ear(EarState.RIGHT); r.confirmPause()
        r.ear(EarState.NONE); r.ear(EarState.LEFT)
        assertEquals(1, r.count(MediaAction.PAUSE)); assertEquals(1, r.count(MediaAction.PLAY))
        assertTrue(r.status().reasons.isEmpty())
    }
    @Test fun bothNoneBothOwnsAndReleases() {
        val r = Rig(); r.ear(EarState.BOTH); r.ear(EarState.NONE); r.confirmPause(); r.ear(EarState.BOTH)
        assertEquals(1, r.count(MediaAction.PLAY))
    }
    @Test fun initialNoneLeftAndInitialNoneBothDoNotAutoplay() {
        for (end in listOf(EarState.LEFT, EarState.RIGHT, EarState.BOTH)) {
            val r = Rig(); r.ear(EarState.NONE); r.ear(end)
            assertTrue(r.sent.isEmpty()); assertFalse(r.status().ownsPause)
        }
    }
    @Test fun monoManualLeftRightBothDoesNotPauseOrPlay() {
        val r = Rig(); r.ear(EarState.LEFT); r.ear(EarState.RIGHT); r.ear(EarState.LEFT); r.ear(EarState.BOTH)
        assertTrue(r.sent.isEmpty())
    }
    @Test fun leftRightAndRightLeftPreserveEarReasonWithoutEffects() {
        val r = Rig(); r.ear(EarState.BOTH); r.ear(EarState.LEFT); r.confirmPause()
        r.ear(EarState.RIGHT); r.ear(EarState.LEFT)
        assertEquals(setOf(PauseReason.EAR), r.status().reasons)
        assertEquals(1, r.sent.size)
    }
    @Test fun monoToNonePausesManuallyPlayingMono() {
        for (mono in listOf(EarState.LEFT, EarState.RIGHT)) {
            val r = Rig(); r.ear(mono); r.ear(EarState.NONE)
            assertEquals(1, r.count(MediaAction.PAUSE))
        }
    }
    @Test fun everyBaselineAndDuplicateIsSilent() {
        for (state in EarState.values()) {
            val r = Rig(); r.ear(state); r.ear(state)
            assertTrue(r.sent.isEmpty()); assertFalse(r.status().ownsPause)
        }
    }
    @Test fun unknownInvalidatesAndNextValidIsBaseline() {
        for (state in EarState.values().filter { it != EarState.UNKNOWN }) {
            val r = Rig(); r.ear(EarState.BOTH); r.ear(EarState.LEFT); r.confirmPause()
            r.ear(EarState.UNKNOWN); r.ear(state)
            assertFalse(r.status().ownsPause); assertEquals(0, r.count(MediaAction.PLAY))
        }
    }
    @Test fun earThenConversationReinsertionCannotReleaseConversation() {
        val r = Rig(); r.ear(EarState.BOTH); r.ear(EarState.LEFT); r.confirmPause(); r.ca(true)
        r.ear(EarState.BOTH)
        assertEquals(setOf(PauseReason.CONVERSATION), r.status().reasons)
        assertEquals(0, r.count(MediaAction.PLAY)); r.ca(false)
        assertEquals(1, r.count(MediaAction.PAUSE)); assertEquals(1, r.count(MediaAction.PLAY))
    }
    @Test fun conversationThenEarConversationEndCannotReleaseEar() {
        val r = Rig(); r.ear(EarState.BOTH); r.ca(true); r.confirmPause(); r.ear(EarState.RIGHT)
        r.ca(false); assertEquals(setOf(PauseReason.EAR), r.status().reasons)
        assertEquals(0, r.count(MediaAction.PLAY)); r.ear(EarState.BOTH)
        assertEquals(1, r.count(MediaAction.PAUSE)); assertEquals(1, r.count(MediaAction.PLAY))
    }
    @Test fun pendingOverlapAlsoSendsOnlyOnePause() {
        val r = Rig(); r.ear(EarState.BOTH); r.ca(true); r.ear(EarState.LEFT)
        r.confirmPause(); r.ca(false); r.ear(EarState.BOTH)
        assertEquals(1, r.count(MediaAction.PAUSE)); assertEquals(1, r.count(MediaAction.PLAY))
    }
    @Test fun acceptedSendAloneNeverOwnsPause() {
        val r = Rig(); r.ear(EarState.BOTH); r.ear(EarState.NONE); r.ear(EarState.BOTH)
        r.confirmPause(); assertFalse(r.status().ownsPause)
        assertEquals(1, r.cancelledEffects.size)
        assertEquals(0, r.count(MediaAction.PLAY))
    }
    @Test fun unattributedPausedCannotGrantOwnership() {
        val r = Rig(); r.ear(EarState.BOTH); r.ear(EarState.LEFT)
        r.observe { it.copy(playback = Playback.PAUSED) }; r.ear(EarState.BOTH)
        assertEquals(0, r.count(MediaAction.PLAY))
    }
    @Test fun applicationPausedBeforeCorroborationWaitsThenOwnsMatchingReceipt() {
        val r = Rig(); r.ear(EarState.BOTH); r.ear(EarState.LEFT)
        r.observe { it.copy(playback = Playback.PAUSED) }
        assertTrue(r.status().pausePending); assertFalse(r.status().ownsPause)
        assertEquals(0, r.count(MediaAction.PLAY))
        r.confirmPause(); assertTrue(r.status().ownsPause); assertFalse(r.status().pausePending)
        r.ear(EarState.BOTH)
        assertEquals(1, r.count(MediaAction.PLAY))
    }
    @Test fun lateCorroborationAfterPendingTimeoutCannotRestoreOwnership() {
        val r = Rig(); r.ear(EarState.BOTH); r.ear(EarState.NONE)
        r.observe { it.copy(playback = Playback.PAUSED) }
        r.advance(10_000); r.confirmPause(); r.ear(EarState.BOTH)
        assertFalse(r.status().ownsPause); assertEquals(0, r.count(MediaAction.PLAY))
    }
    @Test fun manualOrDifferentReceiptWhileAwaitingCannotBeReplacedByLateOwnReceipt() {
        for (manual in listOf(true, false)) {
            val r = Rig(); r.ear(EarState.BOTH); r.ear(EarState.NONE)
            r.observe { it.copy(playback = Playback.PAUSED) }
            r.observe { it.copy(transportOverride = manual,
                observedPauseEffect = if (manual) null else r.sent.last().id + 1) }
            r.observe { it.copy(transportOverride = false, observedPauseEffect = r.sent.last().id) }
            r.ear(EarState.BOTH)
            assertFalse(r.status().ownsPause); assertEquals(0, r.count(MediaAction.PLAY))
        }
    }
    @Test fun manualPauseEvenAlreadyPausedRevokesResume() {
        val r = Rig(); r.ear(EarState.BOTH); r.ear(EarState.NONE); r.confirmPause()
        r.observe { it.copy(transportOverride = true) }; r.ear(EarState.BOTH)
        assertEquals(0, r.count(MediaAction.PLAY)); assertFalse(r.status().ownsPause)
    }
    @Test fun manualPlayReleasesEarDuplicatesDoNotRepauseNewNoneCanPause() {
        val r = Rig(); r.ear(EarState.BOTH); r.ear(EarState.LEFT); r.confirmPause()
        r.observe { it.copy(playback = Playback.PLAYING, transportOverride = true) }
        assertFalse(PauseReason.EAR in r.status().reasons)
        r.ear(EarState.LEFT); assertEquals(1, r.count(MediaAction.PAUSE))
        r.observe { it.copy(transportOverride = false) }; r.ear(EarState.NONE)
        assertEquals(2, r.count(MediaAction.PAUSE))
    }
    @Test fun resumeOptionOffStillReleasesReason() {
        val r = Rig(enabled().copy(earResumeEnabled = false))
        r.ear(EarState.BOTH); r.ear(EarState.LEFT); r.confirmPause(); r.ear(EarState.BOTH)
        assertTrue(r.status().reasons.isEmpty()); assertEquals(0, r.count(MediaAction.PLAY))
    }
    @Test fun preexistingPausedAndUnknownPlaybackCannotBeOwned() {
        for (p in listOf(Playback.PAUSED, Playback.UNKNOWN)) {
            val r = Rig(); r.observe { it.copy(playback = p) }
            r.ear(EarState.BOTH); r.ear(EarState.NONE); r.ear(EarState.BOTH)
            assertTrue(r.sent.isEmpty())
        }
    }
    @Test fun sessionRouteAccountApplicationAndCallChangesRevokeAllEffects() {
        val changes: List<(MediaSnapshot) -> MediaSnapshot> = listOf(
            { it.copy(target = it.target!!.copy(sessionId = "new")) },
            { it.copy(target = it.target!!.copy(sessionGeneration = 2)) },
            { it.copy(target = it.target!!.copy(routeId = "speaker")) },
            { it.copy(target = it.target!!.copy(accountEpoch = "new")) },
            { it.copy(target = it.target!!.copy(applicationId = "new.app")) },
            { it.copy(callActive = true) },
        )
        for (change in changes) {
            val r = Rig(); r.ear(EarState.BOTH); r.ear(EarState.NONE); r.confirmPause()
            val lease = r.status().lease; r.observe(change); r.ear(EarState.BOTH)
            assertFalse(r.status().ownsPause); assertTrue(lease in r.cancelled)
            assertEquals(0, r.count(MediaAction.PLAY))
        }
    }
    @Test fun missingPermissionAmbiguousTargetAndWrongRouteProduceNoCommands() {
        val changes: List<(MediaSnapshot) -> MediaSnapshot> = listOf(
            { it.copy(permitted = false) }, { it.copy(unambiguous = false) },
            { it.copy(target = null) }, { it.copy(routedToPair = false) },
        )
        for (change in changes) {
            val r = Rig(); r.observe(change); r.ear(EarState.BOTH); r.ear(EarState.NONE); r.ca(true)
            assertTrue(r.sent.isEmpty())
        }
    }
    @Test fun timeoutAndLatePauseCallbackCannotGrantOwnership() {
        val r = Rig(); r.ear(EarState.BOTH); r.ear(EarState.LEFT)
        r.advance(2_000); r.confirmPause(); r.ear(EarState.BOTH)
        assertFalse(r.status().ownsPause); assertEquals(0, r.count(MediaAction.PLAY))
    }
    @Test fun duplicateAndOldGenerationCallbacksAreIgnored() {
        val r = Rig(); r.ear(EarState.BOTH); r.ear(EarState.LEFT)
        r.coordinator.onMedia(r.snapshot.copy(playback = Playback.PAUSED,
            observedPauseEffect = r.sent.last().id))
        r.coordinator.onEar(r.session.copy(generation = 0), EarState.BOTH)
        assertTrue(r.status().pausePending); assertFalse(r.status().ownsPause)
    }
    @Test fun toggleOffReenableAndGenerationResetNeedNewBaselines() {
        val r = Rig(); r.ear(EarState.BOTH); r.ear(EarState.LEFT); r.confirmPause()
        r.coordinator.configure(enabled().copy(earEnabled = false), 1); r.ear(EarState.BOTH)
        r.coordinator.configure(enabled(), 2)
        r.coordinator.onEar(r.session, EarState.NONE)
        r.coordinator.onEar(r.session.copy(generation = 2), EarState.BOTH)
        assertEquals(0, r.count(MediaAction.PLAY)); assertFalse(r.status().ownsPause)
    }
    @Test fun defaultsOffCachedStateAndHardwarePolicyIndependence() {
        val r = Rig(AutomationConfig("pair")); r.ear(EarState.BOTH); r.ear(EarState.NONE); r.ca(true)
        assertTrue(r.sent.isEmpty())
        r.coordinator.configure(enabled(), 1); r.coordinator.onMedia(r.snapshot.copy(sequence = 2))
        r.ear(EarState.NONE, live = false); r.ear(EarState.NONE)
        r.coordinator.onConversation(r.session, true, live = false)
        assertTrue(r.sent.isEmpty())
        r.ca(true) // fresh device event, distinct from cached state
        assertEquals(1, r.count(MediaAction.PAUSE))
    }
    @Test fun relativeAndAbsoluteTargetsUseSingleInitialBaseline() {
        for ((mode, expected) in listOf(VolumeMode.RELATIVE to 20, VolumeMode.ABSOLUTE to 40)) {
            val r = Rig(duck(mode)); r.ca(true); r.advance(300)
            assertEquals(expected, r.sent.last().volume); r.confirmVolume()
            r.ca(true); r.advance(1_000); assertEquals(1, r.count(MediaAction.SET_VOLUME))
            r.ca(false); r.advance(300)
            assertEquals(50, r.sent.last().volume); r.confirmVolume()
            assertFalse(r.status().volumeOwned); assertFalse(r.status().rampActive)
        }
    }
    @Test fun percentageClampsAndAbsoluteNeverRaisesInitialVolume() {
        for ((percent, expected) in listOf(-1 to 5, 1_000 to 43)) {
            val r = Rig(duck(percent = percent)); r.ca(true); r.advance(300)
            assertEquals(expected, r.sent.last().volume)
        }
        val r = Rig(duck(VolumeMode.ABSOLUTE, 85)); r.ca(true); r.advance(300)
        assertTrue(r.sent.isEmpty())
    }
    @Test fun rampWaitsForObservationAndDoesNotDoubleDuck() {
        val r = Rig(duck()); r.ca(true); r.advance(150)
        assertEquals(35, r.sent.last().volume)
        assertFalse(r.status().volumeOwned)
        r.advance(100); assertEquals(1, r.sent.size)
        r.confirmVolume(); assertTrue(r.status().volumeOwned)
        r.advance(50); assertEquals(20, r.sent.last().volume)
        r.confirmVolume(); r.ca(true); r.advance(500); assertEquals(2, r.sent.size)
    }
    @Test fun manualVolumeDuringDuckAndRestoreCancelsRampAndRestore() {
        for (restore in listOf(false, true)) {
            val r = Rig(duck()); r.ca(true); r.advance(300); r.confirmVolume()
            if (restore) { r.ca(false); r.advance(100) }
            val count = r.sent.size; val lease = r.status().lease
            r.observe { it.copy(volume = MediaVolume(33, 100), volumeOverride = true) }
            r.advance(5_000); r.ca(false)
            assertEquals(count, r.sent.size); assertFalse(r.status().volumeOwned)
            assertTrue(lease in r.cancelled)
        }
    }
    @Test fun unattributedVolumeAndEndingBeforeWriteObservationForfeitRestore() {
        val r = Rig(duck()); r.ca(true); r.advance(300)
        r.observe { it.copy(volume = MediaVolume(20, 100)) }; r.ca(false); r.advance(300)
        assertEquals(1, r.sent.size); assertFalse(r.status().volumeOwned)
        val q = Rig(duck()); q.ca(true); q.advance(150); q.ca(false); q.confirmVolume(); q.advance(1_000)
        assertEquals(1, q.sent.size); assertFalse(q.status().volumeOwned)
    }
    @Test fun foreignDuckAndMissingVolumeDoNotAttenuate() {
        for (foreign in listOf(false, true)) {
            val r = Rig(duck()); r.observe {
                if (foreign) it.copy(foreignDuckActive = true) else it.copy(volume = null)
            }; r.ca(true); r.advance(300); assertTrue(r.sent.isEmpty())
        }
    }
    @Test fun earAndDuckRemainIndependentWithoutSecondBaseline() {
        val r = Rig(duck()); r.ear(EarState.BOTH); r.ca(true); r.advance(300); r.confirmVolume()
        r.ear(EarState.LEFT); r.confirmPause(); r.ear(EarState.BOTH)
        assertEquals(1, r.count(MediaAction.PLAY)); assertTrue(r.status().volumeOwned)
        r.ca(false); r.advance(300); assertEquals(50, r.sent.last().volume)
    }
    @Test fun stopDisconnectAndToggleCancelResourcesWithoutCleanupPlay() {
        for (disable in listOf(false, true)) {
            val r = Rig(duck().copy(conversationPause = true))
            r.ca(true); r.confirmPause(); r.advance(150)
            val before = r.sent.size; val lease = r.status().lease
            if (disable) r.coordinator.configure(AutomationConfig("pair"), 1) else r.coordinator.stop()
            r.coordinator.stop(); val cancels = r.cancelled.size; r.coordinator.stop()
            r.advance(10_000); r.ca(false); r.confirmVolume()
            assertEquals(before, r.sent.size); assertTrue(lease in r.cancelled)
            assertEquals(cancels, r.cancelled.size); assertFalse(r.status().rampActive)
            assertEquals(0, r.count(MediaAction.PLAY))
        }
    }
    @Test fun rejectedPauseAndVolumeAreNeverOwnedOrRetried() {
        val r = Rig(duck().copy(conversationPause = true)); r.accept = false
        r.ca(true); r.advance(300); r.ca(true); r.advance(5_000); r.ca(false)
        assertFalse(r.status().ownsPause); assertFalse(r.status().volumeOwned)
        assertEquals(1, r.count(MediaAction.PAUSE)); assertEquals(1, r.count(MediaAction.SET_VOLUME))
    }

    @Test fun newConversationDuringRestoreReusesBaselineAndCancelsUnobservedRestore() {
        val r = Rig(duck()); r.ca(true); r.advance(300); r.confirmVolume()
        r.ca(false); r.advance(150); r.confirmVolume()
        assertEquals(35, r.sent.last().volume)
        r.ca(true); r.advance(300); assertEquals(20, r.sent.last().volume); r.confirmVolume()
        r.ca(false); r.advance(150) // unobserved restore write
        val count = r.sent.size; val lease = r.status().lease
        r.ca(true); r.advance(500)
        assertEquals(count, r.sent.size); assertTrue(lease in r.cancelled)
        assertFalse(r.status().volumeOwned)
    }

    @Test fun earUnknownDoesNotCancelIndependentConversationDuck() {
        val r = Rig(duck()); r.ca(true); r.advance(150); r.confirmVolume()
        r.ear(EarState.BOTH); r.ear(EarState.UNKNOWN); r.advance(150)
        assertEquals(20, r.sent.last().volume); assertTrue(r.status().volumeOwned)
    }

    @Test fun pairSwitchAndEqualConfigurationAreExplicitlyIdempotent() {
        val r = Rig(); r.ear(EarState.BOTH); r.ear(EarState.NONE); r.confirmPause()
        val lease = r.status().lease
        r.coordinator.configure(enabled(), 1)
        assertEquals(lease, r.status().lease); assertTrue(r.status().ownsPause)
        r.coordinator.configure(enabled().copy(pairId = "second"), 1)
        r.ear(EarState.BOTH); assertFalse(r.status().ownsPause)
        assertEquals(0, r.count(MediaAction.PLAY))
    }

    @Test fun volumeTimeoutLateCallbackAndTargetChangeDuringRampDoNotRestore() {
        for (changeTarget in listOf(false, true)) {
            val r = Rig(duck()); r.ca(true); r.advance(150)
            if (changeTarget) r.observe { it.copy(target = it.target!!.copy(sessionId = "new")) }
            else r.advance(2_000)
            r.confirmVolume(); r.ca(false); r.advance(1_000)
            assertEquals(1, r.sent.size); assertFalse(r.status().volumeOwned)
        }
    }

    @Test fun lateInputCallbacksAfterReconfigureSameConnectionAreRejected() {
        val r = Rig(); val oldEpoch = r.status().inputEpoch
        r.coordinator.configure(enabled().copy(volumePercent = 45), 1)
        r.coordinator.onMedia(r.snapshot.copy(sequence = 5), expectedInputEpoch = oldEpoch)
        r.coordinator.onEar(r.session, EarState.BOTH, expectedInputEpoch = oldEpoch)
        r.coordinator.onEar(r.session, EarState.NONE, expectedInputEpoch = oldEpoch)
        r.coordinator.onConversation(r.session, true, expectedInputEpoch = oldEpoch)
        r.coordinator.tick(expectedInputEpoch = oldEpoch)
        assertTrue(r.sent.isEmpty()); assertNull(r.status().ear)
        assertTrue(r.status().reasons.isEmpty())
    }
    @Test fun earCacheUnknownAndBaselinePreserveOnlyConversationOrigin() {
        for (caFirst in listOf(false, true)) for (owned in listOf(false, true))
            for (cached in listOf(false, true)) {
                val r = Rig(); r.ear(EarState.BOTH)
                if (caFirst) { r.ca(true); r.ear(EarState.NONE) }
                else { r.ear(EarState.NONE); r.ca(true) }
                if (owned) r.confirmPause()
                r.ear(if (cached) EarState.NONE else EarState.UNKNOWN, live = !cached)
                r.ear(EarState.BOTH) // fresh baseline, never a release
                assertEquals(caFirst && !owned, r.status().pausePending)
                assertEquals(caFirst && owned, r.status().ownsPause)
                if (!owned) r.confirmPause()
                r.ca(false)
                assertEquals(if (caFirst) 1 else 0, r.count(MediaAction.PLAY))
            }
    }

    @Test fun firstEarBaselinePreservesConversationPauseAndCacheRequiresFreshBaseline() {
        val r = Rig(); r.ca(true); r.confirmPause()
        r.ear(EarState.BOTH); assertTrue(r.status().ownsPause)
        r.ear(EarState.NONE, live = false)
        r.ear(EarState.BOTH); r.ca(false)
        assertEquals(1, r.count(MediaAction.PLAY))
        val q = Rig(); q.ear(EarState.BOTH, live = false); q.ear(EarState.NONE)
        assertTrue(q.sent.isEmpty())
    }

    @Test fun earInvalidationCancelsQueuedEarResumeButPreservesConversationResume() {
        for (caOrigin in listOf(false, true)) {
            val r = Rig(); r.ear(EarState.BOTH)
            if (caOrigin) r.ca(true) else r.ear(EarState.NONE)
            r.confirmPause()
            if (caOrigin) r.ca(false) else r.ear(EarState.BOTH)
            val play = r.sent.last()
            assertEquals(MediaAction.PLAY, play.action)
            r.ear(EarState.UNKNOWN)
            assertEquals(!caOrigin, play in r.cancelledEffects)
        }
    }

    // ---- Corrective-7 (prepared, not executed in the source-only turn) ----
    // CA resume belongs to the conversation option; the ear option governs ear reinsertion only.

    @Test fun conversationPauseResumesItsOwnPauseWithEarResumeOff() {
        for (earEnabled in listOf(false, true)) {
            val r = Rig(enabled().copy(earEnabled = earEnabled, earResumeEnabled = false))
            r.ca(true); r.confirmPause(); r.ca(false)
            assertEquals("earEnabled=$earEnabled", listOf(MediaAction.PAUSE, MediaAction.PLAY),
                r.sent.map { it.action })
            assertFalse(r.status().ownsPause); assertTrue(r.status().reasons.isEmpty())
        }
    }

    @Test fun earResumeOffOverlapFollowsPauseOriginAndLastReleasedBlocker() {
        // (caOrigin, earReleasedLast) -> PLAY count. Origin and last blocker must both resume.
        val cases = listOf(Triple(true, false, 1), Triple(true, true, 0),
            Triple(false, false, 0), Triple(false, true, 0))
        for ((caOrigin, earLast, plays) in cases) {
            val r = Rig(enabled().copy(earResumeEnabled = false)); r.ear(EarState.BOTH)
            if (caOrigin) { r.ca(true); r.confirmPause(); r.ear(EarState.LEFT) }
            else { r.ear(EarState.LEFT); r.confirmPause(); r.ca(true) }
            if (earLast) { r.ca(false); r.ear(EarState.BOTH) } else { r.ear(EarState.BOTH); r.ca(false) }
            assertEquals("caOrigin=$caOrigin earLast=$earLast", plays, r.count(MediaAction.PLAY))
            assertEquals(1, r.count(MediaAction.PAUSE))
            assertTrue(r.status().reasons.isEmpty()); assertFalse(r.status().ownsPause)
        }
    }

    // Own PLAY may report a transitional state (BUFFERING -> UNKNOWN) before PLAYING.

    private fun Rig.ownedPauseAndDuck(rampMillis: Long = 300) {
        ca(true); confirmPause(); advance(rampMillis); confirmVolume()
        assertEquals(20, sent.last().volume); assertTrue(status().ownsPause)
    }

    @Test fun transitionalUnknownAfterDispatchedOwnPlayKeepsRestoringOwnedDuck() {
        val r = Rig(duck().copy(conversationPause = true)); r.ownedPauseAndDuck()
        r.ca(false)
        val play = r.sent.last(); assertEquals(MediaAction.PLAY, play.action)
        r.coordinator.onDispatched(play, accepted = true)
        r.observe { it.copy(playback = Playback.UNKNOWN) }
        r.advance(150); assertEquals(35, r.sent.last().volume); r.confirmVolume()
        r.observe { it.copy(playback = Playback.PLAYING) }
        r.advance(150); assertEquals(50, r.sent.last().volume); r.confirmVolume()
        assertFalse(r.status().volumeOwned); assertFalse(r.status().rampActive)
    }

    @Test fun unknownBeforeDispatchAfterRejectionOrAfterWindowStillForfeits() {
        for (variant in 0..2) {
            val r = Rig(duck().copy(conversationPause = true)); r.ownedPauseAndDuck()
            r.ca(false)
            val play = r.sent.last()
            when (variant) {
                0 -> Unit // queued only: UNKNOWN before our PLAY is not our transition
                1 -> r.coordinator.onDispatched(play, accepted = false)
                2 -> { r.coordinator.onDispatched(play, accepted = true); r.now += 2_000 }
            }
            val lease = r.status().lease; val count = r.sent.size
            r.observe { it.copy(playback = Playback.UNKNOWN) }
            r.advance(1_000)
            assertTrue("variant=$variant", lease in r.cancelled)
            assertEquals("variant=$variant", count, r.sent.size)
            assertFalse(r.status().volumeOwned)
        }
    }

    @Test fun overrideOrManualIntentInsideWindowStillRevokes() {
        for (manual in listOf(false, true)) {
            val r = Rig(duck().copy(conversationPause = true)); r.ownedPauseAndDuck()
            r.ca(false); r.coordinator.onDispatched(r.sent.last(), accepted = true)
            r.observe { it.copy(playback = Playback.UNKNOWN) }
            val count = r.sent.size
            if (manual) r.coordinator.onManualIntent()
            else r.observe { it.copy(playback = Playback.PLAYING, transportOverride = true) }
            r.advance(1_000)
            assertEquals("manual=$manual", count, r.sent.size); assertFalse(r.status().volumeOwned)
        }
    }

    @Test fun windowExpiryWhileStillUnknownForfeitsDuringLongRestore() {
        val r = Rig(duck().copy(conversationPause = true, rampMillis = 5_000)); r.ownedPauseAndDuck(5_000)
        r.ca(false); r.coordinator.onDispatched(r.sent.last(), accepted = true)
        r.observe { it.copy(playback = Playback.UNKNOWN) }
        r.advance(1_000); assertEquals(26, r.sent.last().volume); r.confirmVolume()
        val count = r.sent.size
        r.advance(1_000) // bound reached, playback still UNKNOWN
        r.advance(3_000)
        assertEquals(count, r.sent.size); assertFalse(r.status().volumeOwned)
    }

    @Test fun unconfirmedOwnPlayWhilePausedClosesWindowButKeepsRestoring() {
        val r = Rig(duck().copy(conversationPause = true, rampMillis = 5_000)); r.ownedPauseAndDuck(5_000)
        r.ca(false); r.coordinator.onDispatched(r.sent.last(), accepted = true)
        r.advance(2_000); assertEquals(32, r.sent.last().volume); r.confirmVolume()
        // Window closed with PAUSED: a later UNKNOWN is no longer our transition.
        r.advance(3_000); assertEquals(50, r.sent.last().volume); r.confirmVolume()
        assertFalse(r.status().volumeOwned)
        val q = Rig(duck().copy(conversationPause = true, rampMillis = 5_000)); q.ownedPauseAndDuck(5_000)
        q.ca(false); q.coordinator.onDispatched(q.sent.last(), accepted = true)
        q.advance(2_000); q.confirmVolume(); val count = q.sent.size
        q.observe { it.copy(playback = Playback.UNKNOWN) }; q.advance(3_000)
        assertEquals(count, q.sent.size); assertFalse(q.status().volumeOwned)
    }

    @Test fun dispatchNoticeForStaleLeaseOrOtherEffectOpensNoWindow() {
        val r = Rig(duck().copy(conversationPause = true)); r.ownedPauseAndDuck()
        r.ca(false)
        val play = r.sent.last()
        r.coordinator.onDispatched(play.copy(id = play.id + 100), accepted = true)
        r.coordinator.onDispatched(play.copy(lease = play.lease - 1), accepted = true)
        r.coordinator.onDispatched(play, accepted = true, expectedInputEpoch = r.status().inputEpoch - 1)
        val lease = r.status().lease
        r.observe { it.copy(playback = Playback.UNKNOWN) }
        assertTrue(lease in r.cancelled); assertFalse(r.status().volumeOwned)
    }
}
