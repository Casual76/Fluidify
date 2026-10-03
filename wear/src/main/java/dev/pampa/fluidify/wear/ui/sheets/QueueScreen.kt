package dev.pampa.fluidify.wear.ui.sheets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.Text
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
            else -> {
                // What is playing, then what comes after it. The songs already played stay out:
                // the window carries a few for the phone's sake, and on a watch they are only
                // rows to scroll past before reaching the point of the screen.
                val playing = current.items.firstOrNull { it.index == current.currentIndex }
                val upcoming = current.items.filter { it.index > current.currentIndex }
                if (playing != null) {
                    item { ListSubHeader { Text(stringResource(R.string.now_playing)) } }
                    item(key = "q${playing.index}") {
                        FluidWearListRow(
                            title = playing.title,
                            subtitle = playing.artist,
                            selected = true,
                            onClick = onPlayed,
                            leading = { Thumb(playing.artKey, playing.artUrl, art, PhosphorIcons.Regular.MusicNote) },
                        )
                    }
                }
                if (upcoming.isNotEmpty()) {
                    item { ListSubHeader { Text(stringResource(R.string.up_next)) } }
                    upcoming.forEach { entry ->
                        item(key = "q${entry.index}") {
                            FluidWearListRow(
                                title = entry.title,
                                subtitle = entry.artist,
                                onClick = {
                                    controls.playQueueIndex(entry.index, entry.uri)
                                    onPlayed()
                                },
                                leading = { Thumb(entry.artKey, entry.artUrl, art, PhosphorIcons.Regular.MusicNote) },
                            )
                        }
                    }
                }
            }
        }
    }
}
