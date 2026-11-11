// SPDX-License-Identifier: GPL-3.0-only
// Functional reference: Lelonio/Square MonthlyListeningPage, revision
// 8598e13347b9c20153898f2481083c3ffcd24fb2 (GPL-3.0). Fluid composition is app-owned.
package dev.lelonio.square.ui.library.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CaretDown
import com.adamglin.phosphoricons.regular.CaretLeft
import com.adamglin.phosphoricons.regular.CaretRight
import com.adamglin.phosphoricons.regular.CaretUp
import com.adamglin.phosphoricons.regular.Info
import com.adamglin.phosphoricons.regular.MusicNotes
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidGlassButton
import dev.antigravity.fluidengine.ui.fluid.FluidGlassIconButton
import dev.antigravity.fluidengine.ui.fluid.FluidLoadingBlock
import dev.antigravity.fluidengine.ui.fluid.FluidPressGrip
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidTextStyles
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassRole
import dev.antigravity.fluidengine.ui.fluid.currentCanvasBackdrop
import dev.antigravity.fluidengine.ui.fluid.glassSurface
import dev.antigravity.fluidengine.ui.fluid.rememberEmptyGlassBackdrop
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidToneTile
import dev.lelonio.square.R
import dev.lelonio.square.data.HistoryCoverage
import java.text.NumberFormat
import java.time.Instant
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// One material for the whole section, and none of it is the app's own.
//
// Every pane here is the Engine's Content glass (`FluidListGroup(glass = true)`): it refracts the
// artwork canvas the app records before any page (`LocalFluidCanvasBackdrop`, SquareApp's
// groundGlass), which is the same picture the page paper carries. That is what the Library's chips
// already stand on, so the entry, the summary and the lists read as one sheet of the same app
// instead of near-black cards beside glass buttons. Controls are the Engine's lenses, and the
// persistent notice is a Floating pane over the page's own recording. No tint, blur or rim is
// written here: where there is no canvas (a bare test host, API < 31) the Engine draws its
// opaque surface and the layout is unchanged.

/** Inner margin of a pane that is not a list row. */
private val PanePadding = 20.dp

/** Room between the month selector and whatever state the page has to say under it. */
private val StateGap = 14.dp

/** Room between the notice and the system navigation area, and between the notice and the list. */
private val NoticeGap = 8.dp

/** A capsule while the notice is one line; a rounded pane once a large font wraps it. */
private val NoticeShape = ContinuousCornerShape(FluidRadius.Group)

/** Below this much room per unit of font scale a ranked row stacks instead of using columns. */
private val RoomyRowWidth = 300.dp

/**
 * What glass standing *inside* a page may refract: the artwork canvas, recorded before any page.
 *
 * Never the chrome recording (`LocalGlassBackdrop`): at the app root that layer contains this
 * page, and a lens sampling a layer it is drawn into recurses on the render thread. Without a
 * canvas the control keeps its rim and its press response and bends nothing.
 */
@Composable
private fun pageCanvas(): GlassBackdropState = currentCanvasBackdrop() ?: rememberEmptyGlassBackdrop()

/** The section's mark: the accent lives on the icon tile, never on the pane behind a row. */
@Composable
private fun StatsTile() {
    FluidToneTile(color = MaterialTheme.colorScheme.primary) {
        Icon(PhosphorIcons.Regular.MusicNotes, contentDescription = null)
    }
}

/**
 * The same entry can be placed in both Library layouts, without moving Home or its AirPods area.
 *
 * One Content pane over the artwork canvas, like the chips above it. It must keep sampling the
 * canvas and never Library's own body recording, which contains this entry.
 */
@Composable
fun ListeningStatsLibraryEntry(onClick: () -> Unit, modifier: Modifier = Modifier) {
    FluidListGroup(modifier, glass = true) {
        FluidListRow(
            title = stringResource(R.string.listening_stats_title),
            subtitle = stringResource(R.string.listening_stats_entry),
            modifier = Modifier.testTag("listening-stats-entry"),
            leading = { StatsTile() },
            leadingTile = false,
            onClick = onClick,
        )
    }
}

