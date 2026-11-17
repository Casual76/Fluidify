// SPDX-License-Identifier: GPL-3.0-or-later
// Quiet-period behavior follows LibrePods contributors (2025), EqualizerScreen.kt and
// AdaptiveStrengthScreen.kt, pinned 89356c08cea3db0b6b54792d9329d09a87cc85fa.

package dev.lelonio.square.ui.airpods

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.antigravity.fluidengine.ui.fluid.FluidSlider
import dev.antigravity.fluidengine.ui.fluid.FluidSliderRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Live settings slider, following LibrePods' quiet-period debounce with an explicit final flush.
 * The draft belongs to this gesture; received/effective updates take over again after release.
 * Composition and incoming values never send; disposing drops the pending debounce.
 * Callers key the control by session generation so a retired gesture cannot reach a new session.
 */
@Composable
internal fun AirPodsLiveSlider(
    value: Int,
    onSend: (Int) -> Unit,
    modifier: Modifier = Modifier,
    range: IntRange = 0..100,
    debounceMs: Long = 100L,
    enabled: Boolean = true,
    title: String? = null,
) {
    val scope = rememberCoroutineScope()
    val currentSend by rememberUpdatedState(onSend)
    val currentEnabled by rememberUpdatedState(enabled)
    val gesture = remember(scope, range, debounceMs) {
        AirPodsLiveSliderGesture(scope, range, debounceMs) { if (currentEnabled) currentSend(it) }
    }
    DisposableEffect(gesture, enabled) {
        if (!enabled) gesture.cancel()
        onDispose { gesture.cancel() }
    }
    val shown = gesture.draft ?: value.coerceIn(range)
    val change: (Float) -> Unit = { next ->
        if (currentEnabled && next.isFinite()) gesture.change(next.roundToInt(), value)
    }
    val finish: () -> Unit = { if (currentEnabled) gesture.finish(value) else gesture.cancel() }
    if (title == null) {
        FluidSlider(
            value = shown.toFloat(), onValueChange = change, modifier = modifier,
            valueRange = range.first.toFloat()..range.last.toFloat(), enabled = enabled,
            onValueChangeFinished = finish,
        )
    } else {
        FluidSliderRow(
            title = title, value = shown.toFloat(), onValueChange = change, modifier = modifier,
            valueRange = range.first.toFloat()..range.last.toFloat(), enabled = enabled,
            onValueChangeFinished = finish,
        )
    }
}

/** One cancellable timer, never a second transport queue. All callbacks run on the UI scope. */
internal class AirPodsLiveSliderGesture(
    private val scope: CoroutineScope,
    private val range: IntRange,
    private val debounceMs: Long,
    private val onSend: (Int) -> Unit,
) {
    init {
        require(!range.isEmpty())
        require(debounceMs >= 0)
    }

    var draft: Int? by mutableStateOf(null)
        private set
    private var lastSent: Int? = null
    private var timer: Job? = null

    fun change(value: Int, effective: Int) {
        // A new gesture may deliberately restore a value contradicted since our previous send.
        if (draft == null && effective != lastSent) lastSent = null
        val next = value.coerceIn(range)
        if (draft == next) return
        draft = next
        timer?.cancel()
        timer = scope.launch {
            delay(debounceMs)
            sendDistinct(next)
        }
    }

    fun finish(effective: Int? = null) {
        timer?.cancel()
        timer = null
        val finalValue = draft ?: return
        draft = null
        if (effective != null && effective != finalValue) lastSent = null
        sendDistinct(finalValue)
    }

    fun cancel() {
        timer?.cancel()
        timer = null
        draft = null
    }

    private fun sendDistinct(value: Int) {
        if (!scope.isActive || value == lastSent) return
        lastSent = value
        onSend(value)
    }
}
