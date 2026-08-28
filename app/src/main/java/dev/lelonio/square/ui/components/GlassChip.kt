package dev.lelonio.square.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lelonio.square.ui.glass.shapes.ContinuousCapsule
import dev.lelonio.square.ui.glass.pressable
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim

/**
 * A filter chip in the app's own material.
 *
 * The engine's chip is a flat Material pill: right anywhere, and the one
 * control at the top of Home and Library that did not look like the rest of
 * this app. This is the same capsule, the same film and the same hairline rim
 * as the buttons beside it, with the chosen one lit by a wash of the accent.
 *
 * ### Painted, not sampled
 *
 * Deliberately not a `LiquidButton`. Tried that first, and a row of four real
 * glass panes at the top of a scrolling page took the library from thirty per
 * cent janky frames to sixty, with the fiftieth percentile going from 25 ms to
 * 40 — every fling re-photographing and re-blurring the screen four times a
 * frame.
 *
 * Nothing is lost by painting it. What sits behind these chips is the page's
 * own flat wash, and over a flat colour there is nothing for the refraction to
 * bend: the film and the rim are the whole of what says "glass", exactly as the
 * round buttons on the detail page say in their own comment. So this is the
 * same picture, drawn instead of computed.
 */
@Composable
fun GlassChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // One number drives the fill, the rim and the ink, so they can never
    // disagree about how far through the change they are.
    val lit by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(200),
        label = "chip",
    )

    val accent = MaterialTheme.colorScheme.primary
    val shape = ContinuousCapsule()

    Box(
        modifier
            .defaultMinSize(minHeight = 44.dp)
            .clip(shape)
            .background(lerp(FILM, accent.copy(alpha = LIT_ALPHA), lit))
            .border(0.8.dp, lerp(RIM, accent.copy(alpha = 0.55f), lit), shape)
            .pressable(onClick, shape, pressedScale = 0.96f)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = lerp(InkDim, Ink, lit),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The film every glass surface in the app is washed with. */
private val FILM = Color.White.copy(alpha = 0.08f)

/** The hairline that catches the light along the edge. */
private val RIM = Color.White.copy(alpha = 0.20f)

/** Enough accent to be unmistakable, little enough to still be the material. */
private const val LIT_ALPHA = 0.42f