/** Pure render surface: no account lookup, remote artwork, imports, player controls or network. */
@Composable
fun ListeningStatsPage(
    state: ListeningStatsState,
    currentMonth: YearMonth,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onReload: () -> Unit,
    onSettings: () -> Unit,
    onBack: () -> Unit,
    demo: Boolean = false,
) {
    val period = state.request.period
    var detailsExpanded by rememberSaveable(state.request.session.access, period) { mutableStateOf(false) }
    var noticeHeight by remember { mutableStateOf(0.dp) }
    val locale = LocalConfiguration.current.locales[0]
    val numbers = NumberFormat.getIntegerInstance(locale)
    val date = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale).withZone(period.zone)
    fun time(at: Long) = date.format(Instant.ofEpochMilli(at))
    fun count(value: Long) = numbers.format(value)

    FluidScreen(
        title = stringResource(R.string.listening_stats_page_title),
        onBack = onBack,
        modifier = Modifier.testTag("listening-stats-page"),
        extraBottomPadding = noticeHeight,
        overlay = { backdrop ->
            PartialNotice(backdrop, demo, Modifier.align(Alignment.BottomCenter)) { noticeHeight = it }
        },
    ) {
        item("month") {
            Column {
                MonthSelector(period, currentMonth, onPrevious, onNext)
                // Always composed, so a change of state is announced; empty while the month has data.
                Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                    when (val content = state.content) {
                        StatsContent.NoAccount -> StatePane {
                            FluidEmptyState(
                                title = stringResource(R.string.listening_stats_no_account),
                                detail = stringResource(R.string.listening_stats_no_account_detail),
                                modifier = Modifier.testTag("listening-stats-no-account"),
                            )
                            FluidButton(stringResource(R.string.settings), onSettings, fillWidth = true,
                                grip = FluidPressGrip.YieldsToScroll)
                        }
                        StatsContent.Loading -> Column(Modifier.padding(top = StateGap).testTag("listening-stats-loading")) {
                            FluidSectionFootnote(stringResource(R.string.listening_stats_loading))
                            FluidLoadingBlock()
                        }
                        StatsContent.Error -> StatePane {
                            FluidEmptyState(
                                title = stringResource(R.string.listening_stats_error),
                                detail = stringResource(R.string.listening_stats_error_detail),
                                modifier = Modifier.testTag("listening-stats-error"),
                            )
                            FluidButton(stringResource(R.string.retry), onReload, fillWidth = true,
                                grip = FluidPressGrip.YieldsToScroll)
                        }
                        is StatsContent.Ready -> {
                            if (!content.stats.hasActivity) StatePane {
                                FluidEmptyState(
                                    title = stringResource(R.string.listening_stats_empty),
                                    detail = stringResource(R.string.listening_stats_empty_detail),
                                    modifier = Modifier.testTag("listening-stats-empty"),
                                )
                            }
                        }
                    }
                }
            }
        }
        val stats = (state.content as? StatsContent.Ready)?.stats
        if (stats != null) {
            stats.tracks.firstOrNull()?.let { song ->
                item("song") {
                    SongOfTheMonth(
                        name = song.track.name,
                        artist = song.track.artist,
                        plays = stringResource(R.string.listening_stats_plays_value, count(song.plays)),
                    )
                }
            }
            item("totals") {
                StatsTotals(count(stats.plays), if (stats.measuredLocalMs in 1L..59_999L) "< 1"
                    else count(stats.measuredLocalMs / 60_000))
            }
            if (stats.tracks.isNotEmpty()) {
                item("tracks-header") { FluidSectionHeader(stringResource(R.string.listening_stats_tracks)) }
                item("tracks") {
                    RankedGroup(Modifier.testTag("listening-stats-tracks")) { roomy ->
                        stats.tracks.forEachIndexed { index, track ->
                            if (index > 0) RankedDivider(roomy)
                            RankedRow(
                                rank = count((index + 1).toLong()),
                                name = track.track.name,
                                detail = track.track.artist,
                                plays = stringResource(R.string.listening_stats_plays_value, count(track.plays)),
                                roomy = roomy,
                            )
                        }
                    }
                }
            }
            if (stats.artists.isNotEmpty()) {
                item("artists-header") { FluidSectionHeader(stringResource(R.string.listening_stats_artists)) }
                item("artists") {
                    RankedGroup(Modifier.testTag("listening-stats-artists")) { roomy ->
                        stats.artists.forEachIndexed { index, artist ->
                            if (index > 0) RankedDivider(roomy)
                            RankedRow(
                                rank = count((index + 1).toLong()),
                                name = artist.first,
                                detail = null,
                                plays = stringResource(R.string.listening_stats_plays_value, count(artist.second)),
                                roomy = roomy,
                            )
                        }
                    }
                }
            }
        }
        item("details-toggle") {
            val detailState = stringResource(if (detailsExpanded) R.string.listening_stats_hide_details else R.string.listening_stats_show_details)
            FluidListGroup(glass = true) {
                FluidListRow(title = stringResource(R.string.listening_stats_details),
                    subtitle = stringResource(R.string.listening_stats_details_subtitle), disclosure = false,
                    modifier = Modifier.testTag("listening-stats-details-toggle").semantics { stateDescription = detailState },
                    // The same glyph as the notice at the foot of the page: this row is its answer.
                    leading = { Icon(PhosphorIcons.Regular.Info, contentDescription = null) },
                    badge = { Icon(if (detailsExpanded) PhosphorIcons.Regular.CaretUp else PhosphorIcons.Regular.CaretDown, null) },
                    onClick = { detailsExpanded = !detailsExpanded })
            }
        }
        if (detailsExpanded) {
        item("coverage-header") { FluidSectionHeader(stringResource(R.string.listening_stats_coverage)) }
        item("coverage") {
            FluidListGroup(Modifier.testTag("listening-stats-coverage"), glass = true) {
                FluidListRow(
                    title = stringResource(R.string.listening_stats_partial),
                    subtitle = stringResource(R.string.listening_stats_partial_detail),
                    meta = stringResource(R.string.listening_stats_range, time(period.from), time(period.until), period.zone.id),
                    disclosure = false,
                )
                if (state.request.session.access != null) {
                    FluidListDivider()
                    FluidListRow(
                        title = stringResource(if (state.request.session.recording) R.string.listening_stats_recording_on
                            else R.string.listening_stats_recording_off),
                        subtitle = stringResource(R.string.listening_stats_recording_detail),
                        disclosure = false,
                    )
                }
                if (stats != null) {
                    FluidListDivider()
                    FluidListRow(
                        title = stringResource(R.string.listening_stats_sources),
                        subtitle = stringResource(R.string.listening_stats_sources_counts, count(stats.plays),
                            count(stats.localPlays), count(stats.spotifyOnlyPlays)),
                        meta = stringResource(R.string.listening_stats_loaded, time(stats.loadedAt)),
                        disclosure = false,
                    )
                }
            }
        }
        item("calculation") {
            FluidSectionFootnote(stringResource(R.string.listening_stats_minutes_detail))
            FluidSectionFootnote(stringResource(R.string.listening_stats_artist_note))
        }
        if (stats != null) {
            item("receipts-header") {
                FluidSectionHeader(stringResource(R.string.listening_stats_receipts),
                    detail = stringResource(R.string.listening_stats_receipts_detail))
            }
            item("receipts") {
                FluidListGroup(Modifier.testTag("listening-stats-receipts-anchor"), glass = true) {
                    if (stats.recentReceipts.isEmpty()) FluidListRow(
                        title = stringResource(R.string.listening_stats_no_imports),
                        subtitle = stringResource(R.string.listening_stats_no_imports_detail),
                        disclosure = false,
                    )
                    stats.recentReceipts.forEachIndexed { index, receipt ->
                        if (index > 0) FluidListDivider()
                        val observed = if (receipt.observedFrom != null && receipt.observedUntil != null)
                            stringResource(R.string.listening_stats_observed, time(receipt.observedFrom), time(receipt.observedUntil))
                        else stringResource(R.string.listening_stats_observed_none)
                        FluidListRow(
                            title = stringResource(R.string.listening_stats_receipt_at, time(receipt.importedAt)),
                            subtitle = observed,
                            meta = stringResource(receiptStatus(receipt)),
                            disclosure = false,
                        )
                    }
                }
            }
        }
        item("actions") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // A lens like the rest of the page's controls: this is a second-order action, and a
                // solid accent slab at the foot of a glass page would outweigh the summary itself.
                if (state.request.session.access != null) FluidGlassButton(
                    text = stringResource(R.string.listening_stats_reload),
                    onClick = onReload,
                    modifier = Modifier.fillMaxWidth().testTag("listening-stats-reload"),
                    backdrop = pageCanvas(),
                    enabled = state.content != StatsContent.Loading,
                    grip = FluidPressGrip.YieldsToScroll,
                )
                FluidListGroup(glass = true) {
                    FluidListRow(
                        title = stringResource(R.string.listening_stats_settings),
                        subtitle = stringResource(R.string.listening_stats_settings_detail),
                        onClick = onSettings,
                    )
                }
            }
        }
        }
    }
}

