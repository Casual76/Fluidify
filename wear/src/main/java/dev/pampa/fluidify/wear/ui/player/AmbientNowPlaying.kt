package dev.pampa.fluidify.wear.ui.player

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import java.util.Date

/** One sparse, opaque ambient surface for every route, including low-bit displays. */
@Composable fun AmbientNowPlaying(now: NowPlaying, modifier: Modifier = Modifier) {
    val ambient = LocalFluidWearAmbient.current
    val tick = ambient.updateTick
    val context = LocalContext.current
    val time = remember(tick, ambient.isAmbient) { DateFormat.getTimeFormat(context).format(Date()) }
    val textColor = if (ambient.lowBitAmbient) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    Box(modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth(0.7f).fluidBurnInShift(ambient), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(time, style = MaterialTheme.typography.labelMedium, color = textColor)
            Text(now.snapshot?.track?.title ?: stringResource(R.string.nothing_playing), style = MaterialTheme.typography.bodySmall,
                color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            now.snapshot?.track?.artist?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyExtraSmall, color = textColor,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
    }
}
