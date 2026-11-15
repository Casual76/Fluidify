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
    // dispatchedUntil: set only when the host actually handed this PLAY to its target. It bounds
    // the window in which a transitional state can be that target executing our resume.
    private data class PendingResume(val effect: MediaEffect, val earDependent: Boolean,
        val dispatchedUntil: Long? = null)
    private var pendingResume: PendingResume? = null
    private var pending: PendingPause? = null
    private var ownedPause: OwnedPause? = null
    private data class Duck(
        val target: MediaTarget, val initial: Int, val maximum: Int,
        var lastWrite: Int, var goal: Int, var from: Int, var started: Long,
        var awaiting: MediaEffect? = null, var deadline: Long = 0,
        var restoring: Boolean = false,
        var observedWrite: Boolean = false,
        // A manual transport command forfeits pause/resume, not this volume baseline.
        // Only that explicit intervention permits retaining it through a loading state.
        var transportOverridden: Boolean = false,
        // Receipt already present at baseline or consumed at the last confirmed write.
        // Its persistence at lastWrite is neither a new ACK nor interference.
        var lastEvidence: Long? = null,
        // Speech may start/end while the previous write is still being observed. Keep its
        // original receipt deadline, then begin the new direction at the confirmed level.
        var restartAfterObservation: Boolean = false,
    )
    private var duck: Duck? = null

    /** Reconfigure revokes every effect. Only live ear history of the same session survives. */
    @Synchronized fun configure(value: AutomationConfig, generation: Long) {
        val newSession = AutomationSession(value.pairId, generation)
        if (config == value && session == newSession) return
        val liveEar = ear.takeIf { session == newSession }
        clear()
        ear = liveEar
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

    /** An explicit volume intent or blanket revocation consumes all authority, keeping sensor baselines.
     * No cleanup PLAY/restore; duplicates cannot reacquire, but a new ear transition still can.
     * Keep inputEpoch: observations remain current, while the cancelled lease fences old effects.
     */
    @Synchronized fun onManualIntent() { invalidateEffects() }

    /** Manual play/pause/seek/skip consumes transport only. Keep the same duck lease and
     * pending receipt/deadline; a volume command still uses onManualIntent() above.
     */
    @Synchronized fun onManualTransportIntent() {
        abandonTransportEffects()
        reasons.clear()
    }

    /**
     * Host notice that an accepted PLAY of the current lease was actually dispatched. From then,
     * for observationTimeoutMillis only, an UNKNOWN (e.g. BUFFERING) report can be the target
     * executing that resume and does not forfeit the remaining effect. It grants nothing: no
     * ownership, receipt or new command. Overrides and every other UNKNOWN still revoke.
     */
    @Synchronized fun onDispatched(effect: MediaEffect, accepted: Boolean,
        expectedInputEpoch: Long = inputEpoch) {
        val c = config ?: return
        if (expectedInputEpoch != inputEpoch || effect.session != session || effect.lease != lease) return
        val r = pendingResume?.takeIf { it.effect.id == effect.id } ?: return
        pendingResume = if (accepted) r.copy(dispatchedUntil = clock.nowMillis() + c.observationTimeoutMillis)
            else null
    }

    private fun resumeInFlight(): Boolean =
        pendingResume?.dispatchedUntil?.let { clock.nowMillis() < it } == true

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
            // Media authority and sensor history have different lifetimes. A validated live
            // ear baseline belongs to this Bluetooth session, including before music starts.
            // Keep it for the NEXT physical transition; this read never replays an ear effect.
            // Stop/new session and UNKNOWN/cache ear input still reset that baseline.
            conversation = null
            return
        }
        if (snapshot.transportOverride) {
            abandonTransportEffects()
            if (snapshot.playback == Playback.PLAYING) reasons.remove(PauseReason.EAR)
        } else if (snapshot.playback == Playback.UNKNOWN) {
            // UNKNOWN can be our bounded dispatched PLAY or loading after an explicit manual
            // transport override. The latter retains volume only, never pause/resume authority.
            // Identity, route, permission, volume interference and receipt bounds still apply.
            if (!resumeInFlight() && duck?.transportOverridden != true) abandonEffects()
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
            // The first PLAYING closes the transitional window. It is not a receipt and
            // grants nothing; interventions are the adapter's transportOverride.
            if (snapshot.playback == Playback.PLAYING && pendingResume?.dispatchedUntil != null) pendingResume = null
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
                    if (d.restartAfterObservation) {
                        d.restartAfterObservation = false
                        d.from = d.lastWrite
                        d.started = clock.nowMillis()
                    }
                    if (d.restoring && d.lastWrite == d.initial) duck = null
                } else if ((v.current != d.lastWrite && v.current != a.volume) || newReceipt) {
                    abandonEffects()
                }
                // A matching requested level with no new receipt is only a pending read.
                // It grants no ownership and never extends the original receipt deadline.
            } else if (v.current != d.lastWrite || newReceipt) abandonEffects()
        }
    }

    private fun invalidateEffects() {
        abandonEffects()
        reasons.clear()
    }

    private fun abandonTransportEffects() {
        // Individual cancellation fences queued transport without cancelling a volume write
        // that may already have applied. Its original receipt is still required to restore.
        pending?.let { host.cancelEffect(it.effect) }
        pendingResume?.let { host.cancelEffect(it.effect) }
        pending = null
        ownedPause = null
        pendingResume = null
        duck?.transportOverridden = true
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
        if (expectedInputEpoch != inputEpoch || eventSession != session) return
        val before = ear
        ear = if (live) value else null
        // Observation is not permission to act. Retain the first live baseline even while
        // disabled; enabling never replays it, but the next physical transition can act.
        if (!c.enabled || !c.earEnabled) return
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

    /** Resume policy per pause reason; the ear-reinsertion option never gates the CA one. */
    private fun resumes(reason: PauseReason): Boolean = when (reason) {
        PauseReason.CONVERSATION -> config?.conversationPause == true
        PauseReason.EAR -> config?.earResumeEnabled == true
    }

    private fun release(reason: PauseReason) {
        if (!reasons.remove(reason) || reasons.isNotEmpty()) return
        val s = media
        val owner = ownedPause
        // Both the origin of the owned PAUSE and the blocker released last must allow resume:
        // CA end never resumes an EAR-origin pause without the ear option, and a reinsertion
        // ending a CA-origin pause still respects that option.
        val mayPlay = owner != null && resumes(owner.origin) && resumes(reason) && eligible(s) &&
            owner.target == s?.target && s?.playback == Playback.PAUSED &&
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
                existing.restoring = false
                existing.from = existing.lastWrite
                val base = if (c.volumeMode == VolumeMode.RELATIVE) existing.initial else existing.maximum
                existing.goal = min(existing.initial,
                    (base.toDouble() * c.clampedVolumePercent / 100).roundToInt())
                existing.started = clock.nowMillis()
                existing.restartAfterObservation = existing.awaiting != null
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
        // A direction change is not interference. Retain the one pending write and its
        // deadline; no restoration can run until that write has a matching receipt.
        d.restoring = true
        d.from = d.lastWrite
        d.goal = d.initial
        d.started = clock.nowMillis()
        d.restartAfterObservation = d.awaiting != null
        if (d.awaiting == null && d.from == d.goal) duck = null else tick()
    }

    /** Monotonic clock; no wall-clock timers. One unobserved volume write at most. */
    @Synchronized fun tick(expectedInputEpoch: Long = inputEpoch) {
        if (expectedInputEpoch != inputEpoch) return
        val c = config ?: return
        val now = clock.nowMillis()
        if (pending?.let { now >= it.deadline } == true) abandonEffects()
        // A resume still unconfirmed at the bound: UNKNOWN now forfeits; otherwise close the window.
        if (pendingResume?.dispatchedUntil?.let { now >= it } == true) {
            if (media?.playback == Playback.UNKNOWN) abandonEffects() else pendingResume = null
        }
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
