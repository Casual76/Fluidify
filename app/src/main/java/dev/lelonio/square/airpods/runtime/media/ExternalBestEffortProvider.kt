// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.media

import dev.lelonio.square.airpods.runtime.automation.*

/** Ordinary SDK facts. routeKey is system topology, NOT an external renderer route. */
data class ExternalMediaFacts(
    val permitted: Boolean,
    val exactPairConnected: Boolean,
    val exactPairStreaming: Boolean,
    val systemRouteCoherent: Boolean,
    val routeKey: String,
    val callActive: Boolean,
    val foreignDuckActive: Boolean = false,
)

/** C6 opt-in inference adapter; never fabricates a local receipt or a hardware ACK. Serial owner. */
class ExternalBestEffortProvider(private val clock: AutomationClock) {
    private var enabled = false
    private var epoch = 0L
    private var target: MediaTarget? = null
    private var historical = false
    private var route: String? = null
    private var pause: Request? = null
    private var resume: Request? = null
    private var volume: Request? = null
    private var pauseCorrelation: Long? = null
    private var volumeCorrelation: Long? = null
    private var transportRevision = 0L
    private var volumeRevision = 0L
    private var lastPlayback: Playback? = null
    private var lastVolume: Int? = null
    private data class Request(val target: MediaTarget, val id: Long, val epoch: Long,
        val deadline: Long, val value: Int? = null, val started: Long = 0)

    fun configure(value: Boolean) { enabled = value; invalidate() }
    fun invalidate() {
        epoch++; target = null; route = null; historical = false
        pause = null; resume = null; volume = null; pauseCorrelation = null; volumeCorrelation = null
        lastPlayback = null; lastVolume = null
        transportRevision++; volumeRevision++
    }

    fun candidates(raw: List<MediaCandidate>, facts: ExternalMediaFacts): List<MediaCandidate> {
        // Retain only our own pause history, with the same complete target identity and route.
        // Callback handling rereads here before correlating PAUSED, so pending history matters too.
        val retained = if (historical) raw.singleOrNull {
            !it.local && it.target.copy(routeId = facts.routeKey) == target
        }?.target else null
        val only = selectMediaCandidate(raw, retained)
        val external = only?.takeIf { !it.local && it.unavailableReason != "REMOTE_PLAYBACK" &&
            it.unavailableReason != "UNKNOWN_PLAYBACK_TYPE" }
        val identity = external?.target?.copy(routeId = facts.routeKey)
        // Our own correlated PLAY may pass through a transitional state (BUFFERING) before
        // streaming resumes; only inside its bound does that keep the historical evidence.
        val valid = enabled && facts.permitted && facts.exactPairConnected &&
            facts.systemRouteCoherent && !facts.callActive && external != null &&
            (facts.exactPairStreaming || historical && target == identity &&
                (external.playback == Playback.PAUSED || external.playback == Playback.UNKNOWN && resumeInFlight()))
        if (!valid || target != null && (target != identity || route != facts.routeKey)) invalidate()
        if (valid && identity != null) { target = identity; route = facts.routeKey }
        if (facts.foreignDuckActive) {
            volume = null; volumeCorrelation = null; volumeRevision++
        }
        return raw.map { c ->
            if (c.local) c else {
                val eligible = valid && c === external
                c.copy(target = c.target.copy(routeId = facts.routeKey),
                    permitted = c.permitted && facts.permitted && enabled,
                    routedToPair = eligible, callActive = facts.callActive,
                    foreignDuckActive = facts.foreignDuckActive,
                    evidence = MediaEvidence.EXTERNAL_BEST_EFFORT,
                    routeEvidence = if (!eligible) RouteEvidence.UNAVAILABLE else
                        if (facts.exactPairStreaming) RouteEvidence.EXTERNAL_STREAMING_INFERENCE
                        else RouteEvidence.EXTERNAL_HISTORICAL_INFERENCE,
                    transportRevision = transportRevision, volumeRevision = volumeRevision,
                    correlatedPauseEffect = pauseCorrelation.takeIf { eligible },
                    correlatedVolumeEffect = volumeCorrelation.takeIf { eligible },
                    observedPauseEffect = null, observedVolumeEffect = null,
                    unavailableReason = if (eligible) null else c.unavailableReason ?: "C6_INFERENCE_UNAVAILABLE")
            }
        }
    }

