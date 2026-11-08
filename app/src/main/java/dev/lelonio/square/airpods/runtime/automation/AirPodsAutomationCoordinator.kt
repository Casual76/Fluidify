// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.automation

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure Kotlin C5 policy. All calls serialized by this monitor; host must not call back inline.
 * No Android, codec, jobs or timers. Owner's cancellable scope feeds tick() while rampActive or
 * pausePending; closing that scope and stop() leaves no scheduled work here. Events carry the
 * current session; cached events use live=false and establish baselines without effects.
 * Async adapters MUST capture status().inputEpoch at registration and pass expectedInputEpoch
 * on every callback/tick. Defaults are for direct synchronous calls only. Re-register after
 * configure; normalize/deduplicate RX in arrival order before calling this policy.
 * Derived semantics: pinned LibrePods AirPodsService ear/CA behavior, corrected by F1 C5.
 */
class AirPodsAutomationCoordinator(private val host: MediaHost, private val clock: AutomationClock) {
    private var config: AutomationConfig? = null
    private var session: AutomationSession? = null
    private var lease = 0L
    private var inputEpoch = 0L
    private var nextId = 0L
    private var media: MediaSnapshot? = null
    private var sequence = Long.MIN_VALUE
    private var ear: EarState? = null
    private var conversation: Boolean? = null
    private val reasons = mutableSetOf<PauseReason>()
    // Origin belongs to the actual PAUSE request, never to the current set of blockers.
    private data class PendingPause(val effect: MediaEffect, val deadline: Long, val origin: PauseReason)
    private data class OwnedPause(val target: MediaTarget, val origin: PauseReason)
    private data class PendingResume(val effect: MediaEffect, val earDependent: Boolean)
    private var pendingResume: PendingResume? = null
    private var pending: PendingPause? = null
    private var ownedPause: OwnedPause? = null
    private data class Duck(
        val target: MediaTarget, val initial: Int, val maximum: Int,
        var lastWrite: Int, var goal: Int, var from: Int, var started: Long,
        var awaiting: MediaEffect? = null, var deadline: Long = 0,
        var restoring: Boolean = false,
        var observedWrite: Boolean = false,
        // Receipt already present at baseline or consumed at the last confirmed write.
        // Its persistence at lastWrite is neither a new ACK nor interference.
        var lastEvidence: Long? = null,
    )
    private var duck: Duck? = null

    /** Equal configuration/session is idempotent. Every real reconfigure resets both baselines. */
    @Synchronized fun configure(value: AutomationConfig, generation: Long) {
        val newSession = AutomationSession(value.pairId, generation)
        if (config == value && session == newSession) return
        clear()
        config = value
        session = newSession
    }

    /** Stop/disconnect/revoke/unpair: no PLAY or volume rollback during cleanup. */
    @Synchronized fun stop() {
        if (session == null && config == null) return
        clear()
        session = null
        config = null
    }

    private fun clear() {
        host.cancel(lease)
        lease++
        inputEpoch++
        media = null
        sequence = Long.MIN_VALUE
        ear = null
        conversation = null
        reasons.clear()
        pending = null
        ownedPause = null
        pendingResume = null
        duck = null
    }

    private fun eligible(s: MediaSnapshot?): Boolean = s != null && s.target != null &&
        s.permitted && s.unambiguous && s.routedToPair && !s.callActive &&
        (s.evidence != MediaEvidence.EXTERNAL_BEST_EFFORT || s.externalBestEffortConsented)

    private fun pauseEvidence(s: MediaSnapshot): Long? = when (s.evidence) {
        MediaEvidence.LOCAL_CORROBORATED -> s.observedPauseEffect
        MediaEvidence.EXTERNAL_BEST_EFFORT -> s.correlatedPauseEffect.takeIf { s.externalBestEffortConsented }
    }
    private fun volumeEvidence(s: MediaSnapshot): Long? = when (s.evidence) {
        MediaEvidence.LOCAL_CORROBORATED -> s.observedVolumeEffect
        MediaEvidence.EXTERNAL_BEST_EFFORT -> s.correlatedVolumeEffect.takeIf { s.externalBestEffortConsented }
    }

