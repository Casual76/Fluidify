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
import androidx.compose.runtime.mutableStateOf
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
import dev.lelonio.square.ui.glass.LiquidBottomTab
import dev.lelonio.square.ui.glass.LiquidBottomTabs
import dev.lelonio.square.ui.glass.LiquidSlider
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
        val selected = views.indexOf(panel).coerceAtLeast(0)
        // Stable, or LiquidBottomTabs throws away the state it keys on this and
        // the indicator stops animating; see the note in SquareApp.
        val selectedState = rememberUpdatedState(selected)
        val selectedTabIndex = remember { { selectedState.value } }

        LiquidBottomTabs(
            selectedTabIndex = selectedTabIndex,
            onTabSelected = { onSelect(views[it]) },
            backdrop = backdrop,
            tabsCount = views.size,
            accentColor = GlassInk,
            containerColor = GlassFilm,
            // Slimmer than the tab bar, and icon-only. This one sits under the
            // transport rather than at the edge of the window, so it has to
            // read as a smaller thing than the app's own navigation.
            height = 42.dp,
            // Under half the height, or the refraction from the two long edges
            // meets in the middle and draws a seam across the capsule.
            lensDepth = 12.dp,
            modifier = Modifier.fillMaxWidth(0.46f),
        ) {
            PanelTab(
                icon = PhosphorIcons.Regular.VinylRecord,
                activeIcon = PhosphorIcons.Fill.VinylRecord,
                label = stringResource(R.string.cover),
                selected = selected == 0,
            ) { onSelect(PlayerPanel.NONE) }
            PanelTab(
                icon = PhosphorIcons.Regular.TextAlignLeft,
                activeIcon = PhosphorIcons.Fill.TextAlignLeft,
                label = stringResource(R.string.lyrics),
                selected = selected == 1,
                // The karaoke lives in this view and keeps working with the
                // panel shut, so the halo says so from outside it.
                marked = karaokeOn,
            ) { onSelect(PlayerPanel.LYRICS) }
        }

        Spacer(Modifier.width(10.dp))

        InfoButton(
            selected = panel == PlayerPanel.INFO,
            backdrop = backdrop,
        ) {
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
    backdrop: dev.lelonio.square.ui.glass.backdrop.Backdrop,
    onClick: () -> Unit,
) {
    dev.lelonio.square.ui.glass.LiquidButton(
        onClick = onClick,
        backdrop = backdrop,
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
private fun RowScope.PanelTab(
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
    onClick: () -> Unit,
) {
    LiquidBottomTab(onClick = onClick) {
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
) {
    if (queue.isEmpty()) {
        EmptyPanel(stringResource(R.string.queue_empty))
        return
    }

    LazyColumn(Modifier.padding(vertical = 8.dp)) {
        // Keyed on the track, not on where it sits.
        //
        // With the index as the key, taking one out renumbered every row below
        // it — as far as the list is concerned each of those became a different
        // item, so nothing could be animated and the queue jumped. Keyed on the
        // track itself, the row that went is the only one that changes and the
        // rest slide up into the gap.
        itemsIndexed(
            queue,
            key = { at, entry -> "${entry.uri}-$at-${entry.title}" },
        ) { _, entry ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .animateItem()
                    .clickable { onPlay(entry.index) }
                    .padding(horizontal = 18.dp, vertical = 8.dp),
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
                    Icon(
                        PhosphorIcons.Regular.X,
                        contentDescription = stringResource(R.string.remove_from_queue),
                        tint = GlassInkDim,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .pressable({ onRemove(entry.index) }, pressedScale = 0.86f)
                            .padding(10.dp)
                            .size(16.dp),
                    )
                }
            }
        }
    }
}

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
