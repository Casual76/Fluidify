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

    val level: StateFlow<Float> = _level.asStateFlow()
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    /** What the phone last reported; ignored while the hand is turning. */
    fun sync(remoteLevel: Float) {
        coalescer.syncFromRemote(remoteLevel)
        if (!_visible.value) _level.value = coalescer.target
    }

    fun turn(steps: Int) {
        _level.value = coalescer.turn(steps.toFloat())
        show()
        schedule()
    }

    fun set(level: Float) {
        _level.value = coalescer.set(level)
        show()
        schedule()
    }

    private fun schedule() {
        if (sendJob?.isActive == true) return
        sendJob = scope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                val due = coalescer.dueAt() ?: break
                if (due > now) delay(due - now)
                coalescer.take(System.currentTimeMillis())?.let { controls.setVolume(it) }
            }
        }
    }

    private fun show() {
        _visible.value = true
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(VISIBLE_MS)
            _visible.value = false
        }
    }

    private companion object {
        const val VISIBLE_MS = 1_600L
    }
}