    /** Called only after a fresh execution recheck, before dispatch; failures invalidate. */
    fun requested(c: MediaCandidate, action: MediaAction, value: Int?, id: Long?) {
        if (!enabled || c.target != target || !c.routedToPair) return
        when (action) {
            MediaAction.PAUSE -> {
                resume = null
                pauseCorrelation = null
                pause = id?.let { Request(c.target, it, epoch, clock.nowMillis() + 2_000,
                    started = clock.nowMillis()) }
                historical = pause != null && c.playback == Playback.PLAYING
            }
            MediaAction.PLAY -> {
                // This is still C6 inference, not a causal receipt. A bounded PLAY following
                // our correlated pause must not look like a manual override of our own duck.
                resume = id?.takeIf { historical && pauseCorrelation != null &&
                    c.playback == Playback.PAUSED }?.let {
                    Request(c.target, it, epoch, clock.nowMillis() + 2_000,
                        started = clock.nowMillis())
                }
                pause = null; pauseCorrelation = null
            }
            MediaAction.SET_VOLUME -> {
                volumeCorrelation = null
                volume = id?.let { Request(c.target, it, epoch, clock.nowMillis() + 2_000, value) }
            }
        }
    }

    private fun resumeInFlight(): Boolean {
        val r = resume ?: return false
        return r.target == target && r.epoch == epoch && clock.nowMillis() < r.deadline
    }

    /**
     * Callback is correlation only; capture target from registration, never infer command origin.
     * interruption: the platform state is a stop/error/skip/seek/none, never a transition into
     * our own PLAY; it always counts as an observable intervention.
     */
    fun playbackObserved(valueTarget: MediaTarget, playback: Playback, stateUpdatedAt: Long? = null,
        interruption: Boolean = false) {
        if (!enabled || target != valueTarget) return
        val p = pause
        val r = resume
        val pending = p ?: r
        // Reject a demonstrably pre-request state delivered late by the controller. Some apps
        // omit this timestamp: arrival then remains an explicit C6 assumption, never causality.
        if (pending != null && stateUpdatedAt != null && stateUpdatedAt > 0 && stateUpdatedAt < pending.started) return
        if (p != null && p.target == target && p.epoch == epoch &&
            clock.nowMillis() < p.deadline && playback == Playback.PAUSED) {
            pauseCorrelation = p.id; pause = null
        } else if (r != null && r.target == target && r.epoch == epoch &&
            clock.nowMillis() < r.deadline && playback == Playback.PLAYING) {
            // Only retain the existing volume authority through this expected transition.
            // No local receipt, new pause ownership or new route evidence is created.
            resume = null; historical = false
        } else if (!interruption && playback == Playback.UNKNOWN && resumeInFlight()) {
            // Transitional report (BUFFERING/CONNECTING) of our own correlated PLAY: it neither
            // consumes the expectation nor counts as an intervention. Still inference, never a
            // receipt: a PAUSED, an interruption or the deadline revoke exactly as before.
        } else if (interruption || lastPlayback != playback || playback == Playback.PAUSED) {
            // Duplicate PAUSED is conservatively treated as an observable intervention.
            transportRevision++; pauseCorrelation = null; pause = null; resume = null; historical = false
        }
        lastPlayback = playback
    }

    /** Actual STREAM_MUSIC observation, not PlaybackInfo volume and not an optimistic write. */
    fun volumeObserved(valueTarget: MediaTarget, current: Int) {
        if (!enabled || target != valueTarget) return
        val p = volume
        if (p != null && p.target == target && p.epoch == epoch &&
            clock.nowMillis() < p.deadline && p.value == current) {
            volumeCorrelation = p.id; volume = null
        } else if (lastVolume != current) {
            volumeRevision++; volumeCorrelation = null; volume = null
        }
        lastVolume = current
    }

    fun currentTarget(): MediaTarget? = target
    fun currentEpoch(): Long = epoch
}
