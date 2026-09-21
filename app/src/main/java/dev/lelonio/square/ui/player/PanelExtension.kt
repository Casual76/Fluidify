package dev.lelonio.square.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lelonio.square.R
import dev.lelonio.square.data.Lyrics
import dev.lelonio.square.ui.glass.backdrop.Backdrop
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import kotlin.math.abs

/**
 * The words and the queue, opened out beside the now-playing panel.
 *
 * The third size of the panel on a wide window: the column stays what it was,
 * and this stands to its left. Words above, queue below, both there at once —
 * the one being sung and the ones coming next are the two things a glance at
 * a player is for, and a control to choose between them would be a control
 * for nothing.
 *
 * Both halves are the full player's own: the words are [LyricsStage], with
 * the translation and the karaoke dial it carries, and the queue is
 * [QueueList], reorderable by its handles. Nothing here is a smaller drawing
 * of either.
 */
@Composable
internal fun PanelExtension(
    lyrics: Lyrics?,
    lyricsLoading: Boolean,
    positionMs: State<Long>,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    backdrop: Backdrop,
    queue: List<QueueEntry>,
    onPlayQueueItem: (Int) -> Unit,
    onRemoveQueueItem: (Int) -> Unit,
    onMoveQueueItem: (from: Int, to: Int) -> Unit,
) {
    ExtensionColumn {
        LyricsStage(
            lyrics = lyrics,
            loading = lyricsLoading,
            positionMs = positionMs,
            isPlaying = isPlaying,
            onSeek = onSeek,
            backdrop = backdrop,
            expandSignal = 0,
            modifier = Modifier
                .weight(LyricsShare)
                .fillMaxWidth(),
        )
        ExtensionHeading(stringResource(R.string.queue))
        Box(
            Modifier
                .weight(1f - LyricsShare)
                .fillMaxWidth()
                .clipToBounds(),
        ) {
            QueueList(
                queue = queue,
                onPlay = onPlayQueueItem,
                onRemove = onRemoveQueueItem,
                onMove = onMoveQueueItem,
            )
        }
    }
}

/**
 * The same, as a picture, for the copy that rides inside the travelling window.
 *
 * The copy lives for the first third of the morph and is scaled and faded
 * while it does, and the live halves are the wrong thing to put in it: the
 * words wind a frame clock of their own and draw through an offscreen layer,
 * the queue holds a drag, and neither would share what the real ones are
 * showing — the translation is the real stage's. So the lines around the one
 * being sung, read once, and the first few rows of the queue, drawn by the row
 * itself. Still, and exactly the shape of what it stands in for.
 */
@Composable
internal fun PanelExtensionStill(
    lyrics: Lyrics?,
    positionMs: State<Long>,
    queue: List<QueueEntry>,
) {
    ExtensionColumn {
        LyricsStill(
            lyrics = lyrics,
            positionMs = positionMs,
            modifier = Modifier
                .weight(LyricsShare)
                .fillMaxWidth(),
        )
        ExtensionHeading(stringResource(R.string.queue))
        Column(
            Modifier
                .weight(1f - LyricsShare)
                .fillMaxWidth()
                .clipToBounds()
                .padding(vertical = 8.dp),
        ) {
            if (queue.isEmpty()) {
                EmptyPanel(stringResource(R.string.queue_empty))
            }
            queue.take(StillQueueRows).forEach { entry ->
                QueueRow(entry = entry, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * The lines around the one being sung, as they stand on the stage, unmoving.
 *
 * Read once: the copy exists for a few frames and a line that turned over
 * inside it would be a line turning over in a photograph.
 */
@Composable
private fun LyricsStill(
    lyrics: Lyrics?,
    positionMs: State<Long>,
    modifier: Modifier = Modifier,
) {
    val active = remember(lyrics) {
        val at = positionMs.value
        if (lyrics == null || !lyrics.synced) -1
        else lyrics.lines.indexOfLast { (it.startTimeMs ?: 0L) <= at }
    }
    Column(
        modifier.padding(vertical = 40.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        val lines = lyrics?.lines.orEmpty()
        if (lines.isEmpty()) return@Column
        val centre = if (active < 0) 0 else active
        val from = (centre - StillLinesAround).coerceAtLeast(0)
        val to = (centre + StillLinesAround).coerceAtMost(lines.lastIndex)
        // The stage's own type and fall-off; see LyricRow.
        val style = MaterialTheme.typography.titleLarge.copy(fontSize = 27.sp, lineHeight = 33.sp)
        for (index in from..to) {
            val distance = if (active < 0) 0 else abs(index - active)
            val alpha = when {
                active < 0 || index == active -> 1f
                else -> (0.62f - distance * 0.12f).coerceAtLeast(0.22f)
            }
            Text(
                text = lines[index].text,
                style = style,
                color = Ink.copy(alpha = alpha),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 9.dp),
            )
        }
    }
}

/** The extension's frame: room at the top for the button standing in its corner. */
@Composable
private fun ExtensionColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(top = ExtensionTop),
        content = content,
    )
}

/** A word over the queue, so the two halves read as two things. */
@Composable
private fun ExtensionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = InkDim,
        maxLines = 1,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 2.dp),
    )
}

/** How much of the height the words take; the queue has the rest. */
private const val LyricsShare = 0.55f

/** The air above the words: the button at the panel's corner stands in it. */
private val ExtensionTop = 56.dp

/** How many lines either side of the sung one the still draws, and how many rows of queue. */
private const val StillLinesAround = 3
private const val StillQueueRows = 8
