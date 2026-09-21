package dev.lelonio.square.ui.player

import com.adamglin.phosphoricons.regular.Queue
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lelonio.square.R
import dev.lelonio.square.data.Lyrics
import androidx.compose.foundation.layout.RowScope
import dev.lelonio.square.ui.components.GlassButton
import dev.antigravity.fluidengine.ui.fluid.FluidSegmentedControl
import dev.antigravity.fluidengine.ui.fluid.glassSurface
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Plus
import com.adamglin.phosphoricons.regular.SlidersHorizontal
import com.adamglin.phosphoricons.regular.TextAlignLeft
import com.adamglin.phosphoricons.fill.SlidersHorizontal
import com.adamglin.phosphoricons.fill.TextAlignLeft
import com.adamglin.phosphoricons.fill.VinylRecord
import com.adamglin.phosphoricons.regular.VinylRecord
import com.adamglin.phosphoricons.fill.Info
import com.adamglin.phosphoricons.regular.Info
import com.adamglin.phosphoricons.regular.X
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.graphicsLayer
import com.adamglin.phosphoricons.regular.DotsSixVertical
import dev.lelonio.square.ui.glass.pressable

/** Which panel is open below the transport controls. */
enum class PlayerPanel {
    NONE,
    QUEUE,
    LYRICS,

    /** Who made the track: performers, writers, producers, and the label. */
    INFO,

    /**
     * The Connect device list and the playlist picker.
     *
     * Both used to open as modals over the transport. The player already has one
     * place where a second thing is shown — the slot the cover lives in — and
     * two mechanisms for the same job meant the screen sometimes dimmed itself
     * and sometimes did not, depending on which button had been pressed.
     */
    DEVICES,
    ADD_TO_PLAYLIST,
}

@Composable
fun PlayerPanelSection(
    panel: PlayerPanel,
    onSelect: (PlayerPanel) -> Unit,
    queue: List<QueueEntry>,
    lyrics: Lyrics?,
    lyricsLoading: Boolean,
    positionMs: androidx.compose.runtime.State<Long>,
    onPlayQueueItem: (Int) -> Unit,
    onSeek: (Long) -> Unit,
    /** The layer the panel refracts; see the liquid-glass note in PlayerScreen. */
    backdrop: dev.lelonio.square.ui.glass.backdrop.Backdrop,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The same segmented control the tab bar uses, because this is the same
        // kind of choice: three views of one screen, exactly one of them
        // showing. Three separate toggles said "three independent switches",
        // which is not what cover, lyrics and effects are.
        //
        // The queue is deliberately not in here — it has its own button beside
        // the title — and neither is the information, which is one button off
        // to the side: a segmented control says "these are the views of this
        // screen", and after the effects went there are two of them.
        val karaoke by dev.lelonio.square.playback.AudioEffects.karaoke
            .collectAsStateWithLifecycle()
        val karaokeOn = karaoke > 0f

        val views = remember { listOf(PlayerPanel.NONE, PlayerPanel.LYRICS) }
        val coverLabel = stringResource(R.string.cover)
        val lyricsLabel = stringResource(R.string.lyrics)

        // The engine's segmented control, which is the same three-surface
        // arrangement the tab bar uses stood down to the size of a control: the
        // chosen view is *seen through* the lens rather than painted a different
        // colour. It arrived with a content slot in 1.33.0 for exactly this — a
        // control whose segments are glyphs and not words — and `label` stays,
        // because with a glyph in the segment it is the only thing a screen
        // reader has to go on.
        FluidSegmentedControl(
            options = views,
            // INFO is not one of the two: opening the credits leaves the switch
            // showing the cover, which is what is behind the panel anyway.
            selected = if (panel == PlayerPanel.LYRICS) PlayerPanel.LYRICS else PlayerPanel.NONE,
            onSelect = onSelect,
            modifier = Modifier.fillMaxWidth(0.46f),
            content = { option, isSelected ->
                if (option == PlayerPanel.LYRICS) {
                    PanelTab(
                        icon = PhosphorIcons.Regular.TextAlignLeft,
                        activeIcon = PhosphorIcons.Fill.TextAlignLeft,
                        label = lyricsLabel,
                        selected = isSelected,
                        // The karaoke lives in this view and keeps working with
                        // the panel shut, so the halo says so from outside it.
                        marked = karaokeOn,
                    )
                } else {
                    PanelTab(
                        icon = PhosphorIcons.Regular.VinylRecord,
                        activeIcon = PhosphorIcons.Fill.VinylRecord,
                        label = coverLabel,
                        selected = isSelected,
                    )
                }
            },
            label = { if (it == PlayerPanel.LYRICS) lyricsLabel else coverLabel },
        )

        Spacer(Modifier.width(10.dp))

        InfoButton(selected = panel == PlayerPanel.INFO) {
            // A second press closes it. The button is lit while its page is
            // showing, so pressing a lit button and having nothing happen is
            // the one thing a lit button promises will not happen.
            onSelect(if (panel == PlayerPanel.INFO) PlayerPanel.NONE else PlayerPanel.INFO)
        }
    }
}

