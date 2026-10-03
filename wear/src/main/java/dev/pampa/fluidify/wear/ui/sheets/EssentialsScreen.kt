package dev.pampa.fluidify.wear.ui.sheets

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Icon
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Disc
import com.adamglin.phosphoricons.regular.Queue
import com.adamglin.phosphoricons.regular.Radio
import com.adamglin.phosphoricons.regular.Timer
import com.adamglin.phosphoricons.regular.User
import dev.antigravity.fluidengine.wear.components.FluidWearListRow
import dev.pampa.fluidify.wear.R
import dev.pampa.fluidify.wear.playback.PlaybackControls
import dev.pampa.fluidify.wear.ui.common.WatchList

/**
 * Spotify's "essentials" for the song on screen: its queue, the sleep timer, a
 * radio from it, and the way to its artist and album. Opened by tapping the
 * title.
 */
@Composable
fun EssentialsScreen(
    controls: PlaybackControls,
    onQueue: () -> Unit,
    onSleep: () -> Unit,
    onOpenContext: (uri: String) -> Unit,
    onRadio: () -> Unit,
) {
    val now by controls.nowPlaying.collectAsStateWithLifecycle()
    val track = now.snapshot?.track
    WatchList(title = track?.title ?: stringResource(R.string.more)) {
        item {
            FluidWearListRow(
                title = stringResource(R.string.queue),
                onClick = onQueue,
                leading = { Icon(PhosphorIcons.Regular.Queue, contentDescription = null, modifier = Modifier.size(22.dp)) },
            )
        }
        item {
            FluidWearListRow(
                title = stringResource(R.string.sleep_timer),
                onClick = onSleep,
                leading = { Icon(PhosphorIcons.Regular.Timer, contentDescription = null, modifier = Modifier.size(22.dp)) },
            )
        }
        if (track?.uri?.startsWith("spotify:track:") == true) {
            item {
                FluidWearListRow(
                    title = stringResource(R.string.radio),
                    onClick = {
                        controls.startRadio()
                        onRadio()
                    },
                    leading = { Icon(PhosphorIcons.Regular.Radio, contentDescription = null, modifier = Modifier.size(22.dp)) },
                )
            }
        }
        track?.artistUri?.let { artist ->
            item {
                FluidWearListRow(
                    title = stringResource(R.string.go_to_artist),
                    subtitle = track.artist,
                    onClick = { onOpenContext(artist) },
                    leading = { Icon(PhosphorIcons.Regular.User, contentDescription = null, modifier = Modifier.size(22.dp)) },
                )
            }
        }
        track?.albumUri?.let { album ->
            item {
                FluidWearListRow(
                    title = stringResource(R.string.go_to_album),
                    subtitle = track.album,
                    onClick = { onOpenContext(album) },
                    leading = { Icon(PhosphorIcons.Regular.Disc, contentDescription = null, modifier = Modifier.size(22.dp)) },
                )
            }
        }
    }
}
