package dev.pampa.fluidify.wear.ui.sheets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.MusicNote
import dev.antigravity.fluidengine.wear.components.FluidWearListRow
import dev.pampa.fluidify.wear.R
import androidx.compose.ui.platform.LocalContext
import dev.pampa.fluidify.wear.link.ArtStore
import dev.pampa.fluidify.wear.playback.PlaybackControls
import dev.pampa.fluidify.wear.protocol.QueueWindow
import dev.pampa.fluidify.wear.ui.common.Thumb
import dev.pampa.fluidify.wear.ui.common.WatchList
import dev.pampa.fluidify.wear.ui.common.noticeItem

/**
 * What plays next, asked of the phone when the screen opens. A tap jumps there.
 *
 * Read-only on purpose: reordering a queue with a finger on a 200 dp circle is
 * the kind of thing that works in a demo and nowhere else, and the phone does it
 * well.
 */
@Composable
fun QueueScreen(
    controls: PlaybackControls,
    art: ArtStore,
    load: suspend () -> Result<QueueWindow>,
    onPlayed: () -> Unit,
) {
    val context = LocalContext.current
    var window by remember { mutableStateOf<QueueWindow?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        load().fold(onSuccess = { window = it }, onFailure = { failed = true })
    }
    WatchList(title = stringResource(R.string.queue)) {
        val current = window
        when {
            current == null && failed -> noticeItem(context.getString(R.string.couldnt_load))
            current == null -> noticeItem(context.getString(R.string.loading))
            current.items.isEmpty() -> noticeItem(context.getString(R.string.queue_empty))
            else -> current.items.forEach { entry ->
                item(key = "q${entry.index}") {
                    FluidWearListRow(
                        title = entry.title,
                        subtitle = if (entry.index == current.currentIndex) stringResource(R.string.now_playing) else entry.artist,
                        onClick = {
                            controls.playQueueIndex(entry.index, entry.uri)
                            onPlayed()
                        },
                        leading = {
                            Thumb(entry.artKey, entry.artUrl, art, PhosphorIcons.Regular.MusicNote)
                        },
                    )
                }
            }
        }
    }
}
