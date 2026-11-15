// SPDX-License-Identifier: GPL-3.0-only
package dev.lelonio.square.ui.library.history

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ClockCounterClockwise
import androidx.compose.material3.Icon
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidLoadingBlock
import dev.antigravity.fluidengine.ui.fluid.FluidPressGrip
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSearchField
import dev.antigravity.fluidengine.ui.fluid.FluidSearchLabels
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.lelonio.square.R
import dev.lelonio.square.data.HistoryArchiveEntry
import dev.lelonio.square.ui.components.Artwork
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** I09 can place this same entry in Library's grid and list; the callback belongs to the host. */
@Composable
fun ListeningHistoryLibraryEntry(onClick: () -> Unit, modifier: Modifier = Modifier) {
    FluidListGroup(modifier, glass = true) {
        FluidListRow(
            title = stringResource(R.string.wf_i14_title),
            subtitle = "",
            leading = { Icon(PhosphorIcons.Regular.ClockCounterClockwise, contentDescription = null) },
            onClick = onClick,
            modifier = Modifier.testTag("wf_i14_library_entry"),
        )
    }
}

@Composable
fun ListeningHistoryPage(
    state: ListeningHistoryState,
    onSearch: (String) -> Unit,
    onLoadMore: () -> Unit,
    onReload: () -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
    onPlay: ((HistoryArchiveEntry) -> Unit)? = null,
) {
    val locale = LocalConfiguration.current.locales[0]
    val zone = ZoneId.systemDefault()
    val dates = remember(locale, zone) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).withZone(zone)
    }
    val labels = FluidSearchLabels(
        search = stringResource(R.string.wf_i14_search),
        clear = stringResource(R.string.wf_i14_clear),
        close = stringResource(R.string.wf_i14_close_search),
    )
    // Keep the input/focus mounted while typing. Only the list position resets for a new query.
    key(state.request.session.access) {
        val list = rememberLazyListState()
        LaunchedEffect(state.request.query, state.request.revision, state.request.reload) { list.scrollToItem(0) }
        FluidScreen(
            title = stringResource(R.string.wf_i14_title),
            onBack = onBack,
            listState = list,
            itemSpacing = 8.dp,
            extraBottomPadding = contentPadding.calculateBottomPadding(),
            modifier = Modifier.testTag("wf_i14_history_page"),
        ) {
            if (state.request.session.access != null) {
                item("wf_i14_search") {
                    FluidSearchField(
                        query = state.request.query, onQueryChange = onSearch,
                        placeholder = stringResource(R.string.wf_i14_search), labels = labels,
                        autoFocus = false,
                        modifier = Modifier.fillMaxWidth().testTag("wf_i14_history_search"),
                    )
                }
                item("wf_i14_scope") {
                    FluidSectionFootnote(stringResource(
                        if (state.request.session.recording) R.string.wf_i14_scope else R.string.wf_i14_recording_paused,
                    ), Modifier.padding(bottom = 8.dp))
                }
            }
            when (state.phase) {
                ListeningHistoryPhase.NO_ACCOUNT -> item("wf_i14_no_account") {
                    FluidEmptyState(stringResource(R.string.wf_i14_no_account), stringResource(R.string.wf_i14_no_account_detail))
                }
                ListeningHistoryPhase.LOADING -> item("wf_i14_loading") { FluidLoadingBlock() }
                ListeningHistoryPhase.ERROR -> item("wf_i14_error") {
                    FluidEmptyState(stringResource(R.string.wf_i14_error), stringResource(R.string.wf_i14_error_detail))
                    FluidButton(stringResource(R.string.wf_i14_retry), onReload, fillWidth = true,
                        grip = FluidPressGrip.YieldsToScroll)
                }
                ListeningHistoryPhase.READY -> {
                    if (state.entries.isEmpty()) item("wf_i14_empty") {
                        FluidEmptyState(
                            title = stringResource(if (state.request.query.isBlank()) R.string.wf_i14_empty else R.string.wf_i14_no_results),
                            detail = stringResource(if (state.request.query.isBlank()) R.string.wf_i14_empty_detail else R.string.wf_i14_no_results_detail),
                        )
                    }
                    // Engine 3.0.6 has no fluidGroupedItems. One keyed lazy item and opaque
                    // Engine container per row avoids panel-position keys and giant glass layers.
                    items(state.entries, key = { "wf_i14_event:${it.event.id}" }, contentType = { "wf_i14_event" }) { entry ->
                        val track = entry.event.track
                        val time = dates.format(Instant.ofEpochMilli(entry.playedAt))
                        val meta = if (entry.event.listenedMs == null)
                            stringResource(R.string.wf_i14_imported_time, time) else time
                        FluidListGroup {
                            FluidListRow(
                                title = track.name, subtitle = track.artist, meta = meta,
                                titleMaxLines = 2, subtitleMaxLines = 2,
                                leading = { Artwork(track.artworkUrl, track.name, Modifier.size(48.dp), decodeSize = 48.dp) },
                                leadingTile = false, disclosure = false,
                                onClick = onPlay?.let { play -> { play(entry) } },
                            )
                        }
                    }
                    state.next?.let { next ->
                        item("wf_i14_more") {
                            if (state.moreFailed) {
                                FluidSectionFootnote(stringResource(R.string.wf_i14_more_error))
                                FluidButton(stringResource(R.string.wf_i14_retry), onLoadMore, fillWidth = true,
                                    grip = FluidPressGrip.YieldsToScroll)
                            } else {
                                LaunchedEffect(state.request, next) { onLoadMore() }
                                FluidLoadingBlock(minHeight = 64.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}