/**
 * The persistent "partial summary" notice, as a small pane floating over the list.
 *
 * Chrome, so it lives in the screen's overlay slot and refracts [backdrop], the canvas with this
 * page's body on top: rows show through it as they pass, the way they do through the top bar. It
 * is a label, not a control, and takes no pointer input. [onHeight] reports how much of the list's
 * end it covers, so the last row can always be scrolled clear of it.
 */
@Composable
private fun PartialNotice(backdrop: GlassBackdropState, demo: Boolean, modifier: Modifier, onHeight: (Dp) -> Unit) {
    val density = LocalDensity.current
    val navigationBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = navigationBar + NoticeGap),
        contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .onSizeChanged { onHeight(with(density) { it.height.toDp() } + NoticeGap) }
                .glassSurface(state = backdrop, tint = GlassDefaults.floatingTint(), shape = NoticeShape,
                    role = GlassRole.Floating)
                .padding(horizontal = 16.dp, vertical = 9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(PhosphorIcons.Regular.Info, contentDescription = null, modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.listening_stats_partial_short), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.testTag("listening-stats-partial"))
            }
            if (demo) Text(stringResource(R.string.listening_stats_demo), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                modifier = Modifier.testTag("listening-stats-demo"))
        }
    }
}

/** A state the page has to explain (no account, error, empty month), with its action, on one pane. */
@Composable
private fun StatePane(content: @Composable ColumnScope.() -> Unit) {
    FluidListGroup(Modifier.padding(top = StateGap), glass = true) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content)
    }
}

