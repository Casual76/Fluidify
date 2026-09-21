package dev.lelonio.square.ui.player

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import kotlin.math.roundToInt

/**
 * Folds and growths read in the measure pass.
 *
 * Every one of these takes a lambda rather than a number, and that is the whole
 * idea: the number is a spring or a finger, and read in composition it would
 * recompose the panel — and the app around it — on every frame it moves. Read
 * in `layout` it costs a re-layout of the nodes it touches and nothing else,
 * which is the discipline `NowPlayingPanel` already keeps for its picture.
 */

/**
 * Reports a fraction of the height it measured, and fades with it.
 *
 * Clipped, so what is folded away is not drawn over whatever comes next: the
 * row is measured whole and shown from the top down, like a blind.
 */
internal fun Modifier.foldHeight(fraction: () -> Float): Modifier = this
    .clipToBounds()
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val shown = fraction().coerceIn(0f, 1f)
        layout(placeable.width, (placeable.height * shown).roundToInt()) {
            placeable.placeWithLayer(0, 0) { alpha = shown }
        }
    }

/** A child measured at a size between two, by a fraction. For the glyph inside a disc that grows. */
internal fun Modifier.grow(from: Dp, to: Dp, fraction: () -> Float): Modifier = layout { measurable, constraints ->
    val side = lerpPx(from.toPx(), to.toPx(), fraction().coerceIn(0f, 1f)).roundToInt()
    val placeable = measurable.measure(constraints.constrainTo(side))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/** Room taken off the start of a row, by a number that moves. */
internal fun Modifier.startInset(px: () -> Float): Modifier = layout { measurable, constraints ->
    val inset = px().coerceAtLeast(0f).roundToInt()
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = (constraints.minWidth - inset).coerceAtLeast(0),
            maxWidth = (constraints.maxWidth - inset).coerceAtLeast(0),
        ),
    )
    layout(placeable.width + inset, placeable.height) { placeable.place(inset, 0) }
}

private fun Constraints.constrainTo(side: Int): Constraints {
    val w = side.coerceIn(minWidth, maxWidth)
    val h = side.coerceIn(minHeight, maxHeight)
    return Constraints.fixed(w, h)
}

internal fun lerpPx(from: Float, to: Float, t: Float): Float = from + (to - from) * t
