package dev.pampa.fluidify.wear.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.min
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.fill.SpeakerHigh
import com.adamglin.phosphoricons.fill.SpeakerLow
import com.adamglin.phosphoricons.fill.SpeakerX
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.wear.components.FluidEdgeLevelArc
import dev.antigravity.fluidengine.wear.glass.FluidGlassBadge
import dev.antigravity.fluidengine.wear.theme.FluidWearDimens
import dev.pampa.fluidify.wear.R
import kotlin.math.roundToInt

/**
 * The volume, while the bezel turns.
 *
 * A line of light on the right edge of the screen — the same stroke and halo as the progress ring,
 * on the side of the bezel the thumb is on — and the number, with the device it is the volume of,
 * on a disc of glass in the middle while the controls behind it step back. The disc is big, more
 * than half the screen: it is there for the second or two the bezel turns, and a big lens over the
 * cover is the one moment the glass gets to be seen whole. An indicator over the player rather
 * than a page, so it is allowed to arrive and leave softly.
 *
 * @param device whose volume this is ("Pixel 9", "Living room"); null says nothing.
 */
@Composable
fun VolumeOverlay(
    visible: Boolean,
    level: Float,
    backdrop: GlassBackdropState,
    modifier: Modifier = Modifier,
    device: String? = null,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(FluidMotion.fadeIn()),
        exit = fadeOut(FluidMotion.fadeOut()),
        modifier = modifier.fillMaxSize(),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val diameter = min(maxWidth, maxHeight) * DiscFraction
            FluidEdgeLevelArc(level = level)
            FluidGlassBadge(
                backdrop = backdrop,
                size = diameter,
                modifier = Modifier
                    .align(Alignment.Center)
                    .animateEnterExit(enter = scaleIn(FluidMotion.snappy(), initialScale = CardEnterScale), exit = scaleOut(targetScale = CardEnterScale)),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    // The device's name is the widest line; inside the circle, not across its rim.
                    modifier = Modifier.widthIn(max = diameter * TextFraction),
                ) {
                    Icon(
                        imageVector = when {
                            level <= 0f -> PhosphorIcons.Fill.SpeakerX
                            level < LowVolume -> PhosphorIcons.Fill.SpeakerLow
                            else -> PhosphorIcons.Fill.SpeakerHigh
                        },
                        contentDescription = null,
                        modifier = Modifier.size(FluidWearDimens.IconLarge),
                    )
                    Text(
                        text = stringResource(R.string.volume_percent, (level.coerceIn(0f, 1f) * 100).roundToInt()),
                        style = MaterialTheme.typography.numeralMedium,
                        maxLines = 1,
                    )
                    if (!device.isNullOrEmpty()) {
                        Text(
                            text = device,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private const val LowVolume = 0.4f

/** The disc's diameter against the screen's. */
private const val DiscFraction = 0.58f

/** How much of the disc's width the text may take: inside the curve at the height it sits at. */
private const val TextFraction = 0.8f
private const val CardEnterScale = 0.88f
