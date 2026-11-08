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
        pause = null; volume = null; pauseCorrelation = null; volumeCorrelation = null
        lastPlayback = null; lastVolume = null
        transportRevision++; volumeRevision++
    }

    fun candidates(raw: List<MediaCandidate>, facts: ExternalMediaFacts): List<MediaCandidate> {
        // Every contender matters, including a remote or local Fluidify player.
        val playing = raw.filter { it.playback == Playback.PLAYING }
        val only = if (playing.size == 1) playing.single() else
            if (playing.isEmpty() && raw.size == 1) raw.single() else null
        val external = only?.takeIf { !it.local && it.unavailableReason != "REMOTE_PLAYBACK" &&
            it.unavailableReason != "UNKNOWN_PLAYBACK_TYPE" }
        val identity = external?.target?.copy(routeId = facts.routeKey)
        val valid = enabled && facts.permitted && facts.exactPairConnected &&
            facts.systemRouteCoherent && !facts.callActive && external != null &&
            (facts.exactPairStreaming || historical && target == identity && external.playback == Playback.PAUSED)
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
                pauseCorrelation = null
                pause = id?.let { Request(c.target, it, epoch, clock.nowMillis() + 2_000,
                    started = clock.nowMillis()) }
                historical = pause != null && c.playback == Playback.PLAYING
            }
            MediaAction.PLAY -> { pause = null; pauseCorrelation = null }
            MediaAction.SET_VOLUME -> {
                volumeCorrelation = null
                volume = id?.let { Request(c.target, it, epoch, clock.nowMillis() + 2_000, value) }
            }
        }
    }

    /** Callback is correlation only; capture target from registration, never infer command origin. */
    fun playbackObserved(valueTarget: MediaTarget, playback: Playback, stateUpdatedAt: Long? = null) {
        if (!enabled || target != valueTarget) return
        val p = pause
        // Reject a demonstrably pre-request state delivered late by the controller. Some apps
        // omit this timestamp: arrival then remains an explicit C6 assumption, never causality.
        if (p != null && stateUpdatedAt != null && stateUpdatedAt > 0 && stateUpdatedAt < p.started) return
        if (p != null && p.target == target && p.epoch == epoch &&
            clock.nowMillis() < p.deadline && playback == Playback.PAUSED) {
            pauseCorrelation = p.id; pause = null
        } else if (lastPlayback != playback || playback == Playback.PAUSED) {
            // Duplicate PAUSED is conservatively treated as an observable intervention.
            transportRevision++; pauseCorrelation = null; pause = null; historical = false
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
