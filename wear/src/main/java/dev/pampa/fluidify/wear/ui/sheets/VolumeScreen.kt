package dev.pampa.fluidify.wear.ui.sheets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.SpeakerSimpleHigh
import com.adamglin.phosphoricons.regular.SpeakerSimpleLow
import dev.antigravity.fluidengine.ui.fluid.rememberEmptyGlassBackdrop
import dev.antigravity.fluidengine.wear.components.fluidRotarySteps
import dev.antigravity.fluidengine.wear.glass.FluidGlassDisc
import dev.antigravity.fluidengine.wear.theme.FluidWearDimens
import dev.pampa.fluidify.wear.R
import dev.pampa.fluidify.wear.playback.PlaybackControls
import dev.pampa.fluidify.wear.playback.VolumeControl
import kotlin.math.min
import kotlin.math.roundToInt

/** The volume on its own screen: the bezel turns it, and so do the two buttons. */
@Composable
fun VolumeScreen(controls: PlaybackControls, volume: VolumeControl) {
    val now by controls.nowPlaying.collectAsStateWithLifecycle()
    val level by volume.level.collectAsStateWithLifecycle()
    val remote = now.snapshot?.device?.volume
    LaunchedEffect(remote) { if (remote != null) volume.sync(remote) }
    val backdrop = rememberEmptyGlassBackdrop()
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    val fill = MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier.fillMaxSize().fluidRotarySteps(onSteps = volume::turn),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = FluidWearDimens.RingStroke.toPx() * 1.5f
            val radius = min(size.width, size.height) / 2f - 12.dp.toPx()
            val topLeft = Offset(center.x - radius, center.y - radius)
            val arc = Size(radius * 2, radius * 2)
            drawArc(track, 135f, 270f, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(fill, 135f, 270f * level, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.volume), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${(level * 100).roundToInt()}", style = MaterialTheme.typography.numeralMedium)
            Text(now.snapshot?.device?.name.orEmpty(), style = MaterialTheme.typography.bodyExtraSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                FluidGlassDisc(onClick = { volume.turn(-2) }, backdrop = backdrop, contentDescription = null, size = FluidWearDimens.DiscSmall) {
                    Icon(PhosphorIcons.Regular.SpeakerSimpleLow, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                FluidGlassDisc(onClick = { volume.turn(2) }, backdrop = backdrop, contentDescription = null, size = FluidWearDimens.DiscSmall) {
                    Icon(PhosphorIcons.Regular.SpeakerSimpleHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