@Composable
private fun MonthSelector(period: ListeningMonth, currentMonth: YearMonth, onPrevious: () -> Unit, onNext: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val scale = LocalDensity.current.fontScale
    // Standing alone as a heading, so it takes a capital where the language has one.
    val month = period.month.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    if (maxWidth / scale < 260.dp) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(month, style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.testTag("listening-stats-month").semantics { heading() })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MonthStep(false, true, onPrevious)
            MonthStep(true, period.month < currentMonth, onNext)
        }
    } else {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MonthStep(false, true, onPrevious)
        Text(month,
            style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).testTag("listening-stats-month").semantics { heading() })
        MonthStep(true, period.month < currentMonth, onNext)
    }
    }
    }
}

/**
 * The same lens as the page's back button and the Library's bar actions, drawn the same way.
 * The tag sits on the 48 dp target; the click, its label and its disabled state are on the lens
 * inside it, which is the node a test or TalkBack acts on.
 */
@Composable
private fun MonthStep(next: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FluidGlassIconButton(
        onClick = onClick,
        modifier = Modifier.testTag(if (next) "listening-stats-next" else "listening-stats-previous"),
        backdrop = pageCanvas(),
        enabled = enabled,
        grip = FluidPressGrip.YieldsToScroll,
    ) {
        Icon(
            imageVector = if (next) PhosphorIcons.Regular.CaretRight else PhosphorIcons.Regular.CaretLeft,
            contentDescription = stringResource(if (next) R.string.listening_stats_next else R.string.listening_stats_previous),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * The month's headline. The song is the title and the count supports it; the accent arrives as a
 * wash over the pane, which keeps it the same sheet of glass as its neighbours.
 */
@Composable
private fun SongOfTheMonth(name: String, artist: String, plays: String) {
    val accent = MaterialTheme.colorScheme.primary
    val locale = LocalConfiguration.current.locales[0]
    val largeText = LocalDensity.current.fontScale >= 1.5f
    FluidListGroup(Modifier.testTag("listening-stats-song"), glass = true) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.16f), Color.Transparent)))
                .padding(PanePadding),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatsTile()
                Text(stringResource(R.string.listening_stats_song).uppercase(locale),
                    style = FluidTextStyles.uppercaseCaption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).semantics { heading() })
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, style = if (largeText) MaterialTheme.typography.headlineSmall
                    else MaterialTheme.typography.headlineMedium)
                Text(artist, style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(plays, style = FluidTextStyles.numeric)
        }
    }
}

