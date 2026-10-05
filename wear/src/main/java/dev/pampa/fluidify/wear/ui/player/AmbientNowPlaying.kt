package dev.pampa.fluidify.wear.ui.player

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.pampa.fluidify.wear.link.ArtStore
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import dev.antigravity.fluidengine.wear.ambient.LocalFluidWearAmbient
import dev.antigravity.fluidengine.wear.ambient.fluidBurnInShift
import dev.pampa.fluidify.wear.R
import dev.pampa.fluidify.wear.playback.NowPlaying
import dev.pampa.fluidify.wear.WearApp
import java.util.Date

/** One sparse, opaque ambient surface for every route, including low-bit displays. */
@Composable fun AmbientNowPlaying(now: NowPlaying, modifier: Modifier = Modifier, art: ArtStore? = null) {
    val ambient = LocalFluidWearAmbient.current
    val tick = ambient.updateTick
    val context = LocalContext.current
    val time = remember(tick, ambient.isAmbient) { DateFormat.getTimeFormat(context).format(Date()) }
    val textColor = if (ambient.lowBitAmbient) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black)) {
        val compact = maxWidth < 210.dp
        val density = androidx.compose.ui.platform.LocalDensity.current
        val tight = compact && density.fontScale >= 1.2f
        Column(Modifier.fillMaxWidth(0.72f).align(Alignment.TopCenter)
            .padding(top = if (tight) 4.dp else dev.antigravity.fluidengine.wear.theme.FluidWearDimens.TimePillTop)
            .fluidBurnInShift(ambient), horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
                androidx.compose.ui.unit.Density(density.density, if (tight) 1f else density.fontScale)) {
                Text(time, style = MaterialTheme.typography.labelSmall, color = textColor,
                    modifier = Modifier.padding(horizontal = dev.antigravity.fluidengine.wear.theme.FluidWearDimens.TimePillPaddingHorizontal,
                        vertical = dev.antigravity.fluidengine.wear.theme.FluidWearDimens.TimePillPaddingVertical))
            }
            Spacer(Modifier.height(if (tight) 2.dp else if (compact) 4.dp else 6.dp))
            Column(Modifier.fillMaxWidth().padding(vertical = dev.antigravity.fluidengine.wear.theme.FluidWearDimens.CapsulePaddingVertical),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(now.snapshot?.track?.title ?: stringResource(R.string.nothing_playing),
                    style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                    color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                if (!compact) now.snapshot?.track?.artist?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyExtraSmall, color = textColor,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
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
                AsyncImage(ImageRequest.Builder(context).data(it).crossfade(false).build(),
                    contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.align(Alignment.Center).size(if (compact) 52.dp else 64.dp)
                        .fluidBurnInShift(ambient).clip(RoundedCornerShape(8.dp)).alpha(.22f))
            }
        }
    }
}
