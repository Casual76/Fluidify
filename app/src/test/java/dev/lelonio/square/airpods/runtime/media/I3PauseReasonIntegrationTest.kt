// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.media

import dev.lelonio.square.airpods.runtime.AirPodsMediaGesture
import dev.lelonio.square.airpods.runtime.automation.*
import dev.lelonio.square.airpods.runtime.storage.HostSettings
import java.io.Closeable
import org.junit.Assert.*
import org.junit.Test

/** CORRECTIVE3: real core/coordinator and deferred dispatch; providers are fake. NOT EXECUTED by writer. */
class I3PauseReasonIntegrationTest {
    private class Queue : MediaRuntimeQueue {
        val jobs = ArrayDeque<() -> Unit>()
        override fun post(block: () -> Unit) { jobs.addLast(block) }
        override fun later(delayMillis: Long, block: () -> Unit) = Closeable { }
        fun drain() { while (jobs.isNotEmpty()) jobs.removeFirst()() }
    }
    private class Env(external: Boolean) : MediaRuntimeEnvironment {
        var candidate = MediaCandidate(MediaTarget("app", "session", 1, "pair-route", "account"),
            Playback.PLAYING, MediaVolume(50, 100), !external, true, true, false, false,
            evidence = if (external) MediaEvidence.EXTERNAL_BEST_EFFORT else MediaEvidence.LOCAL_CORROBORATED,
            routeEvidence = if (external) RouteEvidence.EXTERNAL_STREAMING_INFERENCE else RouteEvidence.LOCAL_RENDERER)
        var observer: (() -> Unit)? = null
        var receipts = true
        val sent = mutableListOf<MediaAction>()
        override fun read() = listOf(candidate)
        override fun observe(changed: () -> Unit): Closeable {
            observer = changed
            return Closeable { observer = null }
        }
        override fun execute(target: MediaTarget, action: MediaAction, value: Int?, effectId: Long?): Boolean {
            sent.add(action)
            candidate = when (action) {
                MediaAction.PAUSE -> candidate.copy(playback = Playback.PAUSED,
                    observedPauseEffect = effectId.takeIf { receipts && candidate.local },
                    correlatedPauseEffect = effectId.takeIf { receipts && !candidate.local })
                MediaAction.PLAY -> candidate.copy(playback = Playback.PLAYING)
                MediaAction.SET_VOLUME -> error("duck disabled in this fixture")
            }
            observer?.invoke()
            return true
        }
        override fun gesture(target: MediaTarget, action: AirPodsMediaGesture) = false
    }
    private class Rig(external: Boolean) {
        val queue = Queue()
        val env = Env(external)
        val session = AutomationSession("pair", 1)
        val core = MediaRuntimeCore(env, queue, AutomationClock { 0L })
        val settings = HostSettings(automationsEnabled = true, otherAppsEnabled = true,
            fluidifyBackgroundEnabled = true, earPause = true, earResume = true, conversationPause = true)
        init { core.start(session, settings) }
        fun drain() = queue.drain()
        fun ear(value: EarState) = core.ear(value)
        fun ca(value: Boolean) = core.conversation(value)
        // Core exposes only validated live RX. Reach its real coordinator solely to exercise
        // the cache ingress contract without adding a production API for a test.
        fun cachedEar() {
            val field = MediaRuntimeCore::class.java.getDeclaredField("coordinator")
            field.isAccessible = true
            (field.get(core) as AirPodsAutomationCoordinator).onEar(session, EarState.NONE, live = false)
        }
        fun invalidate(cache: Boolean) { if (cache) cachedEar() else ear(EarState.UNKNOWN) }
        fun assertActions(vararg expected: MediaAction) = assertEquals(expected.toList(), env.sent)
    }

    @Test fun caOnlyQueuedAndOwnedSurviveUnknownCacheAndFreshBaselineForLocalAndC6() {
        for (external in listOf(false, true)) for (owned in listOf(false, true))
            for (cache in listOf(false, true)) {
                val r = Rig(external); r.ear(EarState.BOTH); r.ca(true)
                if (owned) r.drain()
                r.invalidate(cache); r.ear(EarState.BOTH); r.drain()
                r.assertActions(MediaAction.PAUSE)
                r.ca(false); r.drain(); r.assertActions(MediaAction.PAUSE, MediaAction.PLAY)
            }
    }

    @Test fun firstEarBaselineCannotDiscardFirstLiveConversationPause() {
        for (external in listOf(false, true)) for (owned in listOf(false, true)) {
            val r = Rig(external); r.ca(true); if (owned) r.drain()
            r.ear(EarState.BOTH); r.drain(); r.ca(false); r.drain()
            r.assertActions(MediaAction.PAUSE, MediaAction.PLAY)
        }
    }

    @Test fun earOnlyQueuedAndOwnedLoseResumeOnUnknownOrCache() {
        for (external in listOf(false, true)) for (owned in listOf(false, true))
            for (cache in listOf(false, true)) {
                val r = Rig(external); r.ear(EarState.BOTH); r.ear(EarState.NONE)
                if (owned) r.drain()
                r.invalidate(cache); r.ear(EarState.BOTH); r.drain()
                if (owned) r.assertActions(MediaAction.PAUSE) else r.assertActions()
            }
    }

