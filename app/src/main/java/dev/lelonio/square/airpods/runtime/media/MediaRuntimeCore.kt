// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.media

import dev.lelonio.square.airpods.*
import dev.lelonio.square.airpods.runtime.AirPodsMediaGesture
import dev.lelonio.square.airpods.runtime.automation.*
import dev.lelonio.square.airpods.runtime.storage.HostSettings
import java.io.Closeable

/** All methods run on one serial executor. post must enqueue, never invoke inline. */
interface MediaRuntimeQueue {
    fun post(block: () -> Unit)
    fun later(delayMillis: Long, block: () -> Unit): Closeable
}

data class MediaCandidate(
    val target: MediaTarget,
    val playback: Playback,
    val volume: MediaVolume?,
    val local: Boolean,
    val permitted: Boolean,
    val routedToPair: Boolean,
    val callActive: Boolean,
    val foreignDuckActive: Boolean = true,
    val transportRevision: Long = 0,
    val volumeRevision: Long = 0,
    // Only a corroborated origin receipt, never inferred from matching state/value.
    val observedPauseEffect: Long? = null,
    val observedVolumeEffect: Long? = null,
    val unavailableReason: String? = null,
    val evidence: MediaEvidence = MediaEvidence.LOCAL_CORROBORATED,
    val routeEvidence: RouteEvidence = RouteEvidence.UNAVAILABLE,
    val correlatedPauseEffect: Long? = null,
    val correlatedVolumeEffect: Long? = null,
    // Only the local renderer can attest no media item and no pending PLAY. UNKNOWN alone
    // (buffering, stopped, missing controller state, remote output) is never this evidence.
    val knownEmptyLocal: Boolean = false,
)

/** Phone stream observation only: domain identifies the selected pair lifetime, not a media route. */
data class PhoneStreamVolume(val domain: String, val volume: MediaVolume)

interface MediaRuntimeEnvironment {
    /** Fresh pair/permission/mode/range read. No candidate, NLS, route proof or C6 receipt. */
    fun readPhoneVolume(): PhoneStreamVolume? = null
    /** Reread and reject changed domain/range or lost authority immediately before platform write. */
    fun writePhoneVolume(expected: PhoneStreamVolume, value: Int): Boolean = false
    /** Reconfigure/revoke before observation; implementations clear all C6 history. */
    fun configureExternal(enabled: Boolean) {}
    /** Fresh reads, including route/account/permission; no cached authorization. */
    fun read(): List<MediaCandidate>
    fun observe(changed: () -> Unit): Closeable
    fun execute(target: MediaTarget, action: MediaAction, value: Int?, effectId: Long?): Boolean
    fun gesture(target: MediaTarget, action: AirPodsMediaGesture): Boolean
}

