package dev.pampa.fluidify.wear.playback

import dev.pampa.fluidify.wear.protocol.logic.VolumeCoalescer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The bezel as a volume knob for whatever is playing.
 *
 * The level on screen moves with every detent; the phone hears about it at most
 * ten times a second and once more when the hand stops ([VolumeCoalescer]), so a
 * quick spin is a handful of Bluetooth messages, not fifty. [visible] is true
 * while the hand is on the bezel and for a moment after, which is how long the
 * level is drawn.
 */
class VolumeControl(private val scope: CoroutineScope, private val controls: PlaybackControls) {

    private val coalescer = VolumeCoalescer()
    private val _level = MutableStateFlow(0f)
    private val _visible = MutableStateFlow(false)
    private var sendJob: Job? = null
    private var hideJob: Job? = null
    private var known = false
    init {
        scope.launch {
            var device: Pair<dev.pampa.fluidify.wear.protocol.PlaybackSource?, String?>? = null
            controls.nowPlaying.collect { now ->
                val next = now.snapshot?.source to now.snapshot?.device?.id
                if (next != device) { device = next; reset(now.snapshot?.device?.volume) }
                else now.snapshot?.device?.volume?.let(::sync)
            }
        }
    }

    val level: StateFlow<Float> = _level.asStateFlow()
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    /** What the phone last reported; ignored while the hand is turning. */
    fun sync(remoteLevel: Float) {
        known = true
        coalescer.syncFromRemote(remoteLevel)
        if (!_visible.value) _level.value = coalescer.target
    }

    fun turn(steps: Int) {
        if (!known || !canSend()) return
        _level.value = coalescer.turn(steps.toFloat())
        show()
        schedule()
    }

    fun set(level: Float) {
        if (!known || !canSend()) return
        _level.value = coalescer.set(level)
        show()
        schedule()
    }

    private fun canSend(): Boolean = controls.nowPlaying.value.let {
        it.link == dev.pampa.fluidify.wear.link.LinkStatus.CONNECTED && it.snapshot?.device?.canSetVolume != false
    }

    fun reset(remoteLevel: Float?) {
        sendJob?.cancel()
        hideJob?.cancel()
        known = remoteLevel != null
        coalescer.reset(remoteLevel ?: 0f)
        _level.value = coalescer.target
        _visible.value = false
    }

    private fun schedule() {
        if (sendJob?.isActive == true) return
        sendJob = scope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                val due = coalescer.dueAt() ?: break
                if (due > now) delay(due - now)
                if (!canSend()) { reset(controls.nowPlaying.value.snapshot?.device?.volume); break }
                coalescer.take(System.currentTimeMillis())?.let { controls.setVolume(it) }
            }
        }
    }

    private fun show() {
        _visible.value = true
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(VISIBLE_MS)
            _level.value = coalescer.target
            _visible.value = false
        }
    }

    private companion object {
        const val VISIBLE_MS = 1_600L
    }
}
