package dev.pampa.fluidify.wear.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import kotlin.math.min

/**
 * The volume, drawn along the right edge while the bezel turns.
 *
 * An arc of 90 degrees centred at three o'clock, the side of the bezel the thumb
 * is on, filling from the bottom. It is an indicator over the player, not a page,
 * so it is allowed to appear and leave softly.
 */
@Composable
fun VolumeOverlay(visible: Boolean, level: Float, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
    val fill = MaterialTheme.colorScheme.primary
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(FluidMotion.fadeIn()),
        exit = fadeOut(FluidMotion.fadeOut()),
        modifier = modifier.fillMaxSize(),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 6.dp.toPx()
            val radius = min(size.width, size.height) / 2f - 14.dp.toPx()
            val topLeft = Offset(center.x - radius, center.y - radius)
            val arc = Size(radius * 2, radius * 2)
            // Bottom of the arc at 45° below three o'clock, top at 45° above.
            drawArc(track, startAngle = 45f, sweepAngle = -90f, useCenter = false, topLeft = topLeft, size = arc, style = Stroke(stroke, cap = StrokeCap.Round))
            val sweep = -90f * level.coerceIn(0f, 1f)
            if (sweep < 0f) {
                drawArc(fill, startAngle = 45f, sweepAngle = sweep, useCenter = false, topLeft = topLeft, size = arc, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
    }
}
