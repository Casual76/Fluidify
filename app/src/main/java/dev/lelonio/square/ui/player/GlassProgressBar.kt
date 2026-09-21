package dev.lelonio.square.ui.player

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
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
) {
    var scrubbing by remember { mutableStateOf<Float?>(null) }

    Box(
        modifier
            .fillMaxWidth()
            // The touch target is taller than the bar. A 6dp-high capsule is
            // accurate to look at and almost impossible to grab. Folded, the
            // target folds with it: a hairline is not for touching.
            .layout { measurable, constraints ->
                val height = lerpPx(HairlineHeight.toPx(), TouchHeight.toPx(), amount().coerceIn(0f, 1f))
                    .roundToInt()
                val placeable = measurable.measure(
                    constraints.copy(minHeight = height, maxHeight = height),
                )
                layout(placeable.width, height) { placeable.place(0, 0) }
            }
            .pointerInput(durationMs, interactive) {
                if (!interactive) return@pointerInput
                detectTapGestures { offset ->
                    if (durationMs > 0) {
                        onSeek(((offset.x / size.width).coerceIn(0f, 1f) * durationMs).toLong())
                    }
                }
            }
            .pointerInput(durationMs, interactive) {
                if (!interactive) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        scrubbing = (offset.x / size.width).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        scrubbing?.let { onSeek((it * durationMs).toLong()) }
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
                val progress = scrubbing ?: progressOf(positionMs.value, durationMs)
                val filled = size.width * progress
                if (filled > 0f) {
                    drawRoundRect(
                        color = accentColor,
                        topLeft = Offset(0f, top),
                        size = Size(filled, thickness),
                        cornerRadius = radius,
                    )
                }
            },
    )
}

/** The bar as the panel has always drawn it beside a page: a line, not a control. */
private val HairlineHeight = 3.dp

/** The capsule itself. */
private val TrackHeight = 6.dp

/** And the room around it that a finger can find. */
private val TouchHeight = 28.dp
