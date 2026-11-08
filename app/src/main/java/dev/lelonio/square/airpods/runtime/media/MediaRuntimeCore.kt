// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.media

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
)

interface MediaRuntimeEnvironment {
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
    private val onVolume: (MediaVolume?) -> Unit = {},
) : MediaHost {
    private val coordinator = AirPodsAutomationCoordinator(this, clock)
    private var session: AutomationSession? = null
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
        settings = value
        epoch++
        observer?.close()
        environment.configureExternal(value.automationsEnabled && value.otherAppsEnabled &&
            (visible || value.fluidifyBackgroundEnabled))
        tick?.close(); tick = null
        previous = null
        val s = session ?: return
        coordinator.stop()
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
        // Visibility is authorization, so revoke queued effects/baselines on either transition.
        session?.let { coordinator.stop(); configure(settings) }
    }

    private fun selected(): MediaCandidate? {
        val candidates = runCatching { environment.read() }.getOrDefault(emptyList())
        // Never select a different paused session merely because another playing one is ineligible.
        val playing = candidates.filter { it.playback == Playback.PLAYING }
        return if (playing.size == 1) playing.single()
        else if (playing.isEmpty() && candidates.size == 1) candidates.single() else null
    }

    private fun accessible(c: MediaCandidate): Boolean = c.permitted &&
        c.routedToPair && !c.callActive && (visible || settings.fluidifyBackgroundEnabled) &&
        (if (c.local) true else
            settings.automationsEnabled && settings.otherAppsEnabled && c.evidence == MediaEvidence.EXTERNAL_BEST_EFFORT)

    private fun allowed(c: MediaCandidate): Boolean = settings.automationsEnabled && accessible(c)

    private fun refresh(input: Long = coordinator.status().inputEpoch): MediaCandidate? {
        val s = session ?: return null
        val c = selected()
        val old = previous
        previous = c
        onVolume(c?.takeIf { it.permitted && it.routedToPair && !it.callActive }?.volume)
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
        queue.post {
            if (captured != epoch || effect.lease <= cancelledThrough || effect.id in cancelledEffects ||
                effect.session != session || effect.lease != coordinator.status().lease ||
                input != coordinator.status().inputEpoch) return@post
            val c = refresh(input)
            // refresh may revoke the lease on account/route/permission/target/override change.
            if (c == null || !allowed(c) || c.target != effect.target ||
                effect.lease != coordinator.status().lease) return@post
            if (effect.action == MediaAction.SET_VOLUME && c.foreignDuckActive) return@post
            environment.execute(effect.target, effect.action, effect.volume, effect.id)
            // A read is not an acknowledgement. Origin receipts must arrive through observe.
        }
        return true
    }

    override fun cancel(lease: Long) {
        cancelledThrough = maxOf(cancelledThrough, lease)
        cancelledEffects.clear()
    }
    override fun cancelEffect(effect: MediaEffect) { cancelledEffects.add(effect.id) }

    fun manualVolume(value: Int): Boolean {
        val c = selected() ?: return false
        if (!accessible(c) || c.volume == null || value !in 0..c.volume.maximum) return false
        revokeManual()
        val now = selected() ?: return false
        return now.target == c.target && accessible(now) &&
            environment.execute(now.target, MediaAction.SET_VOLUME, value, null)
    }

    fun gesture(action: AirPodsMediaGesture): Boolean {
        val c = selected() ?: return false
        if (!accessible(c)) return false
        revokeManual()
        val now = selected() ?: return false
        return now.target == c.target && accessible(now) && environment.gesture(now.target, action)
    }

    private fun revokeManual() { coordinator.stop(); configure(settings) }

    fun stop() {
        epoch++
        observer?.close(); observer = null
        environment.configureExternal(false)
        tick?.close(); tick = null
        coordinator.stop()
        session = null; previous = null
        cancelledEffects.clear()
        onVolume(null)
    }
}