    @Synchronized fun onMedia(snapshot: MediaSnapshot, expectedInputEpoch: Long = inputEpoch) {
        if (expectedInputEpoch != inputEpoch || snapshot.session != session || snapshot.sequence <= sequence) return
        sequence = snapshot.sequence
        val old = media
        media = snapshot
        if (!eligible(snapshot) || !eligible(old) || old?.target != snapshot.target || old?.evidence != snapshot.evidence ||
            old?.callActive != snapshot.callActive) {
            invalidateEffects()
            // Target recovery does not replay the last ear/conversation state.
            ear = null
            conversation = null
            return
        }
        if (snapshot.transportOverride || snapshot.playback == Playback.UNKNOWN) {
            abandonEffects()
            if (snapshot.playback == Playback.PLAYING) reasons.remove(PauseReason.EAR)
        } else {
            pending?.let { p ->
                if (clock.nowMillis() >= p.deadline) abandonEffects()
                else if (snapshot.playback == Playback.PAUSED) {
                    val observed = pauseEvidence(snapshot)
                    if (observed != null) {
                        if (observed == p.effect.id && reasons.isNotEmpty()) ownedPause = OwnedPause(snapshot.target!!, p.origin)
                        else host.cancelEffect(p.effect)
                        pending = null
                    }
                    // Exo's application state may precede the sink/native receipt.
                    // Unattributed PAUSED grants nothing; keep only the bounded
                    // pending request until matching evidence, override or timeout.
                }
            }
            if (snapshot.playback == Playback.PLAYING && ownedPause != null) {
                ownedPause = null
                reasons.remove(PauseReason.EAR)
            }
        }
        duck?.let { d ->
            val v = snapshot.volume
            val a = d.awaiting
            val receipt = volumeEvidence(snapshot)
            val newReceipt = receipt != null && receipt != d.lastEvidence
            if (snapshot.volumeOverride || snapshot.foreignDuckActive || v == null ||
                v.maximum != d.maximum) abandonEffects()
            else if (a != null) {
                if (clock.nowMillis() >= d.deadline) abandonEffects()
                else if (newReceipt && receipt == a.id && v.current == a.volume) {
                    d.lastWrite = v.current
                    d.lastEvidence = receipt
                    d.observedWrite = true
                    d.awaiting = null
                    if (d.restoring && d.lastWrite == d.initial) duck = null
                } else if (v.current != d.lastWrite || newReceipt) {
                    abandonEffects()
                }
            } else if (v.current != d.lastWrite || newReceipt) abandonEffects()
        }
    }

    private fun invalidateEffects() {
        abandonEffects()
        reasons.clear()
    }

    private fun abandonEffects() {
        host.cancel(lease)
        lease++
        pending = null
        ownedPause = null
        pendingResume = null
        duck = null
    }

    @Synchronized fun onEar(eventSession: AutomationSession, value: EarState, live: Boolean = true,
        expectedInputEpoch: Long = inputEpoch) {
        val c = config ?: return
        if (expectedInputEpoch != inputEpoch || eventSession != session || !c.enabled || !c.earEnabled) return
        val before = ear
        ear = if (live) value else null
        if (!live || value == EarState.UNKNOWN || before == null || before == EarState.UNKNOWN) {
            invalidateEarResume()
            return
        }
        if (before == value) return
        if (before == EarState.BOTH && value != EarState.BOTH ||
            isMono(before) && value == EarState.NONE) acquire(PauseReason.EAR)
        else if (before == EarState.NONE && value != EarState.NONE ||
            isMono(before) && value == EarState.BOTH) release(PauseReason.EAR)
        // L <-> R: preserve the reason, no new action.
    }

    /** Unknown/cache/baseline is cleanup, never a release that can emit PLAY. */
    private fun invalidateEarResume() {
        reasons.remove(PauseReason.EAR)
        // CA cannot inherit a request originally issued by EAR. Conversely, an active
        // CA-origin pause survives ear uncertainty, even if EAR joined in the meantime.
        fun invalid(origin: PauseReason) = origin == PauseReason.EAR ||
            PauseReason.CONVERSATION !in reasons
        pending?.takeIf { invalid(it.origin) }?.let {
            host.cancelEffect(it.effect)
            pending = null
        }
        if (ownedPause?.let { invalid(it.origin) } == true) ownedPause = null
        pendingResume?.takeIf { it.earDependent }?.let {
            host.cancelEffect(it.effect)
            pendingResume = null
        }
    }

    /** Hardware CA config is intentionally absent: this consumes validated voice events only. */
    @Synchronized fun onConversation(eventSession: AutomationSession, active: Boolean, live: Boolean = true,
        expectedInputEpoch: Long = inputEpoch) {
        val c = config ?: return
        if (expectedInputEpoch != inputEpoch || eventSession != session || !c.enabled || !c.conversationEnabled) return
        val before = conversation
        if (!live) {
            conversation = null // cached state cannot suppress a subsequent fresh live activation
            reasons.remove(PauseReason.CONVERSATION)
            abandonEffects()
            return
        }
        conversation = active
        if (before == active) return
        // Unlike ear's first-RX baseline, a live voice start is actionable immediately.
        if (active) {
            if (c.conversationPause) acquire(PauseReason.CONVERSATION)
            if (c.conversationDuck) startDuck()
        } else {
            if (c.conversationPause) release(PauseReason.CONVERSATION)
            finishDuck()
        }
    }

    private fun isMono(e: EarState) = e == EarState.LEFT || e == EarState.RIGHT

