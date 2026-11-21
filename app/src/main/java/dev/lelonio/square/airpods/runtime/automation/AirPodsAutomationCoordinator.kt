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
    private data class PendingPause(val effect: MediaEffect, val deadline: Long, val origin: PauseReason,
        // LibrePods semantics: ownership is decided by "we dispatched the PAUSE and the
        // target then paused", not by a correlated receipt id. Receipts stay telemetry.
        val dispatched: Boolean = false)
    private data class OwnedPause(val target: MediaTarget, val origin: PauseReason)
    // dispatchedUntil: set only when the host actually handed this PLAY to its target. It bounds
    // the window in which a transitional state can be that target executing our resume.
    private data class PendingResume(val effect: MediaEffect, val earDependent: Boolean,
        val dispatchedUntil: Long? = null)
    private var pendingResume: PendingResume? = null
    private var pending: PendingPause? = null
    private var ownedPause: OwnedPause? = null
    private data class Duck(
        val target: MediaTarget, var initial: Int, val maximum: Int,
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
        // Steps actually submitted, kept after their deadlines so a late receipt of
        // any of them is recognized as ours instead of a foreign volume move.
        val stepIds: ArrayDeque<Long> = ArrayDeque(),
    )
    private var duck: Duck? = null
    // A manual transport intent while out-of-ear suppresses the repeated-NONE re-pause
    // until the buds are worn again: pressing play by hand must stick.
    private var earManualOverride = false

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
    @Synchronized fun onManualIntent() { earManualOverride = true; invalidateEffects() }

    /** Manual play/pause/seek/skip consumes transport only. Keep the same duck lease and
     * pending receipt/deadline; a volume command still uses onManualIntent() above.
     */
    @Synchronized fun onManualTransportIntent() {
        earManualOverride = true
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
        // Dispatch truth (FIX-11/12): an accepted PAUSE becomes ownership-eligible the
        // moment the target actually pauses; a rejected dispatch frees its slot at once
        // instead of lingering until a deadline abandons unrelated effects.
        pending?.takeIf { it.effect.id == effect.id }?.let { p ->
            pending = if (accepted) p.copy(dispatched = true) else null
        }
        if (!accepted) duck?.takeIf { it.awaiting?.id == effect.id }?.let { it.awaiting = null }
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
        earManualOverride = false
        conversation = null
        reasons.clear()
        pending = null
        ownedPause = null
        pendingResume = null
        duck = null
    }

    // Route-to-pair is deliberately not a play/pause condition (upstream sends the
    // global media key to the active session); it stays required only where a wrong
    // route would be destructive (audio disconnect, handled by the runtime).
    private fun eligible(s: MediaSnapshot?): Boolean = s != null && s.target != null &&
        s.permitted && s.unambiguous && !s.callActive &&
        (s.evidence != MediaEvidence.EXTERNAL_BEST_EFFORT || s.routedToPair || s.routeEvidence != RouteEvidence.UNAVAILABLE) &&
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
        // A manual-play attestation must be latched even on a snapshot that is not
        // (or no longer) eligible: the override is exactly what invalidated it. A pending
        // PAUSE attests only once dispatched: a fenced queued effect never reached the player.
        if (snapshot.transportOverride && snapshot.playback == Playback.PLAYING &&
            (ownedPause != null || pending?.dispatched == true ||
                (snapshot.evidence == MediaEvidence.EXTERNAL_BEST_EFFORT && old?.playback != Playback.PLAYING))) {
            earManualOverride = true
        }
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
                if (clock.nowMillis() >= p.deadline) dropPendingPause()
                else if (snapshot.playback == Playback.PAUSED) {
                    val observed = pauseEvidence(snapshot)
                    if (observed == p.effect.id || (observed == null && p.dispatched)) {
                        if (reasons.isNotEmpty()) ownedPause = OwnedPause(snapshot.target!!, p.origin)
                        pending = null
                    } else if (observed != null) {
                        host.cancelEffect(p.effect)
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
            // Duck and pause have separate fates from here on: nothing in this branch
            // may abandon an owned pause, and a duck step never waits for a receipt
            // to keep ramping (upstream ramps unconditionally and always restores).
            if (v == null || v.maximum != d.maximum || snapshot.foreignDuckActive) dropDuck()
            // A volume revision caused by our own step landing is not an override:
            // rebasing on it would move the restore target to the ducked level.
            else if (snapshot.volumeOverride && !(snapshot.evidence == MediaEvidence.EXTERNAL_BEST_EFFORT &&
                v.current == a?.volume)) rebaseDuck(d, v.current)
            else if (a != null) {
                if (clock.nowMillis() >= d.deadline) { d.awaiting = null; d.lastWrite = v.current
                    if (d.restoring && d.lastWrite == d.initial) duck = null }
                else if (newReceipt && receipt == a.id) {
                    d.lastWrite = v.current
                    d.lastEvidence = receipt
                    d.observedWrite = true
                    d.stepIds.remove(a.id)
                    d.awaiting = null
                    if (d.restartAfterObservation) {
        d.restartAfterObservation = d.awaiting != null
                        d.from = d.lastWrite
                        d.started = clock.nowMillis()
                    }
                    if (d.restoring && d.lastWrite == d.initial) duck = null
                } else if (newReceipt && receipt != null && receipt in d.stepIds) {
                    // An earlier step of ours landing late while a newer one is in
                    // flight: accept its level; never mistake it for a foreign move.
                    d.stepIds.remove(receipt)
                    d.lastWrite = v.current
                    d.lastEvidence = receipt
                    d.observedWrite = true
                } else if ((v.current != d.lastWrite && v.current != a.volume) || newReceipt) {
                    rebaseDuck(d, v.current)
                }
                // A matching requested level with no new receipt is only a pending read.
                // It grants no ownership and never extends the original receipt deadline.
            } else if (newReceipt && receipt != null && receipt in d.stepIds) {
                // Our expired step landing late: accept its level, never rebase the
                // baseline onto it, or the conversation-end restore target is lost.
                d.lastWrite = v.current
                d.lastEvidence = receipt
                d.observedWrite = true
                d.stepIds.remove(receipt)
                if (d.restartAfterObservation) {
                    d.restartAfterObservation = false
                    d.from = d.lastWrite
                    d.started = clock.nowMillis()
                }
                if (d.restoring && d.lastWrite == d.initial) duck = null
            } else if (v.current != d.lastWrite || newReceipt) rebaseDuck(d, v.current)
        }
    }

    private fun invalidateEffects() {
        abandonEffects()
        reasons.clear()
    }

    /** A pause that never landed within its window: drop only the pause slot.
     * The duck and its volume baseline are unrelated and keep their own fate. */
    private fun dropPendingPause() {
        pending?.let { host.cancelEffect(it.effect) }
        pending = null
    }

    /** A manual/foreign volume move during a duck becomes the new baseline: restore at
     * conversation end targets the level the user last chose, and the ramp continues
     * toward its goal from there instead of abandoning (and orphaning the volume). */
    /** Dropping a duck must also cancel its queued step: an un-cancelled SET_VOLUME
     * already in the host queue would land after the duck is gone and overwrite
     * whatever level superseded it (manual choice, new volume domain, foreign duck). */
    private fun dropDuck() {
        duck?.awaiting?.let { host.cancelEffect(it) }
        duck = null
    }

    private fun rebaseDuck(d: Duck, level: Int) {
        d.awaiting?.let { host.cancelEffect(it) }
        d.initial = level
        d.lastWrite = level
        d.from = level
        d.awaiting = null
        d.restartAfterObservation = false
        d.started = clock.nowMillis()
        if (d.restoring) d.goal = level
        if (d.restoring && d.lastWrite == d.goal) duck = null
    }

    /** The user just set this exact level by hand: it becomes the duck baseline, so a
     * later conversation-end restore lands on the user's level, never the pre-duck one. */
    @Synchronized fun onManualVolumeLevel(level: Int) {
        duck?.let { rebaseDuck(it, level) }
    }

    private fun abandonTransportEffects() {
        // Individual cancellation fences queued transport without cancelling a volume
        // write that may already have applied; its receipt is still required to restore.
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
        // A live UNKNOWN sample is a glitch frame: it neither updates the baseline nor
        // poisons the next valid transition (it used to eat both the resume and the
        // following transition). Cache/non-live input still cleans up below.
        if (live && value == EarState.UNKNOWN) return
        ear = if (live) value else null
        // Observation is not permission to act. Retain the first live baseline even while
        // disabled; enabling never replays it, but the next physical transition can act.
        if (!c.enabled || !c.earEnabled) return
        if (!live) {
            invalidateEarResume()
            return
        }
        // Cold start counts as NONE, like upstream's default: a first worn sample is an
        // insertion (release still requires an owned pause); a first NONE with music
        // playing pauses like any repeated NONE.
        val from = before ?: EarState.NONE
        if (from == value) {
            // A repeated NONE while music plays re-pauses (upstream checks every packet).
            if (value == EarState.NONE && !earManualOverride) acquire(PauseReason.EAR)
            return
        }
        if (value != EarState.NONE) earManualOverride = false
        if (from == EarState.BOTH && value != EarState.BOTH ||
            isMono(from) && value == EarState.NONE) acquire(PauseReason.EAR)
        else if (from == EarState.NONE && value != EarState.NONE ||
            isMono(from) && value == EarState.BOTH) release(PauseReason.EAR)
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
        // A direction change keeps the one pending write: it may already have landed,
        // and only its receipt (or deadline) says at which level the new ramp starts.
        d.restoring = true
        d.from = d.lastWrite
        d.goal = d.initial
        d.started = clock.nowMillis()
        d.restartAfterObservation = false
        if (d.awaiting == null && d.from == d.goal) duck = null else tick()
    }

    /** Monotonic clock; no wall-clock timers. One unobserved volume write at most. */
    @Synchronized fun tick(expectedInputEpoch: Long = inputEpoch) {
        if (expectedInputEpoch != inputEpoch) return
        val c = config ?: return
        val now = clock.nowMillis()
        if (pending?.let { now >= it.deadline } == true) dropPendingPause()
        // A resume still unconfirmed at the bound: UNKNOWN now forfeits; otherwise close the window.
        if (pendingResume?.dispatchedUntil?.let { now >= it } == true) {
            if (media?.playback == Playback.UNKNOWN) abandonEffects() else pendingResume = null
        }
        val d = duck ?: return
        if (!eligible(media) || media?.target != d.target) { dropDuck(); return }
        if (d.awaiting != null && now < d.deadline) {
            // An expired step is cleared just below; expiry never abandons the duck.
            return
        }
        if (d.awaiting != null) { d.awaiting = null; media?.volume?.current?.let { d.lastWrite = it }
            if (d.restoring && d.lastWrite == d.initial) { duck = null; return } }
        val fraction = if (c.rampMillis == 0L) 1.0 else
            ((now - d.started).toDouble() / c.rampMillis).coerceIn(0.0, 1.0)
        val next = (d.from + (d.goal - d.from) * fraction).roundToInt().coerceIn(0, d.maximum)
        if (next == d.lastWrite) return
        val e = effect(MediaAction.SET_VOLUME, next) ?: run { duck = null; return }
        if (host.submit(e)) {
            d.awaiting = e
            d.stepIds.addLast(e.id); while (d.stepIds.size > 8) d.stepIds.removeFirst()
            d.deadline = now + c.observationTimeoutMillis
        } else duck = null
    }

    @Synchronized fun status() = AutomationStatus(session, lease, inputEpoch, ear, reasons.toSet(),
        pending != null, ownedPause != null, duck?.observedWrite == true,
        duck?.let { it.awaiting != null || it.lastWrite != it.goal } == true)
}
