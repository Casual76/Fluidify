package dev.pampa.fluidify.wear.ui.sheets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import dev.pampa.fluidify.wear.link.ArtStore
import dev.pampa.fluidify.wear.playback.PlaybackControls
import dev.pampa.fluidify.wear.protocol.QueueWindow
import dev.pampa.fluidify.wear.ui.common.Thumb
import dev.pampa.fluidify.wear.ui.common.WatchList
import dev.pampa.fluidify.wear.ui.common.failedItem
import dev.pampa.fluidify.wear.ui.common.loadingItem
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
    var window by remember { mutableStateOf<QueueWindow?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        failed = false
        load().fold(onSuccess = { window = it }, onFailure = { failed = true })
    }
    val loadingText = stringResource(R.string.loading)
    val failedText = stringResource(R.string.couldnt_load)
    val retryText = stringResource(R.string.retry)
    val emptyText = stringResource(R.string.queue_empty)
    WatchList(title = stringResource(R.string.queue)) {
        val current = window
        when {
            current == null && failed -> failedItem(failedText, retryText) { attempt++ }
            current == null -> loadingItem(loadingText)
            current.items.isEmpty() -> noticeItem(emptyText)
            else -> {
                // What is playing, then what comes after it. The songs already played stay out:
                // the window carries a few for the phone's sake, and on a watch they are only
                // rows to scroll past before reaching the point of the screen.
                val playing = current.items.firstOrNull { it.index == current.currentIndex }
                // After the current one in the order given, which is the order of play: with
                // shuffle on, that is not the order of the list.
                val upcoming = current.items.dropWhile { it.index != current.currentIndex }.drop(1)
                    .ifEmpty { current.items.filter { it.index > current.currentIndex && playing == null } }
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