    private fun acquire(reason: PauseReason) {
        // A new blocker must also revoke a previously queued resume.
        pendingResume?.let { host.cancelEffect(it.effect) }
        pendingResume = null
        reasons.add(reason)
        val s = media ?: return
        if (!eligible(s) || s.transportOverride || s.playback != Playback.PLAYING ||
            pending != null || ownedPause != null) return
        val e = effect(MediaAction.PAUSE) ?: return
        if (host.submit(e)) pending = PendingPause(e, clock.nowMillis() + config!!.observationTimeoutMillis, reason)
    }

    private fun release(reason: PauseReason) {
        if (!reasons.remove(reason) || reasons.isNotEmpty()) return
        val s = media
        val owner = ownedPause
        val mayPlay = config?.resumeEnabled == true && eligible(s) &&
            owner != null && owner.target == s?.target && s?.playback == Playback.PAUSED &&
            s?.transportOverride == false
        pending?.let { host.cancelEffect(it.effect) }
        pending = null
        ownedPause = null // consume before submit, including failure
        if (mayPlay) effect(MediaAction.PLAY)?.let {
            if (host.submit(it)) pendingResume = PendingResume(it,
                owner!!.origin == PauseReason.EAR || reason == PauseReason.EAR)
        }
    }

    private fun effect(action: MediaAction, volume: Int? = null): MediaEffect? {
        val s = media ?: return null
        if (!eligible(s) || config?.enabled != true) return null
        // A player/provider can outlive this coordinator and retain its last receipt.
        // Never reuse that receipt as a new command ID: freshness must remain observable.
        val retained = listOfNotNull(s.observedPauseEffect, s.observedVolumeEffect,
            s.correlatedPauseEffect, s.correlatedVolumeEffect)
        do { nextId++ } while (nextId in retained)
        return MediaEffect(nextId, lease, session ?: return null, s.target ?: return null, action, volume)
    }

    private fun startDuck() {
        val s = media ?: return
        val c = config ?: return
        val v = s.volume ?: return
        val existing = duck
        if (existing != null) {
            if (existing.restoring) {
                if (existing.awaiting != null) { abandonEffects(); return }
                existing.restoring = false
                existing.from = existing.lastWrite
                val base = if (c.volumeMode == VolumeMode.RELATIVE) existing.initial else existing.maximum
                existing.goal = min(existing.initial,
                    (base.toDouble() * c.clampedVolumePercent / 100).roundToInt())
                existing.started = clock.nowMillis()
            }
            return
        }
        if (!eligible(s) || s.foreignDuckActive || s.volumeOverride ||
            s.playback == Playback.UNKNOWN) return
        val base = if (c.volumeMode == VolumeMode.RELATIVE) v.current else v.maximum
        val goal = min(v.current, (base.toDouble() * c.clampedVolumePercent / 100).roundToInt())
        if (goal == v.current) return
        duck = Duck(s.target!!, v.current, v.maximum, v.current, goal, v.current, clock.nowMillis(),
            lastEvidence = volumeEvidence(s))
        tick()
    }

    private fun finishDuck() {
        val d = duck ?: return
        // An unobserved write may have applied; never restore from uncertain attribution.
        if (d.awaiting != null) { abandonEffects(); return }
        d.restoring = true
        d.from = d.lastWrite
        d.goal = d.initial
        d.started = clock.nowMillis()
        if (d.from == d.goal) duck = null else tick()
    }

    /** Monotonic clock; no wall-clock timers. One unobserved volume write at most. */
    @Synchronized fun tick(expectedInputEpoch: Long = inputEpoch) {
        if (expectedInputEpoch != inputEpoch) return
        val c = config ?: return
        val now = clock.nowMillis()
        if (pending?.let { now >= it.deadline } == true) abandonEffects()
        val d = duck ?: return
        if (!eligible(media) || media?.target != d.target) { duck = null; return }
        if (d.awaiting != null) {
            if (now >= d.deadline) abandonEffects()
            return
        }
        val fraction = if (c.rampMillis == 0L) 1.0 else
            ((now - d.started).toDouble() / c.rampMillis).coerceIn(0.0, 1.0)
        val next = (d.from + (d.goal - d.from) * fraction).roundToInt().coerceIn(0, d.maximum)
        if (next == d.lastWrite) return
        val e = effect(MediaAction.SET_VOLUME, next) ?: run { duck = null; return }
        if (host.submit(e)) {
            d.awaiting = e
            d.deadline = now + c.observationTimeoutMillis
        } else duck = null
    }

    @Synchronized fun status() = AutomationStatus(session, lease, inputEpoch, ear, reasons.toSet(),
        pending != null, ownedPause != null, duck?.observedWrite == true,
        duck?.let { it.awaiting != null || it.lastWrite != it.goal } == true)
}