@Composable
private fun StatsTotals(plays: String, minutes: String) {
    val scale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("listening-stats-totals")) {
        // The large text decides whether two metrics fit; narrow phones and large fonts stack.
        // Side by side the two panes share the taller one's height, whatever its label wraps to.
        if (maxWidth / scale >= 300.dp) Row(Modifier.height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Metric(plays, stringResource(R.string.listening_stats_plays),
                Modifier.weight(1f).fillMaxHeight().testTag("listening-stats-plays"))
            Metric(minutes, stringResource(R.string.listening_stats_minutes),
                Modifier.weight(1f).fillMaxHeight().testTag("listening-stats-minutes"))
        } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Metric(plays, stringResource(R.string.listening_stats_plays), Modifier.testTag("listening-stats-plays"))
            Metric(minutes, stringResource(R.string.listening_stats_minutes), Modifier.testTag("listening-stats-minutes"))
        }
    }
}

@Composable
private fun Metric(value: String, label: String, modifier: Modifier = Modifier) {
    FluidListGroup(modifier, glass = true) {
        Column(Modifier.padding(PanePadding), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(value, style = FluidTextStyles.largeNumeric)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * A chart of ranked rows on one pane. [rows] is told whether there is room for the rank and the
 * count to be columns of their own; narrow phones and large fonts get the stacked row instead.
 */
@Composable
private fun RankedGroup(modifier: Modifier, rows: @Composable ColumnScope.(roomy: Boolean) -> Unit) {
    val scale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val roomy = maxWidth / scale >= RoomyRowWidth
        FluidListGroup(modifier, glass = true) { rows(roomy) }
    }
}

/** With a rank column the hairline starts where the names do, not under the numerals. */
@Composable
private fun RankedDivider(roomy: Boolean) {
    FluidListDivider(if (roomy) Modifier.padding(start = 40.dp) else Modifier)
}

/**
 * One place in a chart: rank, name, an optional second line, and the count.
 *
 * With room, the rank leads in tabular figures and the count trails, so both scan as columns.
 * Without it the row is the stacked one this page always had: the rank in the title and the count
 * underneath, where nothing has to share a line with a long name.
 */
@Composable
private fun RankedRow(rank: String, name: String, detail: String?, plays: String, roomy: Boolean) {
    if (roomy) FluidListRow(
        title = name,
        subtitle = detail.orEmpty(),
        leading = {
            Text(rank, style = FluidTextStyles.numeric, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 24.dp))
        },
        leadingTile = false,
        badge = {
            Text(plays, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        disclosure = false,
    ) else FluidListRow(
        title = stringResource(R.string.listening_stats_rank, rank, name),
        subtitle = detail ?: plays,
        meta = if (detail != null) plays else null,
        disclosure = false,
    )
}

private fun receiptStatus(receipt: HistoryCoverage): Int = when {
    receipt.status == "error" || receipt.error != null -> R.string.listening_stats_receipt_error
    receipt.missingPages -> R.string.listening_stats_receipt_missing
    receipt.truncated -> R.string.listening_stats_receipt_truncated
    receipt.observedFrom == null -> R.string.listening_stats_receipt_empty
    else -> R.string.listening_stats_receipt_partial
}