    @Test fun overlapRetainsRequestOriginAcrossBothAcquisitionAndReleaseOrders() {
        for (external in listOf(false, true)) for (caOrigin in listOf(false, true))
            for (owned in listOf(false, true)) for (earReleasedFirst in listOf(false, true))
            for (cache in listOf(false, true)) {
                val r = Rig(external); r.ear(EarState.BOTH)
                if (caOrigin) { r.ca(true); r.ear(EarState.NONE) }
                else { r.ear(EarState.NONE); r.ca(true) }
                if (owned) r.drain()
                if (earReleasedFirst) r.ear(EarState.BOTH)
                r.invalidate(cache); r.ear(EarState.BOTH); r.drain()
                r.ca(false); r.drain()
                val expected = if (caOrigin) listOf(MediaAction.PAUSE, MediaAction.PLAY)
                    else if (owned) listOf(MediaAction.PAUSE) else emptyList()
                assertEquals("external=$external caOrigin=$caOrigin owned=$owned earReleased=$earReleasedFirst",
                    expected, r.env.sent)
            }
    }

    @Test fun conversationEndCannotReleaseEarAndUnknownCannotLeaveTokenForNewEarCycle() {
        for (external in listOf(false, true)) for (caOrigin in listOf(false, true)) {
            val r = Rig(external); r.ear(EarState.BOTH)
            if (caOrigin) { r.ca(true); r.ear(EarState.NONE) }
            else { r.ear(EarState.NONE); r.ca(true) }
            r.drain(); r.ca(false); r.drain(); r.assertActions(MediaAction.PAUSE)
            r.ear(EarState.UNKNOWN); r.ear(EarState.BOTH)
            r.ear(EarState.NONE); r.ear(EarState.BOTH); r.drain()
            r.assertActions(MediaAction.PAUSE)
        }
    }

    @Test fun queuedPlayKeepsEarDependencyAfterReasonsAreEmpty() {
        for (external in listOf(false, true)) for (caOrigin in listOf(false, true))
            for (earLast in listOf(false, true)) for (cache in listOf(false, true)) {
                val r = Rig(external); r.ear(EarState.BOTH)
                if (caOrigin) { r.ca(true); r.ear(EarState.NONE) }
                else { r.ear(EarState.NONE); r.ca(true) }
                r.drain()
                if (earLast) { r.ca(false); r.ear(EarState.BOTH) }
                else { r.ear(EarState.BOTH); r.ca(false) }
                r.invalidate(cache); r.drain()
                if (caOrigin && !earLast) r.assertActions(MediaAction.PAUSE, MediaAction.PLAY)
                else r.assertActions(MediaAction.PAUSE)
            }
    }

    @Test fun caOnlyQueuedPlaySurvivesUnrelatedEarUnknownButNewBlockerCancelsIt() {
        for (external in listOf(false, true)) for (newBlocker in listOf(false, true)) {
            val r = Rig(external); r.ear(EarState.BOTH); r.ca(true); r.drain(); r.ca(false)
            r.ear(EarState.UNKNOWN)
            if (newBlocker) r.ca(true)
            r.drain()
            if (newBlocker) r.assertActions(MediaAction.PAUSE)
            else r.assertActions(MediaAction.PAUSE, MediaAction.PLAY)
        }
    }

    @Test fun pausedWithoutReceiptCannotAcquireCaOwnershipInEitherEvidenceDomain() {
        for (external in listOf(false, true)) {
            val r = Rig(external); r.env.receipts = false
            r.ca(true); r.drain(); r.ear(EarState.UNKNOWN); r.ca(false); r.drain()
            r.assertActions(MediaAction.PAUSE)
        }
    }

    @Test fun receiptsCannotCrossLocalAndC6EvidenceDomains() {
        for (external in listOf(false, true)) {
            val r = Rig(external); r.env.receipts = false
            r.ca(true); r.drain()
            // First request ID is 1; place it exclusively in the wrong evidence domain.
            r.env.candidate = if (external) r.env.candidate.copy(observedPauseEffect = 1L)
                else r.env.candidate.copy(correlatedPauseEffect = 1L)
            r.ear(EarState.UNKNOWN); r.ca(false); r.drain()
            r.assertActions(MediaAction.PAUSE)
        }
    }

    @Test fun preservedCaOriginStillRechecksRevocationsBeforeQueuedPauseAndPlay() {
        val changes: List<(MediaCandidate) -> MediaCandidate> = listOf(
            { it.copy(permitted = false) }, { it.copy(routedToPair = false) },
            { it.copy(callActive = true) }, { it.copy(playback = Playback.UNKNOWN) },
            { it.copy(transportRevision = 1) },
            { it.copy(target = it.target.copy(accountEpoch = "other")) },
            { it.copy(target = it.target.copy(routeId = "other")) },
            { it.copy(target = it.target.copy(sessionGeneration = 2)) },
        )
        for (external in listOf(false, true)) for (resume in listOf(false, true)) for (change in changes) {
            val r = Rig(external); r.ear(EarState.BOTH); r.ca(true)
            if (resume) { r.drain(); r.ca(false) }
            r.ear(EarState.UNKNOWN); r.env.candidate = change(r.env.candidate); r.drain()
            if (resume) r.assertActions(MediaAction.PAUSE) else r.assertActions()
        }
    }

    @Test fun cleanupOrConsentRevocationNeverDispatchPreservedCaResume() {
        for (external in listOf(false, true)) for (stop in listOf(false, true)) {
            val r = Rig(external); r.ca(true); r.drain(); r.ear(EarState.UNKNOWN); r.ca(false)
            if (stop) r.core.stop() else r.core.configure(r.settings.copy(automationsEnabled = false))
            r.drain(); r.assertActions(MediaAction.PAUSE)
        }
    }
}
