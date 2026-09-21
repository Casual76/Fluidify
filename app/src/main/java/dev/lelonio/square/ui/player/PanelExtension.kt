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
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
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
    val queueState = rememberLazyListState()
    var reordering by remember { mutableStateOf(false) }
    val nudge = with(LocalDensity.current) { QueueNudge.toPx() }

    // Whether the queue is the thing being read.
    //
    // A fact and not a number of pixels: a list pushed off its top is a list
    // somebody is looking through, and what they are not looking at is the
    // words above it. A nudge of slack so that a finger resting on the list, or
    // a fling that ends one pixel short of home, does not count as reading it.
    val away by remember(nudge) {
        derivedStateOf {
            queueState.firstVisibleItemIndex > 0 ||
                queueState.firstVisibleItemScrollOffset > nudge
        }
    }
    val share by animateFloatAsState(
        targetValue = if (away) 0f else LyricsShare,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "extensionSplit",
    )

    // And home again by itself, once nobody is asking anything of it.
    //
    // The queue takes the column on the way down and has to give it back on the
    // way up, and "the way up" cannot only mean scrolling to the top: a list
    // left halfway is a panel that has quietly lost its words. So the top is
    // where it goes when it is put down -- which is the same gesture as putting
    // it down, and needs no control.
    //
    // Not while a row is in somebody's hand, which is the one case where the
    // list moving on its own would be the list fighting the finger: a drag does
    // not scroll, so [LazyListState.isScrollInProgress] cannot see it, and the
    // list says so itself.
    LaunchedEffect(away, reordering, queueState.isScrollInProgress) {
        if (!away || reordering || queueState.isScrollInProgress) return@LaunchedEffect
        delay(QueueIdleReturnMs)
        queueState.animateScrollToItem(0)
    }

    ExtensionSplit(
        share = { share },
        words = {
            Box(Modifier.fillMaxSize().clipToBounds()) {
                LyricsStage(
                    lyrics = lyrics,
                    loading = lyricsLoading,
                    positionMs = positionMs,
                    isPlaying = isPlaying,
                    onSeek = onSeek,
                    backdrop = backdrop,
                    expandSignal = 0,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        },
        heading = { ExtensionHeading(stringResource(R.string.queue)) },
        queue = {
            QueueList(
                queue = queue,
                onPlay = onPlayQueueItem,
                onRemove = onRemoveQueueItem,
                onMove = onMoveQueueItem,
                listState = queueState,
                onReorderingChange = { reordering = it },
            )
        },
    )
}

/**
 * The words and the queue sharing one column, on a number that moves.
 *
 * A layout rather than two weights, and the reason is the number: a weight has
 * to be greater than zero, and the whole of what this is for is the queue taking
 * *all* of the column. Read in measure, so the spring that hands the room over
 * does not recompose either half while it runs -- the words are a stage winding
 * a clock of their own and the queue is a list that may be holding a drag.
 *
 * The words fade as their room goes rather than only being cropped: a line of
 * lyric squeezed to nothing is a line cut in half, and half a letter is worse
 * than none.
 */
@Composable
private fun ExtensionSplit(
    /** How much of the column the words take, 0 to [LyricsShare]. */
    share: () -> Float,
    words: @Composable () -> Unit,
    heading: @Composable () -> Unit,
    queue: @Composable () -> Unit,
) {
    Layout(
        contents = listOf(words, heading, queue),
        modifier = Modifier
            .fillMaxSize()
            .padding(top = ExtensionTop),
    ) { slots, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val f = share().coerceIn(0f, 1f)

        val head = slots[1].first().measure(
            Constraints(minWidth = width, maxWidth = width, minHeight = 0, maxHeight = height),
        )
        val room = (height - head.height).coerceAtLeast(0)
        val wordsH = (room * f).roundToInt().coerceIn(0, room)
        val queueH = (room - wordsH).coerceAtLeast(0)

        val wordsP = slots[0].first().measure(Constraints.fixed(width, wordsH))
        val queueP = slots[2].first().measure(Constraints.fixed(width, queueH))

        layout(width, height) {
            wordsP.placeWithLayer(0, 0) {
                alpha = (f / (LyricsShare * WordsFadeOut)).coerceIn(0f, 1f)
            }
            head.place(0, wordsH)
            queueP.place(0, wordsH + head.height)
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

/** How much of the height the words take at rest; the queue has the rest. */
private const val LyricsShare = 0.55f

/**
 * How far into its own share the words have faded out.
 *
 * Four tenths: by the time the queue has taken a little over half of what the
 * words had, the words are gone. They go before their room does on purpose -- a
 * stage of lyrics cropped to two lines reads as a mistake, where an empty band
 * closing reads as room being handed over.
 */
private const val WordsFadeOut = 0.4f

/** Slack before a scrolled list counts as one somebody is reading. */
private val QueueNudge = 8.dp

/**
 * How long the queue is left alone before it goes home and folds back.
 *
 * Long enough to read what is coming without the list moving under the eyes,
 * short enough that a panel left open comes back to itself rather than staying
 * where the last flick left it.
 */
private const val QueueIdleReturnMs = 8000L

/** The air above the words: the button at the panel's corner stands in it. */
private val ExtensionTop = 56.dp

/** How many lines either side of the sung one the still draws, and how many rows of queue. */
private const val StillLinesAround = 3
private const val StillQueueRows = 8
