// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.automation

/** Normalized validated RX only. Missing/invalid sides MUST map to UNKNOWN, never NONE. */
enum class EarState { BOTH, LEFT, RIGHT, NONE, UNKNOWN }
enum class Playback { PLAYING, PAUSED, UNKNOWN }
enum class PauseReason { EAR, CONVERSATION }
enum class VolumeMode { RELATIVE, ABSOLUTE }
/** C6 correlations are explicitly weaker than local corroborated receipts. */
enum class MediaEvidence { LOCAL_CORROBORATED, EXTERNAL_BEST_EFFORT }
enum class RouteEvidence { UNAVAILABLE, LOCAL_RENDERER, LOCAL_HISTORICAL_RENDERER, EXTERNAL_STREAMING_INFERENCE, EXTERNAL_HISTORICAL_INFERENCE }

/**
 * Local preferences, not device configuration/readback. No persistence or wire dependency.
 * earResumeEnabled governs only ear reinsertion. A conversation pause is "pause/resume during
 * conversation" by itself (catalog host.ca_pause, pinned LibrePods stopSpeaking()).
 */
data class AutomationConfig(
    val pairId: String,
    val enabled: Boolean = false,
    val earEnabled: Boolean = false,
    val earResumeEnabled: Boolean = true,
    val conversationEnabled: Boolean = false,
    val conversationPause: Boolean = false,
    val conversationDuck: Boolean = true,
    val volumeMode: VolumeMode = VolumeMode.RELATIVE,
    val volumePercent: Int = 40,
    val rampMillis: Long = 300,
    val observationTimeoutMillis: Long = 2_000,
) {
    init {
        require(pairId.isNotBlank())
        require(rampMillis in 0..10_000)
        require(observationTimeoutMillis in 1..30_000)
    }
    val clampedVolumePercent: Int get() = volumePercent.coerceIn(10, 85)
}

/** generation is the root's connection epoch; lease below also changes on local reconfigure. */
data class AutomationSession(val pairId: String, val generation: Long)

/** All fields are identity: account/session/route changes cannot inherit an effect. */
data class MediaTarget(
    val applicationId: String,
    val sessionId: String,
    val sessionGeneration: Long,
    val routeId: String,
    val accountEpoch: String,
)

data class MediaVolume(val current: Int, val maximum: Int) {
    init { require(maximum > 0 && current in 0..maximum) }
}

/**
 * Adapter observations, never optimistic command echoes. sequence strictly increases per
 * AutomationSession. Attribution IDs are supplied ONLY with reliable causal evidence; Android
 * callbacks alone usually cannot prove this. Unknown attribution forfeits automatic restoration.
 * Local manual PAUSE must be reported even if playback was already PAUSED. C6 external adapters
 * use only the separate correlated* fields and disclose unobservable interventions. Adapters detect
 * user/app overrides and call/account/route changes before dispatching any queued effect.
 */
data class MediaSnapshot(
    val session: AutomationSession,
    val sequence: Long,
    val target: MediaTarget?,
    val playback: Playback = Playback.UNKNOWN,
    val volume: MediaVolume? = null,
    val permitted: Boolean = false,
    val unambiguous: Boolean = false,
    val routedToPair: Boolean = false,
    val callActive: Boolean = false,
    val transportOverride: Boolean = false,
    val volumeOverride: Boolean = false,
    val observedPauseEffect: Long? = null,
    val observedVolumeEffect: Long? = null,
    /** Another duck owner (including Fluidify player) prevents a second host attenuation. */
    val foreignDuckActive: Boolean = false,
    val evidence: MediaEvidence = MediaEvidence.LOCAL_CORROBORATED,
    val externalBestEffortConsented: Boolean = false,
    val correlatedPauseEffect: Long? = null,
    val correlatedVolumeEffect: Long? = null,
    val routeEvidence: RouteEvidence = RouteEvidence.UNAVAILABLE,
)

enum class MediaAction { PAUSE, PLAY, SET_VOLUME }
data class MediaEffect(
    val id: Long,
    val lease: Long,
    val session: AutomationSession,
    val target: MediaTarget,
    val action: MediaAction,
    val volume: Int? = null,
)

fun interface AutomationClock { fun nowMillis(): Long }

/**
 * One arbiter for local Fluidify and external MediaSessions. submit is nonblocking, non-reentrant
 * and returns acceptance only. Revalidate target/route/account/call/permission/lease at execution.
 * cancel must revoke all queued effects in that lease. Do not independently duck in the player.
 * Implementations must not throw; a rejected command is not retried. No global fake playback.
 */
interface MediaHost {
    fun submit(effect: MediaEffect): Boolean
    fun cancel(lease: Long)
    /** Revoke an individual pending command without cancelling an unrelated CA volume ramp. */
    fun cancelEffect(effect: MediaEffect)
}

data class AutomationStatus(
    val session: AutomationSession?,
    val lease: Long,
    val inputEpoch: Long,
    val ear: EarState?,
    val reasons: Set<PauseReason>,
    val pausePending: Boolean,
    val ownsPause: Boolean,
    val volumeOwned: Boolean,
    val rampActive: Boolean,
)
