package dev.pampa.fluidify.wear.ui.player

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.antigravity.fluidengine.wear.ambient.LocalFluidWearAmbient
import dev.antigravity.fluidengine.wear.ambient.fluidBurnInShift
import dev.antigravity.fluidengine.wear.theme.FluidWearDimens
import dev.pampa.fluidify.wear.R
import dev.pampa.fluidify.wear.WearApp
import dev.pampa.fluidify.wear.link.ArtStore
import dev.pampa.fluidify.wear.playback.NowPlaying
import dev.pampa.fluidify.wear.ui.theme.WearDimens
import java.util.Date

/** One sparse, opaque ambient surface for every route, including low-bit displays. */
@Composable
fun AmbientNowPlaying(now: NowPlaying, modifier: Modifier = Modifier, art: ArtStore? = null) {
    val ambient = LocalFluidWearAmbient.current
    val tick = ambient.updateTick
    val context = LocalContext.current
    val time = remember(tick, ambient.isAmbient) { DateFormat.getTimeFormat(context).format(Date()) }
    val textColor = if (ambient.lowBitAmbient) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black)) {
        val compact = maxWidth < WearDimens.CompactScreen
        val tight = isTightClock(compact)
        Column(
            modifier = Modifier
                .fillMaxWidth(ContentWidthFraction)
                .align(Alignment.TopCenter)
                .padding(top = if (tight) WearDimens.ClockTopTight else FluidWearDimens.TimePillTop)
                .fluidBurnInShift(ambient),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PlayerClock(tight) {
                Text(
                    text = time,
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor,
                    modifier = Modifier.padding(
                        horizontal = FluidWearDimens.TimePillPaddingHorizontal,
                        vertical = FluidWearDimens.TimePillPaddingVertical,
                    ),
                )
            }
            Spacer(Modifier.height(if (tight) WearDimens.PlayerGapTight else if (compact) WearDimens.PlayerGapCompact else WearDimens.PlayerGap))
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = FluidWearDimens.CapsulePaddingVertical),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = now.snapshot?.track?.title ?: stringResource(R.string.nothing_playing),
                    style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                if (!compact) {
                    now.snapshot?.track?.artist?.takeIf { it.isNotBlank() }?.let { artist ->
                        Text(
                            text = artist,
                            style = MaterialTheme.typography.bodyExtraSmall,
                            color = textColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
        // Static, small and dim: the cover remains recognisable without lighting the whole panel.
        // No image on a low-bit panel; the title and time remain sufficient in that mode.
        if (!ambient.lowBitAmbient) {
            val store = art ?: (context.applicationContext as? WearApp)?.art
            val artKey = now.snapshot?.track?.artKey
            val cover = remember(artKey, store, tick) { store?.fileFor(artKey) }
            cover?.let {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(it).crossfade(false).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(if (compact) CompactCover else RegularCover)
                        .fluidBurnInShift(ambient)
                        .clip(RoundedCornerShape(CoverCorner))
                        .alpha(CoverAlpha),
                )
            }
        }
    }
}

/** How much of the width the time and the title may take. */
private const val ContentWidthFraction = 0.72f

private val CompactCover: Dp = 52.dp
private val RegularCover: Dp = 64.dp
private val CoverCorner: Dp = 8.dp

/** Dim enough not to light the panel. */
private const val CoverAlpha = 0.22f
