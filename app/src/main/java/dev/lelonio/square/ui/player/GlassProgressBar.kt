package dev.lelonio.square.ui.player

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidScrubbing
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.lelonio.square.R
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The playback position, as a thin capsule.
 *
 * Not a slider, and not the waveform this
 * replaced. The waveform looked good but invented its shape, and the reference
 * this screen follows uses a plain bar. The library's slider is the wrong tool
 * for a different reason: it reports every intermediate value, and each one here
 * would be a seek — the engine would spend a drag re-buffering instead of
 * playing.
 *
 * So the position is scrubbed locally and committed once, on release. While a
 * drag is in progress the incoming position is ignored, or each update would
 * yank the bar back to where playback still is.
 *
 * It has a folded form as well, for the now-playing panel beside a page: at
 * [amount] zero it is the hairline the panel always had, three points high and
 * not for touching, and at one it is this bar. The two are one number apart,
 * read in measure and draw, so the panel growing into a player carries the bar
 * with it instead of swapping one for the other.
 */
@Composable
fun GlassProgressBar(
    positionMs: State<Long>,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    accentColor: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
    /** How much of a bar this is, 0 (a hairline) to 1. */
    amount: () -> Float = { 1f },
    /** False for a bar that is a picture of one, or one folded too thin to grab. */
    interactive: Boolean = true,
    /** Clears unfinished input when the caller changes track, including equal-duration tracks. */
    resetKey: Any? = null,
) {
    var scrubbing by remember(positionMs, durationMs, resetKey) { mutableStateOf<Float?>(null) }
    var pending by remember(positionMs, durationMs, resetKey) { mutableStateOf<Long?>(null) }
    var pendingOrigin by remember(positionMs, durationMs, resetKey) { mutableLongStateOf(0L) }
    var pendingRevision by remember(positionMs, durationMs, resetKey) { mutableIntStateOf(0) }
    val focusInteraction = remember { MutableInteractionSource() }
    val focused by focusInteraction.collectIsFocusedAsState()
    val fullyShown by remember(amount) { derivedStateOf { amount() >= 0.999f } }
    val seekable = interactive && fullyShown && durationMs > 0
    val currentSeekable by rememberUpdatedState(seekable)
    val currentDuration by rememberUpdatedState(durationMs)
    val currentOnSeek by rememberUpdatedState(onSeek)
    val description = stringResource(R.string.player_seek_position)
    val backLabel = stringResource(R.string.player_seek_back)
    val forwardLabel = stringResource(R.string.player_seek_forward)
    val valuePattern = stringResource(R.string.player_seek_value)

    LaunchedEffect(seekable, resetKey) {
        if (!seekable) { scrubbing = null; pending = null; pendingRevision++ }
    }

    val capturedRevision = pendingRevision
    LaunchedEffect(capturedRevision, seekable, positionMs, durationMs, resetKey) {
        val requested = pending ?: return@LaunchedEffect
        if (!seekable) return@LaunchedEffect
        val tolerance = minOf(1_000.0, maxOf(1.0, abs(requested.toDouble() - pendingOrigin.toDouble()) / 2.0))
        withTimeoutOrNull(FluidMotion.ScrubberHoldMillis) {
            snapshotFlow { positionMs.value }.first { abs(it.toDouble() - requested.toDouble()) <= tolerance }
        }
        if (pendingRevision == capturedRevision) pending = null
    }

    fun seekTo(value: Float): Boolean {
        if (!currentSeekable || !value.isFinite()) return false
        val target = value.coerceIn(0f, currentDuration.toFloat()).toLong().coerceIn(0L, currentDuration)
        scrubbing = null
        pendingOrigin = positionMs.value
        pending = target
        pendingRevision++
        currentOnSeek(target)
        return true
    }

    fun step(forward: Boolean): Boolean {
        if (!currentSeekable) return false
        val at = (pending ?: positionMs.value).coerceIn(0L, currentDuration)
        val target = if (forward) {
            if (at >= currentDuration - KeyStepMs) currentDuration else at + KeyStepMs
        } else (at - KeyStepMs).coerceAtLeast(0L)
        return seekTo(target.toFloat())
    }

    val accessibility = if (interactive && fullyShown) Modifier.semantics {
        contentDescription = description
        val range = 0f..durationMs.coerceAtLeast(0L).toFloat()
        val shown = (scrubbing?.times(range.endInclusive) ?: (pending ?: positionMs.value).toFloat()).coerceIn(range)
        progressBarRangeInfo = ProgressBarRangeInfo(shown, range)
        stateDescription = valuePattern.format(FluidScrubbing.Elapsed(shown, range), FluidScrubbing.Total(shown, range))
        if (seekable) {
            setProgress { seekTo(it) }
            customActions = listOf(
                CustomAccessibilityAction(backLabel) { step(false) },
                CustomAccessibilityAction(forwardLabel) { step(true) },
            )
        } else disabled()
    } else Modifier.clearAndSetSemantics {}

    Box(
        modifier
            .fillMaxWidth()
            .then(accessibility)
            .onKeyEvent { event ->
                if (!currentSeekable || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionRight -> step(true)
                    Key.DirectionLeft -> step(false)
                    Key.MoveHome -> seekTo(0f)
                    Key.MoveEnd -> seekTo(currentDuration.toFloat())
                    else -> false // In particular, vertical keys must remain available to leave the control.
                }
            }
            .focusable(seekable, focusInteraction)
            // The touch target is taller than the bar. A 6dp-high capsule is
            // accurate to look at and almost impossible to grab. Folded, the
            // target folds with it: a hairline is not for touching.
            .layout { measurable, constraints ->
                val height = lerpPx(HairlineHeight.toPx(), TouchHeight.toPx(), amount().coerceIn(0f, 1f))
                    .roundToInt().coerceAtLeast(if (seekable) TouchHeight.roundToPx() else 0)
                val placeable = measurable.measure(
                    constraints.copy(minHeight = height, maxHeight = height),
                )
                layout(placeable.width, height) { placeable.place(0, 0) }
            }
            .pointerInput(durationMs, seekable, resetKey) {
                if (!seekable) return@pointerInput
                detectTapGestures { offset ->
                    if (size.width > 0) {
                        seekTo((offset.x / size.width).coerceIn(0f, 1f) * currentDuration)
                    }
                }
            }
            .pointerInput(durationMs, seekable, resetKey) {
                if (!seekable) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        scrubbing = (offset.x / size.width).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        scrubbing?.let { seekTo(it * currentDuration) }
                        scrubbing = null
                    },
                    onDragCancel = { scrubbing = null },
                ) { change, _ ->
                    change.consume()
                    scrubbing = (change.position.x / size.width).coerceIn(0f, 1f)
                }
            }
            // Drawn rather than laid out, so the ticking position redraws the
            // bar and recomposes nothing.
            .drawBehind {
                val a = amount().coerceIn(0f, 1f)
                val thickness = lerpPx(HairlineHeight.toPx(), TrackHeight.toPx(), a)
                val top = (size.height - thickness) / 2f
                val radius = CornerRadius(thickness / 2f)
                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(0f, top),
                    size = Size(size.width, thickness),
                    cornerRadius = radius,
                )
                val progress = scrubbing ?: progressOf(pending ?: positionMs.value, durationMs)
                val filled = size.width * progress
                if (filled > 0f) {
                    drawRoundRect(
                        color = accentColor,
                        topLeft = Offset(0f, top),
                        size = Size(filled, thickness),
                        cornerRadius = radius,
                    )
                }
                if (focused && seekable) {
                    val ring = 2.dp.toPx()
                    drawRoundRect(color = accentColor, topLeft = Offset(ring / 2f, ring / 2f),
                        size = Size((size.width - ring).coerceAtLeast(0f), (size.height - ring).coerceAtLeast(0f)),
                        cornerRadius = CornerRadius((size.height - ring).coerceAtLeast(0f) / 2f), style = Stroke(ring))
                }
            },
    )
}

/** The bar as the panel has always drawn it beside a page: a line, not a control. */
private val HairlineHeight = 3.dp

/** The capsule itself. */
private val TrackHeight = 6.dp

/** And the room around it that a finger can find. */
private val TouchHeight = 48.dp

private const val KeyStepMs = 10_000L