/**
 * The way to what is behind the record: who made it, and who they are.
 *
 * A circle apart from the two-view switch beside it, on the model of the
 * search button next to the home page's tab capsule. Putting it inside the
 * switch would say it is a third way of looking at the same thing, and it is
 * not: the switch chooses between the cover and the words, this opens a page.
 */
@Composable
private fun InfoButton(
    selected: Boolean,
    onClick: () -> Unit,
) {
    GlassButton(
        onClick = onClick,
        modifier = Modifier.size(42.dp),
        contentHeight = 42.dp,
        contentPadding = 0.dp,
    ) {
        Icon(
            if (selected) PhosphorIcons.Fill.Info else PhosphorIcons.Regular.Info,
            contentDescription = stringResource(R.string.credits),
            tint = if (selected) MaterialTheme.colorScheme.primary else GlassInk,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun PanelTab(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    /** The filled cut of the same glyph, for the view being shown. */
    activeIcon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    /**
     * A light on the tab: what is behind it is doing something right now.
     *
     * Used by the effects, which are the one view whose settings go on working
     * after it is closed — a song playing a third slower with the panel shut
     * looks, from this row, exactly like a song playing normally.
     */
    marked: Boolean = false,
) {
    androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
        if (marked) {
            // A halo rather than a badge stuck to the corner: the tabs are
            // small and round-ended, and a dot on the edge of one reads as
            // damage to the capsule.
            androidx.compose.foundation.layout.Box(
                Modifier
                    .size(30.dp)
                    .background(
                        androidx.compose.ui.graphics.Brush.radialGradient(
                            listOf(GlassInk.copy(alpha = 0.28f), androidx.compose.ui.graphics.Color.Transparent),
                        ),
                        androidx.compose.foundation.shape.CircleShape,
                    ),
            )
        }
        Icon(
            // Filled rather than only brighter. The indicator behind the icon
            // moves, so at a glance the two states differed by a shade of grey
            // sliding around; a solid glyph says which view you are in without
            // being read against its neighbours.
            imageVector = if (selected) activeIcon else icon,
            contentDescription = label,
            tint = when {
                selected -> GlassInk
                marked -> GlassInk.copy(alpha = 0.85f)
                else -> GlassInkDim
            },
            modifier = Modifier.size(19.dp),
        )
    }
}

/** One row of the upcoming-tracks list. */
data class QueueEntry(
    val index: Int,
    /** What the row is, so a list that loses one can animate the rest. */
    val uri: String,
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    /** Put here by hand with "add to queue", rather than by the list. */
    val queued: Boolean,
    val isCurrent: Boolean,
)

@Composable
internal fun QueueList(
    queue: List<QueueEntry>,
    onPlay: (Int) -> Unit,
    /** Takes a track out of the queue; absent for the one playing. */
    onRemove: (Int) -> Unit,
    /** Moves a track to another place in the queue. See [QueueDragHandleWidth]. */
    onMove: (from: Int, to: Int) -> Unit = { _, _ -> },
    /** Hoisted where the caller reacts to the scroll; see [PanelExtension]. */
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
    /**
     * True while a row is in somebody's hand.
     *
     * Told to the caller because a drag is the one thing that has to stop the
     * extension putting the list back at the top by itself: a list that scrolls
     * home under a finger holding a row is the list fighting the hand.
     */
    onReorderingChange: (Boolean) -> Unit = {},
) {
    if (queue.isEmpty()) {
        EmptyPanel(stringResource(R.string.queue_empty))
        return
    }

    val haptics = LocalHapticFeedback.current

    // Which row is in the hand.
    //
    // The index is renumbered *while* the drag happens — every crossing moves
    // the row and shifts the ones it passed — so this is updated at each
    // crossing rather than held from the press. Minus one means nobody is
    // dragging, which is also why it cannot simply be the row's own index.
    var draggingIndex by remember { mutableIntStateOf(-1) }
    LaunchedEffect(draggingIndex >= 0) { onReorderingChange(draggingIndex >= 0) }

    // How far the finger has travelled since the last crossing, not since the
    // press. Once a row has been moved, the list has already put it under the
    // finger again, so carrying the whole distance forward would move it a
    // second time for a journey it has already made.
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowHeight = with(LocalDensity.current) { QueueRowHeight.toPx() }

    // Keyed on the track, not on where it sits.
    //
    // With the index as the key, taking one out renumbered every row below
    // it — as far as the list is concerned each of those became a different
    // item, so nothing could be animated and the queue jumped. Keyed on the
    // track itself, the row that went is the only one that changes and the
    // rest slide up into the gap.
    //
    // And not on the index *either*, which the key carried for a while to
    // tell two copies of one track apart: a row dragged one place down took a
    // new index, so a new key, so it was a new row — and the handle under
    // the finger belonged to the old one, gone. A drag could move a row once
    // and then held nothing. Copies are told apart by counting them instead,
    // which a move does not change unless one copy is dragged past another.
    val keys = remember(queue) {
        val seen = HashMap<String, Int>()
        queue.map { entry ->
            val nth = seen.getOrDefault(entry.uri, 0)
            seen[entry.uri] = nth + 1
            "${entry.uri}#$nth"
        }
    }

    LazyColumn(state = listState, modifier = Modifier.padding(vertical = 8.dp)) {
        itemsIndexed(
            queue,
            key = { at, _ -> keys[at] },
        ) { _, entry ->
            val held = draggingIndex == entry.index
            val startIndex by rememberUpdatedState(entry.index)
            QueueRow(
                entry = entry,
                modifier = Modifier
                    .fillMaxWidth()
                    // A block each, with air between them.
                    //
                    // The list was rows of text standing on the panel's own
                    // film, which at any length reads as one slab with writing
                    // on it: nothing says where a song ends and the next begins
                    // except the gap in the writing. A pane each says it in the
                    // material, and on a surface that is already glass the
                    // engine has a recipe for exactly that; see [queueBlock].
                    .padding(
                        horizontal = QueueBlockInset,
                        vertical = QueueBlockGap / 2,
                    )
                    // Everything but the row in the hand animates into its new
                    // place. The held one must not: `animateItem` would be
                    // animating a row that is already following a finger, and
                    // the two together read as the row lagging behind the hand
                    // rather than as the list making room for it.
                    .then(if (held) Modifier else Modifier.animateItem())
                    // Lifted onto the glass while it travels, which is the whole
                    // point of a drag you can see: a row that only slides looks
                    // like the list scrolling under it.
                    .graphicsLayer {
                        if (held) {
                            translationY = dragOffset
                            scaleX = QueueHeldScale
                            scaleY = QueueHeldScale
                            shadowElevation = QueueHeldElevation.toPx()
                            shape = RoundedCornerShape(14.dp)
                            clip = false
                        }
                    }
                    .queueBlock()
                    // What a held row adds to the material, drawn over it
                    // rather than instead of it: a block is still a block while
                    // it travels.
                    .then(
                        if (held) {
                            Modifier.background(
                                GlassInk.copy(alpha = 0.10f),
                                QueueBlockShape,
                            )
                        } else {
                            Modifier
                        },
                    )
                    .clickable { onPlay(entry.index) },
                onRemove = { onRemove(entry.index) },
                handle = {
                    // A handle, and not the whole row.
                    //
                    // The row is already a button — tapping it plays that track
                    // — and a list that reorders itself when you meant to play
                    // something is worse than one that does not reorder at all.
                    // A long press would have been the other answer, and it is
                    // the wrong one here: this list is inside a panel that is
                    // itself dragged open and shut, so half a second of holding
                    // still before anything happens is half a second of the
                    // panel wondering whether it is being closed.
                    QueueHandle(
                        modifier = Modifier
                            // Keyed on nothing: the detector must survive the
                            // reordering it is causing. `startIndex` is read
                            // through a state for the same reason — the row this
                            // lambda was built for has a different index by the
                            // second crossing.
                            .pointerInput(Unit) {
                                detectVerticalDragGestures(
                                    onDragStart = {
                                        draggingIndex = startIndex
                                        dragOffset = 0f
                                        haptics.performHapticFeedback(
                                            HapticFeedbackType.LongPress,
                                        )
                                    },
                                    onDragEnd = {
                                        draggingIndex = -1
                                        dragOffset = 0f
                                    },
                                    onDragCancel = {
                                        draggingIndex = -1
                                        dragOffset = 0f
                                    },
                                ) { change, amount ->
                                    change.consume()
                                    dragOffset += amount
                                    // One row at a time, and the offset is reset
                                    // by exactly the row it crossed: what is
                                    // left is the part of the journey the list
                                    // has not answered yet.
                                    while (dragOffset >= rowHeight) {
                                        val to = draggingIndex + 1
                                        if (to > queue.last().index) break
                                        onMove(draggingIndex, to)
                                        draggingIndex = to
                                        dragOffset -= rowHeight
                                        haptics.performHapticFeedback(
                                            HapticFeedbackType.SegmentTick,
                                        )
                                    }
                                    while (dragOffset <= -rowHeight) {
                                        val to = draggingIndex - 1
                                        if (to < queue.first().index) break
                                        onMove(draggingIndex, to)
                                        draggingIndex = to
                                        dragOffset += rowHeight
                                        haptics.performHapticFeedback(
                                            HapticFeedbackType.SegmentTick,
                                        )
                                    }
                                }
                            },
                    )
                },
            )
        }
    }
}

/**
 * One row of the queue: the cover, the names, and the two things you can do to it.
 *
 * On its own so that the still the morph carries — see PanelExtensionStill —
 * is a picture of *this* row and not a second drawing of it. Given [onRemove]
 * and a [handle] it is the live row; given neither it is the picture.
 */
@Composable
internal fun QueueRow(
    entry: QueueEntry,
    modifier: Modifier = Modifier,
    /** Takes the track out; null on a still. Absent for the one playing either way. */
    onRemove: (() -> Unit)? = null,
    /** The grip at the end, with whatever the list has hung on it. */
    handle: @Composable () -> Unit = { QueueHandle() },
) {
    Row(
        modifier.padding(start = 10.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        dev.lelonio.square.ui.components.Artwork(
            url = entry.artworkUrl,
            title = entry.title,
            modifier = Modifier.size(44.dp),
            corner = 8.dp,
            decodeSize = 44.dp,
        )

        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                entry.title,
                style = MaterialTheme.typography.titleMedium,
                // The accent for the one playing — inside the player
                // that is the cover's own colour — because a shade of
                // grey was not enough to find it in a long queue.
                color = when {
                    entry.isCurrent -> MaterialTheme.colorScheme.primary
                    else -> GlassInk.copy(alpha = 0.85f)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The mark for a track somebody asked for by hand, the
                // way every queue that has one draws it: a small glyph
                // before the name, in the accent, so the difference
                // between "next in the list" and "next because I said
                // so" is visible without reading anything.
                if (entry.queued) {
                    Icon(
                        PhosphorIcons.Regular.Queue,
                        contentDescription = stringResource(R.string.queued),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(end = 5.dp)
                            .size(13.dp),
                    )
                }
                Text(
                    entry.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = GlassInkDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Not on the track being played: taking that one out is a
        // different act — it is a skip — and it already has a button.
        if (!entry.isCurrent) {
            Box(
                Modifier
                    .size(QueueTouchTarget)
                    .clip(RoundedCornerShape(50))
                    .then(
                        if (onRemove != null) {
                            Modifier.pressable(onRemove, pressedScale = 0.86f)
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    PhosphorIcons.Regular.X,
                    contentDescription = stringResource(R.string.remove_from_queue),
                    tint = GlassInkDim,
                    modifier = Modifier.size(16.dp),
                )
            }
            handle()
        }
    }
}

/**
 * The grip: six dots drawn small, inside a target the size of a finger.
 *
 * The two were one thing for a while, and that is the whole of why reordering
 * was fiddly. A drag has to start on the grip -- the row itself is a button, and
 * a list that reorders when you meant to play something is worse than one that
 * does not reorder at all -- so the grip is the only way in, and it was
 * twenty-two points wide, which is half of what a finger asks for.
 *
 * The glyph stays the size it was. What grew is the box around it, which is also
 * what the caller hangs the detector on.
 */
@Composable
internal fun QueueHandle(modifier: Modifier = Modifier) {
    Box(
        modifier.size(QueueTouchTarget),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            PhosphorIcons.Regular.DotsSixVertical,
            contentDescription = stringResource(R.string.reorder),
            tint = GlassInkDim,
            modifier = Modifier.size(QueueDragHandleWidth),
        )
    }
}

/**
 * A pane of the surface this list stands on, for one row.
 *
 * The engine's rule is that glass goes on the container and never on the row,
 * and it is a rule about cost: a group of twelve rows is one pane, not twelve,
 * because every pane is a layer recording and a chain of shaders over its own
 * bounds. What makes a queue the exception is that the rows are the point --
 * they are separate songs, they are dragged past one another, and one pane
 * behind all of them says the opposite of what the list is for.
 *
 * It is affordable here for the reason the stacked recipe exists: the canvas is
 * another pane of glass, already frosted and already still, so the capture is
 * taken once and kept -- [GlassRole.Content] does that by itself -- and a
 * gradient riding along with a row is indistinguishable from one fixed behind
 * it. Only the rows on screen are composed, which is six or seven.
 *
 * Asking is a request and not an instruction, exactly as in the engine: with no
 * canvas in scope this hands the modifier back untouched and the row is drawn on
 * whatever it was drawn on before.
 */
@Composable
private fun Modifier.queueBlock(): Modifier {
    val canvas = dev.antigravity.fluidengine.ui.fluid.LocalFluidCanvasBackdrop.current ?: return this
    val stacked = dev.antigravity.fluidengine.ui.fluid.LocalFluidCanvasIsGlass.current
    val defaults = dev.antigravity.fluidengine.ui.fluid.GlassDefaults
    return this.then(
        Modifier.glassSurface(
            state = canvas,
            tint = if (stacked) defaults.stackedContentTint() else defaults.contentTint(),
            shape = QueueBlockShape,
            role = dev.antigravity.fluidengine.ui.fluid.GlassRole.Content,
            optics = if (stacked) {
                defaults.stackedContentOptics()
            } else {
                defaults.optics(dev.antigravity.fluidengine.ui.fluid.GlassRole.Content)
            },
        ),
    )
}

/**
 * How tall one queue row is: a 44 dp cover with 8 dp above and below it.
 *
 * Written down rather than measured, and that is a trade made on purpose. The
 * honest way is to read each row's bounds and find which one the finger is over,
 * which is also four more pieces of state and a re-layout inside a drag. Every
 * row here is the same height by construction — the cover sets it and the two
 * lines beside it are shorter — so one number is the whole answer, and it is
 * wrong only if somebody changes the row without changing this.
 */
/** A block on its own: a 44 dp cover with 8 dp above and below it. */
private val QueueBlockHeight = 60.dp

/** The air between two blocks, and what keeps them off the panel's edge. */
private val QueueBlockGap = 6.dp
private val QueueBlockInset = 10.dp

/** Declared after the two it adds up, because a file-level val is read in order. */
private val QueueRowHeight = QueueBlockHeight + QueueBlockGap

/** The corner a block is cut with; a held row's wash takes the same one. */
private val QueueBlockShape = dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape(
    dev.antigravity.fluidengine.ui.fluid.FluidRadius.Card,
)

/** The glyph on the grip. What a finger presses is [QueueTouchTarget]. */
private val QueueDragHandleWidth = 22.dp

/** What a finger is given for the two things at the end of a row. */
private val QueueTouchTarget = 44.dp

/** How much a held row grows, and how far it stands off the list. */
private const val QueueHeldScale = 1.02f
private val QueueHeldElevation = 10.dp

@Composable
internal fun EmptyPanel(message: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = GlassInkDim,
        )
    }
}