/** Single policy and volume domain shared by local and external sessions. */
class MediaRuntimeCore(
    private val environment: MediaRuntimeEnvironment,
    private val queue: MediaRuntimeQueue,
    clock: AutomationClock,
    private val trace: AirPodsQualificationObserver = AirPodsQualificationObserver.NONE,
    private val authority: () -> Boolean = { true },
    private val onVolume: (MediaVolume?) -> Unit = {},
) : MediaHost {
    private val coordinator = AirPodsAutomationCoordinator(this, clock)
    private var session: AutomationSession? = null
    private val targetAliases = LinkedHashMap<MediaTarget, Int>()
    private fun alias(target: MediaTarget): Int = targetAliases[target] ?: if (targetAliases.size < 64)
        (targetAliases.size + 1).also { targetAliases[target] = it } else 0
    private var settings = HostSettings()
    private var visible = false
    private var epoch = 0L
    private var sequence = 0L
    private var observer: Closeable? = null
    private var tick: Closeable? = null
    private var previous: MediaCandidate? = null
    private var cancelledThrough = -1L
    private val cancelledEffects = mutableSetOf<Long>()

    fun start(value: AutomationSession, valueSettings: HostSettings) {
        stop()
        session = value
        configure(valueSettings)
    }

    fun configure(value: HostSettings) {
        value.validate()
        // PairSettings saves also reapply unchanged HostSettings. Preserve the observer,
        // pending receipts, sensor baseline and effect lease, but reread all live guards.
        if (settings == value && observer != null) {
            refresh()
            return
        }
        settings = value
        epoch++
        observer?.close()
        environment.configureExternal(value.automationsEnabled && value.otherAppsEnabled &&
            (visible || value.fluidifyBackgroundEnabled))
        tick?.close(); tick = null
        previous = null
        val s = session ?: return
        // earResume is the ear-reinsertion option only; conversationPause carries its own resume.
        coordinator.configure(AutomationConfig(s.pairId, value.automationsEnabled,
            value.earPause, value.earResume, value.conversationDuck || value.conversationPause,
            value.conversationPause, value.conversationDuck,
            if (value.conversationRelative) VolumeMode.RELATIVE else VolumeMode.ABSOLUTE,
            value.conversationVolumePercent), s.generation)
        val captured = epoch
        val input = coordinator.status().inputEpoch
        observer = environment.observe { queue.post {
            if (captured == epoch) refresh(input)
        } }
        refresh(input)
    }

    fun visible(value: Boolean) {
        if (visible == value) return
        visible = value
        if (session == null) return
        // Visibility revokes media authority without discarding live sensor facts from this
        // same Bluetooth session. The ineligible refresh cancels every owned/queued effect;
        // reopening does not replay a removal or recover the discarded C6 history.
        if (!settings.fluidifyBackgroundEnabled) environment.configureExternal(
            settings.automationsEnabled && settings.otherAppsEnabled && visible)
        refresh()
    }

    private fun selected(): MediaCandidate? {
        val candidates = runCatching { environment.read() }.getOrDefault(emptyList())
        return selectMediaCandidate(candidates, previous?.target)
    }

    private fun accessible(c: MediaCandidate): Boolean = authority() && c.permitted &&
        c.routedToPair && !c.callActive && (visible || settings.fluidifyBackgroundEnabled) &&
        (if (c.local) true else
            settings.automationsEnabled && settings.otherAppsEnabled && c.evidence == MediaEvidence.EXTERNAL_BEST_EFFORT)

    private fun allowed(c: MediaCandidate): Boolean = settings.automationsEnabled && accessible(c)

    private fun refresh(input: Long = coordinator.status().inputEpoch): MediaCandidate? {
        val s = session ?: return null
        val c = selected()
        val old = previous
        previous = c
        trace.observe(QualificationEvent(QualificationStep.MEDIA_OBSERVE, QualificationPhase.OBSERVED,
            s.generation, token = c?.observedPauseEffect ?: c?.correlatedPauseEffect,
            value = c?.target?.let(::alias), flags = (if(c?.local == true) 1 else 0) or
                (if(c?.routedToPair == true) 2 else 0) or (if(c?.callActive == true) 4 else 0) or
                ((c?.playback?.ordinal ?: 0) shl 4) or ((c?.evidence?.ordinal ?: 0) shl 8) or
                ((c?.routeEvidence?.ordinal ?: 0) shl 12)))
        c?.let { trace.observe(QualificationEvent(QualificationStep.MEDIA_OBSERVE, QualificationPhase.OBSERVED,
            s.generation, token = it.observedVolumeEffect ?: it.correlatedVolumeEffect,
            value = it.volume?.current, flags = 1 shl 20)) }
        publishPhoneVolume()
        coordinator.onMedia(MediaSnapshot(s, ++sequence, c?.target,
            c?.playback ?: Playback.UNKNOWN, c?.volume, c?.let(::allowed) == true,
            c != null, c?.routedToPair == true, c?.callActive == true,
            old?.target == c?.target && old?.transportRevision != c?.transportRevision,
            old?.target == c?.target && old?.volumeRevision != c?.volumeRevision,
            c?.observedPauseEffect, c?.observedVolumeEffect, c?.foreignDuckActive ?: true,
            c?.evidence ?: MediaEvidence.LOCAL_CORROBORATED,
            settings.automationsEnabled && settings.otherAppsEnabled,
            c?.correlatedPauseEffect, c?.correlatedVolumeEffect,
            c?.routeEvidence ?: RouteEvidence.UNAVAILABLE), input)
        scheduleTick()
        return c
    }

    fun ear(value: EarState) {
        val s = session ?: return
        val input = coordinator.status().inputEpoch
        refresh(input)
        coordinator.onEar(s, value, expectedInputEpoch = input)
        scheduleTick()
    }

    fun conversation(active: Boolean) {
        val s = session ?: return
        val input = coordinator.status().inputEpoch
        refresh(input)
        coordinator.onConversation(s, active, expectedInputEpoch = input)
        scheduleTick()
    }

    private fun scheduleTick() {
        val status = coordinator.status()
        if (!status.pausePending && !status.rampActive) { tick?.close(); tick = null; return }
        if (tick != null) return
        val captured = epoch
        val input = status.inputEpoch
        tick = queue.later(50) {
            if (captured == epoch) {
                tick = null
                refresh(input)
                coordinator.tick(input)
                scheduleTick()
            }
        }
    }

    override fun submit(effect: MediaEffect): Boolean {
        val captured = epoch
        val input = coordinator.status().inputEpoch
        trace.observe(QualificationEvent(QualificationStep.MEDIA_REQUEST, QualificationPhase.REQUESTED,
            effect.session.generation, sequence = effect.lease, token = effect.id, value = effect.action.ordinal))
        queue.post {
            if (!authority() || captured != epoch || effect.lease <= cancelledThrough || effect.id in cancelledEffects ||
                effect.session != session || effect.lease != coordinator.status().lease ||
                input != coordinator.status().inputEpoch) return@post
            val c = refresh(input)
            // refresh may revoke the lease on account/route/permission/target/override change.
            if (c == null || !allowed(c) || c.target != effect.target ||
                effect.lease != coordinator.status().lease) return@post
            if (effect.action == MediaAction.SET_VOLUME && c.foreignDuckActive) return@post
            val accepted = environment.execute(effect.target, effect.action, effect.volume, effect.id)
            trace.observe(QualificationEvent(QualificationStep.MEDIA_RESULT, QualificationPhase.UNCONFIRMED,
                effect.session.generation, sequence = effect.lease, token = effect.id,
                value = alias(effect.target), flags = if (accepted) 1 else 0))
            // A read is not an acknowledgement. Origin receipts must arrive through observe.
            // Dispatch only opens the bounded transitional window of our own PLAY.
            coordinator.onDispatched(effect, accepted, input)
        }
        return true
    }

    override fun cancel(lease: Long) {
        cancelledThrough = maxOf(cancelledThrough, lease)
        cancelledEffects.clear()
    }
    override fun cancelEffect(effect: MediaEffect) { cancelledEffects.add(effect.id) }

    private fun phoneVolume(): PhoneStreamVolume? =
        if (session == null || !authority()) null else runCatching { environment.readPhoneVolume() }.getOrNull()

    private fun publishPhoneVolume() { onVolume(phoneVolume()?.volume) }

    /** Synchronous foreground user action on this same serial domain. The owner supplies a
     * dispatch-time guard captured from its admitted ticket before crossing to the handler.
     * Matching values still revoke ownership; neither a read nor a successful set is a receipt.
     */
    fun manualVolume(value: Int, stillCurrent: () -> Boolean = { true }): Boolean {
        val admitted = session ?: return false
        if (!visible || !stillCurrent()) return false
        val before = phoneVolume() ?: return false
        if (value !in 0..before.volume.maximum) return false
        revokeManual()
        val now = phoneVolume()
        val accepted = authority() && session == admitted && visible && stillCurrent() && now != null &&
            now.domain == before.domain && now.volume.maximum == before.volume.maximum &&
            value in 0..now.volume.maximum &&
            runCatching { environment.writePhoneVolume(now, value) }.getOrDefault(false)
        // Publish a fresh platform read, including after a rejected/failed set. No callback echo.
        publishPhoneVolume()
        trace.observe(QualificationEvent(QualificationStep.MEDIA_RESULT, QualificationPhase.UNCONFIRMED,
            admitted.generation, value = value, flags = if (accepted) 2 else 0))
        return accepted
    }

    fun gesture(action: AirPodsMediaGesture): Boolean {
        val c = selected() ?: return false
        if (!accessible(c)) return false
        revokeManual()
        val now = selected() ?: return false
        val accepted = now.target == c.target && accessible(now) && environment.gesture(now.target, action)
        trace.observe(QualificationEvent(QualificationStep.MEDIA_RESULT, QualificationPhase.UNCONFIRMED,
            session?.generation ?: 0, value = alias(now.target), flags = if (accepted) 4 else 0))
        return accepted
    }

    private fun revokeManual() {
        coordinator.onManualIntent()
        tick?.close(); tick = null
    }

    fun stop() {
        trace.observe(QualificationEvent(QualificationStep.MEDIA_CLOSE, QualificationPhase.REQUESTED, session?.generation ?: 0))
        epoch++
        observer?.close(); observer = null
        environment.configureExternal(false)
        tick?.close(); tick = null
        coordinator.stop()
        session = null; previous = null
        cancelledEffects.clear()
        onVolume(null)
        targetAliases.clear()
        trace.observe(QualificationEvent(QualificationStep.MEDIA_CLOSE, QualificationPhase.COMPLETED))
    }
}
