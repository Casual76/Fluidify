package dev.lelonio.square.ui.library

import dev.antigravity.fluidengine.ui.fluid.fluidOverscrollContent
import dev.antigravity.fluidengine.ui.fluid.fluidOverscrollEdge
import dev.antigravity.fluidengine.ui.fluid.FluidCollapsingTitle
import dev.antigravity.fluidengine.ui.fluid.FluidCollapsingTopBar
import dev.antigravity.fluidengine.ui.fluid.FluidScreenDefaults
import dev.antigravity.fluidengine.ui.fluid.FluidTitleCollapse
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.fluidTitleCollapseOrigin
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberCombinedGlassBackdrop
import dev.antigravity.fluidengine.ui.fluid.rememberFluidCollapseScroll
import dev.antigravity.fluidengine.ui.fluid.rememberFluidTitleCollapse
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.antigravity.fluidengine.ui.fluid.rememberFluidEdgeOverscroll
import dev.antigravity.fluidengine.ui.fluid.fluidContextMenuAnchor
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.lelonio.square.ui.glass.backdrop.Backdrop
import dev.lelonio.square.R
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.data.sortedByRecentlyOpened
import dev.lelonio.square.data.withLocalFilesFirst
import dev.lelonio.square.data.withPinnedFirst
import dev.lelonio.square.ui.MainViewModel
import dev.lelonio.square.ui.components.Artwork
import dev.lelonio.square.ui.components.PlaylistCover
import dev.lelonio.square.ui.glass.LiquidButton
import dev.lelonio.square.ui.glass.pressable
import dev.lelonio.square.ui.player.GlassFilm
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import dev.lelonio.square.ui.theme.softShadow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import dev.lelonio.square.ui.components.CHOICE_MENU_WIDTH
import dev.lelonio.square.ui.components.GlassChoiceItem
import dev.lelonio.square.ui.components.GlassMenuRule
import dev.lelonio.square.ui.components.GlassChoiceMenu
import dev.lelonio.square.ui.glass.backdrop.backdrops.layerBackdrop
import dev.lelonio.square.ui.glass.backdrop.backdrops.rememberCombinedBackdrop
import dev.lelonio.square.ui.glass.backdrop.backdrops.rememberLayerBackdrop
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.fill.PushPin
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowsDownUp
import com.adamglin.phosphoricons.regular.ListBullets
import com.adamglin.phosphoricons.regular.Plus
import com.adamglin.phosphoricons.regular.SquaresFour

/** How long one filter dissolves into the next. */
private const val FILTER_FADE_MS = 180

/** How the playlists are arranged. */
private enum class Layout { GRID, LIST }

/** What the library is sorted by. */
private enum class Order(val key: String, @StringRes val label: Int) {
    RECENT("recent", R.string.recently_opened),
    NAME("name", R.string.name),
    ADDED("added", R.string.spotify_order),
}

/**
 * Which kind of thing the library is showing.
 *
 * The chips used to be the sort, which put the three least-used controls in the
 * most prominent place on the screen and left no way at all to say "only
 * albums". The sort is a menu now; the chips answer the question a library is
 * actually asked, which is what am I looking at.
 */
private enum class Filter(@StringRes val label: Int, @StringRes val count: Int) {
    ALL(R.string.library_all, R.string.item_count),
    PLAYLISTS(R.string.playlists, R.string.playlist_count),
    ARTISTS(R.string.artists, R.string.artist_count),
    ALBUMS(R.string.albums, R.string.album_count),

    /**
     * What can be played with no connection.
     *
     * A filter rather than a page of its own, because it is the same question
     * the other chips ask — what am I looking at — and because a downloaded
     * playlist is still a playlist: it sorts, pins and opens like the rest.
     */
    DOWNLOADS(R.string.downloads, R.string.download_count),
}

/**
 * Every playlist on the account.
 *
 * Rewritten alongside the home page and for the same reason: this was a plain
 * Material list with a divider between every row, which is the one thing on
 * screen that cannot be made of glass. It is now the same material as the rest —
 * and, being the screen you come to when you know what you are looking for, it
 * gets the controls the home page has no room for: a grid, and a sort.
 */
@Composable
fun LibraryScreen(
    state: MainViewModel.UiState,
    contentPadding: PaddingValues,
    /**
     * The artwork wash on its own, for the bar the title docks into.
     *
     * Not the page-wide recording: that one contains this bar, and glass
     * blurring a photograph of itself is the one thing this material cannot do.
     */
    ground: GlassBackdropState,
    onLogIn: () -> Unit,
    onRetry: () -> Unit,
    onLogOut: () -> Unit,
    onOpenPlaylist: (CatalogPlaylist) -> Unit,
    /** URIs most recently opened first; see PlaylistOrderStore. */
    playlistOrder: List<String>,
    /** URIs the listener pinned to the top; see PinnedPlaylistStore. */
    pinned: List<String> = emptyList(),
    /** False when the source cannot be written to; see MusicBackend. */
    canEdit: Boolean = false,
    onCreatePlaylist: () -> Unit = {},
    /** Long press: rename and delete live in a sheet, like a track's actions. */
    onPlaylistMenu: (CatalogPlaylist) -> Unit = {},
    /**
     * What a long press on a playlist offers. Built by the caller — it owns
     * the pin store and the rename/delete plumbing — and served here through
     * the engine's context menu, which lifts the pressed row itself.
     */
    playlistActions: (CatalogPlaylist) -> List<dev.antigravity.fluidengine.ui.fluid.FluidContextAction> =
        { emptyList() },
    /** The artists the account follows; empty leaves the shelf out entirely. */
    artists: List<dev.lelonio.square.data.SearchItem> = emptyList(),
    onOpenArtist: (dev.lelonio.square.data.SearchItem) -> Unit = {},
    /** The albums the account has saved; see MainViewModel.savedAlbums. */
    albums: List<CatalogPlaylist> = emptyList(),
    /** Which pages are kept offline, for the Downloads chip. */
    downloaded: Set<String> = emptySet(),
    /**
     * The shelf of songs downloaded on their own, or null when there are none.
     *
     * Only ever shown under the Downloads chip: it is not something the account
     * has, it is something this phone made, and it would be an odd stranger
     * among the playlists everywhere else.
     */
    downloadedSongs: CatalogPlaylist? = null,
    backdrop: Backdrop,
) {
    when (state) {
        MainViewModel.UiState.LoggedOut -> Centered {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displayLarge)
            Text(
                stringResource(R.string.unofficial_client),
                style = MaterialTheme.typography.bodyMedium,
                color = InkDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 40.dp),
            )
            GlassAction(stringResource(R.string.log_in_with_spotify), backdrop, onLogIn)
        }

        MainViewModel.UiState.Connecting,
        MainViewModel.UiState.Loading,
        -> Centered {
            CircularProgressIndicator(color = Ink, strokeWidth = 2.dp)
        }

        is MainViewModel.UiState.Failed -> Centered {
            Text(
                state.message,
                style = MaterialTheme.typography.bodyMedium,
                color = InkDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 40.dp),
            )
            GlassAction(stringResource(R.string.retry), backdrop, onRetry)
            GlassAction(stringResource(R.string.log_out), backdrop, onLogOut)
        }

        is MainViewModel.UiState.Ready -> {
            // Both are the listener's own decision about their library rather
            // than a mode of this screen, so they are read from where they were
            // left and written as they change; see LibraryViewStore.
            val appContext = LocalContext.current.applicationContext
            val view = remember(appContext) {
                (appContext as dev.lelonio.square.SquareApplication).libraryView
            }
            var layout by remember { mutableStateOf(if (view.grid) Layout.GRID else Layout.LIST) }
            var order by remember {
                mutableStateOf(Order.entries.firstOrNull { it.key == view.order } ?: Order.RECENT)
            }
            var filter by remember { mutableStateOf(Filter.ALL) }
            var sortOpen by remember { mutableStateOf(false) }
            // Recorded so the menu has the tiles behind it to blur, rather than
            // the page colour: over a flat fill glass has nothing to bend and
            // comes out looking like a hole. Safe to record — the menu is drawn
            // outside the grid, so nothing in this layer samples it.
            val listBackdrop = rememberLayerBackdrop()
            var descending by remember { mutableStateOf(view.descending) }
            val onOrderChosen: (Order) -> Unit = {
                order = it
                view.order = it.key
            }
            var sortAnchor by remember { mutableStateOf(androidx.compose.ui.unit.IntOffset.Zero) }
            val density = androidx.compose.ui.platform.LocalDensity.current

            val artistItems = remember(artists) {
                artists.map { artist ->
                    CatalogPlaylist(
                        uri = artist.uri,
                        name = artist.title,
                        artworkUrl = artist.artworkUrl,
                    )
                }
            }

            // What the chips are asking for, before any sorting. Computed per
            // filter *inside* the animated swap below, so the page on its way
            // out keeps showing its own list instead of the new one.
            fun shownFor(which: Filter): List<CatalogPlaylist> = when (which) {
                Filter.ALL -> state.playlists + albums
                Filter.PLAYLISTS -> state.playlists
                Filter.ALBUMS -> albums
                Filter.ARTISTS -> artistItems
                // Everything with a download, whatever kind it is, plus the
                // loose songs. Drawn from the same lists as the other chips, so
                // a downloaded playlist keeps its cover, its pin and its place.
                Filter.DOWNLOADS -> listOfNotNull(downloadedSongs) +
                    (state.playlists + albums + artistItems)
                        .filter { downloaded.contains(it.uri) }
            }

            fun sortedFor(which: Filter): List<CatalogPlaylist> {
                val shown = shownFor(which)
                return when (order) {
                    Order.RECENT -> shown.sortedByRecentlyOpened(playlistOrder)
                    Order.NAME -> shown.sortedWith(
                        compareBy(String.CASE_INSENSITIVE_ORDER) { it.name },
                    )
                    // The order the account added them, which is what the
                    // rootlist arrives in.
                    Order.ADDED -> shown
                }
                    // The direction belongs to the sort rather than beside it:
                    // reversing "recently opened" is "least recently", and a
                    // separate control for that would be a second sort.
                    .let { if (descending) it.reversed() else it }
                    // Pinning outranks the sort: it is the one instruction the
                    // listener gave about this list themselves.
                    .withPinnedFirst(pinned)
                    // And the phone's own music outranks that, because it is
                    // not a playlist competing for a place: it is a fixed shelf
                    // of the library, and one nobody has opened yet would
                    // otherwise sit sixtieth among lists they have.
                    //
                    // Not under the Downloads chip, where the local files shelf
                    // is not shown at all: those are somebody else's files that
                    // happen to be on the phone, which is a different thing
                    // from music this app was told to keep.
                    .let { if (which == Filter.DOWNLOADS) it else it.withLocalFilesFirst() }
            }

            val playlists = remember(
                state.playlists, albums, artistItems, filter, downloaded, downloadedSongs,
                playlistOrder, pinned, order, descending,
            ) { sortedFor(filter) }

            // Hoisted out of the swap below, so the heading can read the
            // scroll whichever shape the library is wearing. The copy on its
            // way out shares the position for the length of a fade, which is
            // shorter than it takes to notice.
            val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
            val rowsState = androidx.compose.foundation.lazy.rememberLazyListState()
            // Read once for both backdrops below, and read here so the lambdas
            // they are given stay cheap.
            val scrolling by remember {
                derivedStateOf { gridState.isScrollInProgress || rowsState.isScrollInProgress }
            }

            val gridScroll = rememberFluidCollapseScroll(gridState)
            val rowsScroll = rememberFluidCollapseScroll(rowsState)
            val title = stringResource(R.string.library)
            val collapse = rememberFluidTitleCollapse(
                title = title,
                scroll = if (layout == Layout.GRID) gridScroll else rowsScroll,
            )
            // This page's own body, recorded on its own; with the ground under
            // it that is an opaque image with no chrome in it, which is the one
            // thing the bar is allowed to blur.
            val bodyGlass = rememberGlassBackdrop()
            val barBackdrop = rememberCombinedGlassBackdrop(ground, bodyGlass)

            Box(
                Modifier
                    .fillMaxSize()
                    .fluidTitleCollapseOrigin(collapse),
            ) {
            Column(Modifier.fillMaxSize()) {
                val overscroll = rememberFluidEdgeOverscroll()

            val listPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = FluidScreenDefaults.topBarHeight(),
                    bottom = contentPadding.calculateBottomPadding(),
                )

                // The swap the chips ask for, animated the way the home
                // page's feed already is: the new list rises in as the old one
                // fades, instead of the contents snapping under a chip that
                // did animate.
                androidx.compose.animation.AnimatedContent(
                    targetState = filter,
                    // A dissolve, and nothing else.
                    //
                    // It used to slide in from a fourteenth of the page below
                    // while the outgoing copy faded twice as fast: the two were
                    // never at the same opacity at the same moment, so the
                    // change read as a jump followed by the new page climbing
                    // into place. Worse, the slide re-laid-out a whole grid of
                    // glass tiles on every frame of it, on top of the outgoing
                    // grid still being drawn — which is where the stutter came
                    // from. Equal, overlapping fades cost one extra layer for a
                    // fifth of a second and move nothing.
                    transitionSpec = {
                        androidx.compose.animation.fadeIn(tween(FILTER_FADE_MS))
                            .togetherWith(
                                androidx.compose.animation.fadeOut(tween(FILTER_FADE_MS)),
                            )
                            .using(
                                androidx.compose.animation.SizeTransform(clip = false),
                            )
                    },
                    label = "library filter",
                    modifier = Modifier
                        .fillMaxSize()
                        // Held still while the list is moving.
                        //
                        // Both of these record the whole page into a layer for
                        // the glass to sample, and recording is one full
                        // traversal of the subtree per frame — the dominant
                        // cost of a fling, measured and written up in
                        // LayerBackdropModifier. A list under the finger is
                        // exactly when that is most expensive and least worth
                        // paying: what is lost is a reflection that stops
                        // sliding for the length of the fling, under a blur
                        // heavy enough that it does not read as stopping.
                        .layerBackdrop(listBackdrop, frozen = { scrolling })
                        .glassBackdropSource(bodyGlass, frozen = { scrolling }),
                ) { shownFilter ->
                val playlists = remember(
                    state.playlists, albums, artistItems, shownFilter,
                    playlistOrder, pinned, order, descending,
                ) { sortedFor(shownFilter) }
                when (layout) {
                    Layout.GRID -> LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        state = gridState,
                        contentPadding = listPadding,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .fluidOverscrollEdge(overscroll)
                            .fluidOverscrollContent(overscroll),
                        overscrollEffect = null,
                    ) {
                        // The heading rides in the grid, spanning it, so it
                        // scrolls away with what it names. The grid stays a
                        // grid: only the row above it moved into the page.
                        item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                            Header(
                                count = playlists.size,
                                layout = layout,
                                order = order,
                                filter = filter,
                                backdrop = backdrop,
                                title = title,
                                collapse = collapse,
                                onOrder = { onOrderChosen(it) },
                                onFilter = { filter = it },
                                onSort = { sortOpen = true },
                                onSortAnchor = { sortAnchor = it },
                            )
                        }

                        if (artists.isNotEmpty() && shownFilter == Filter.ALL) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "artists") {
                                ArtistShelf(artists, onOpenArtist)
                            }
                        }

                        items(playlists, key = { it.uri }) { playlist ->
                            GridTile(
                                playlist,
                                pinned = playlist.uri in pinned,
                                onClick = { open(playlist, artists, onOpenPlaylist, onOpenArtist) },
                                contextActions = playlistActions(playlist),
                            )
                        }
                    }

                    Layout.LIST -> LazyColumn(
                        state = rowsState,
                        contentPadding = listPadding,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .fluidOverscrollEdge(overscroll)
                            .fluidOverscrollContent(overscroll),
                        overscrollEffect = null,
                    ) {
                        item(key = "header") {
                            Header(
                                count = playlists.size,
                                layout = layout,
                                order = order,
                                filter = filter,
                                backdrop = backdrop,
                                title = title,
                                collapse = collapse,
                                onOrder = { onOrderChosen(it) },
                                onFilter = { filter = it },
                                onSort = { sortOpen = true },
                                onSortAnchor = { sortAnchor = it },
                            )
                        }

                        if (artists.isNotEmpty() && shownFilter == Filter.ALL) {
                            item(key = "artists") { ArtistShelf(artists, onOpenArtist) }
                        }

                        items(playlists, key = { it.uri }) { playlist ->
                            ListRow(
                                playlist,
                                pinned = playlist.uri in pinned,
                                onClick = { open(playlist, artists, onOpenPlaylist, onOpenArtist) },
                                contextActions = playlistActions(playlist),
                            )
                        }
                    }
                }
                }
            }

            // The app's own menu rather than Material's card: see GlassMenu.
            // The same one the track sort on a playlist page opens, for the
            // same reason — a menu that arrives from another design system is
            // the one surface on screen that says so.
            GlassChoiceMenu(
                visible = sortOpen,
                anchor = sortAnchor.leftOf(density),
                backdrop = rememberCombinedBackdrop(backdrop, listBackdrop),
                onDismiss = { sortOpen = false },
            ) {
                Order.entries.forEach { entry ->
                    GlassChoiceItem(
                        stringResource(entry.label),
                        selected = entry == order,
                    ) {
                        sortOpen = false
                        onOrderChosen(entry)
                    }
                }
                GlassMenuRule()

                // Deliberately leaves the menu open: the direction is the one
                // choice people flip back and forth to compare, and reopening
                // for each flip makes a sort feel heavy.
                GlassChoiceItem(
                    stringResource(R.string.sort_descending),
                    selected = descending,
                ) {
                    descending = !descending
                    view.descending = descending
                }
            }

            // Last, so it stands over the shelves rather than under them.
            FluidCollapsingTopBar(
                title = title,
                collapse = collapse,
                backdrop = barBackdrop,
                // The app is locked to a dark theme and every page stands on a
                // blurred cover, so the family's own film — which lightens by
                // design — made the bar the brightest thing on the screen. This
                // one darkens toward black, which is what a scrim over a picture
                // is for. See GlassDefaults.darkBarTint.
                barTint = dev.antigravity.fluidengine.ui.fluid.GlassDefaults.darkBarTint(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                // In the bar rather than in the header, and that is the whole
                // reason the bar has an actions slot: the heading scrolls away
                // now, and a control that goes with it is a control you have to
                // scroll back for. Making a playlist and changing the shelf are
                // not that kind of control.
                //
                // Only where a playlist can actually be made: on a source with
                // no account signed in, a plus that always failed would be
                // worse than no plus at all.
                if (canEdit) {
                    dev.antigravity.fluidengine.ui.fluid.FluidBarAction(
                        icon = PhosphorIcons.Regular.Plus,
                        contentDescription = stringResource(R.string.new_playlist),
                        onClick = onCreatePlaylist,
                    )
                }
                // One button that swaps between the two arrangements rather
                // than a pair of them: with two options, a toggle showing the
                // *other* one is both smaller and unambiguous.
                dev.antigravity.fluidengine.ui.fluid.FluidBarAction(
                    icon = if (layout == Layout.GRID) PhosphorIcons.Regular.ListBullets
                    else PhosphorIcons.Regular.SquaresFour,
                    contentDescription = stringResource(R.string.change_layout),
                    onClick = {
                        layout = if (layout == Layout.GRID) Layout.LIST else Layout.GRID
                        view.grid = layout == Layout.GRID
                    },
                )
            }
            }
        }
    }
}

/** Where a menu hanging off the sort button's right edge goes. */
private fun androidx.compose.ui.unit.IntOffset.leftOf(
    density: androidx.compose.ui.unit.Density,
): androidx.compose.ui.unit.IntOffset {
    val width = with(density) {
        CHOICE_MENU_WIDTH.roundToPx()
    }
    val gap = with(density) { 8.dp.roundToPx() }
    return androidx.compose.ui.unit.IntOffset((x - width + gap).coerceAtLeast(gap), y + gap)
}

/**
 * Opens whatever the tile stands for.
 *
 * The grid holds playlists, albums and, under the artists chip, artists. The
 * first two are track lists and open the same way; an artist is a page of its
 * own, and the only thing that knows which is which is the URI.
 */
private fun open(
    item: CatalogPlaylist,
    artists: List<dev.lelonio.square.data.SearchItem>,
    onOpenPlaylist: (CatalogPlaylist) -> Unit,
    onOpenArtist: (dev.lelonio.square.data.SearchItem) -> Unit,
) {
    val artist = artists.firstOrNull { it.uri == item.uri }
    if (artist != null) onOpenArtist(artist) else onOpenPlaylist(item)
}

@Composable
private fun Header(
    count: Int,
    layout: Layout,
    order: Order,
    filter: Filter,
    backdrop: Backdrop,
    title: String,
    /** The heading's handover; the type itself is the engine's to draw. */
    collapse: FluidTitleCollapse,
    onOrder: (Order) -> Unit,
    onFilter: (Filter) -> Unit,
    onSort: () -> Unit,
    onSortAnchor: (androidx.compose.ui.unit.IntOffset) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                FluidCollapsingTitle(title, collapse)
                // Counting whatever the chips are showing, and saying so: the
                // same number labelled "playlists" under the albums chip is a
                // line that contradicts the screen it sits on.
                Text(
                    stringResource(filter.count, count),
                    style = MaterialTheme.typography.bodySmall,
                    color = InkDim,
                )
            }

        }

        // The chips say what is being shown; the sort sits at the end of the
        // same row as a menu, because it is a choice among three that is made
        // once and then left alone.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Scrollable, because there are five of them now and "Scaricati"
            // does not fit across a phone beside the other four. The sort
            // button stays put at the end of the row, where it has always been.
            Row(
                Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Filter.entries.forEach { entry ->
                    dev.lelonio.square.ui.components.GlassChip(
                        label = stringResource(entry.label),
                        selected = entry == filter,
                        onClick = { onFilter(entry) },
                    )
                }
                Spacer(Modifier.width(8.dp))
            }

            LiquidButton(
                onClick = onSort,
                backdrop = backdrop,
                flat = true,
                contentHeight = 36.dp,
                contentPadding = 12.dp,
                modifier = Modifier.onGloballyPositioned {
                    val bounds = it.boundsInRoot()
                    onSortAnchor(
                        androidx.compose.ui.unit.IntOffset(
                            bounds.right.toInt(),
                            bounds.bottom.toInt(),
                        ),
                    )
                },
            ) {
                Icon(
                    PhosphorIcons.Regular.ArrowsDownUp,
                    contentDescription = stringResource(R.string.sort),
                    tint = InkDim,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun GridTile(
    playlist: CatalogPlaylist,
    pinned: Boolean,
    onClick: () -> Unit,
    contextActions: List<dev.antigravity.fluidengine.ui.fluid.FluidContextAction>,
) {
    // The engine's context menu: a long press lifts this very tile in place
    // and grows the actions from its nearest corner. The anchor form, because
    // the tile already answers taps of its own.
    val contextMenu = dev.antigravity.fluidengine.ui.fluid.rememberFluidContextMenu(
        actions = { contextActions },
    )
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Column(
        Modifier
            .fluidContextMenuAnchor(contextMenu)
            .pressable(
                onClick,
                onLongClick = {
                    if (contextMenu.open()) {
                        haptics.performHapticFeedback(
                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                        )
                    }
                },
            ),
    ) {
        PlaylistCover(
            playlist = playlist,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .softShadow(RoundedCornerShape(20.dp), elevation = 18.dp, spot = 0.4f),
            corner = 20.dp,
            decodeSize = 220.dp,
        )
        Row(
            Modifier.padding(top = 10.dp, start = 2.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pinned) PinMark(Modifier.padding(end = 6.dp))
            Text(
                playlist.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ListRow(
    playlist: CatalogPlaylist,
    pinned: Boolean,
    onClick: () -> Unit,
    contextActions: List<dev.antigravity.fluidengine.ui.fluid.FluidContextAction>,
) {
    // See GridTile: the engine lifts the pressed row itself.
    val contextMenu = dev.antigravity.fluidengine.ui.fluid.rememberFluidContextMenu(
        actions = { contextActions },
    )
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .fluidContextMenuAnchor(contextMenu)
            .pressable(
                onClick,
                shape = RoundedCornerShape(16.dp),
                pressedScale = 0.98f,
                onLongClick = {
                    if (contextMenu.open()) {
                        haptics.performHapticFeedback(
                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                        )
                    }
                },
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlaylistCover(
            playlist = playlist,
            modifier = Modifier
                .size(52.dp)
                .softShadow(RoundedCornerShape(14.dp), elevation = 10.dp),
            corner = 14.dp,
            decodeSize = 52.dp,
        )
        Text(
            playlist.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = 16.dp)
                .weight(1f),
        )
        if (pinned) PinMark()
    }
}

/** The quiet mark on a pinned row. Small: the position is the message. */
@Composable
private fun PinMark(modifier: Modifier = Modifier) {
    Icon(
        PhosphorIcons.Fill.PushPin,
        contentDescription = stringResource(R.string.pinned),
        tint = InkDim,
        modifier = modifier.size(14.dp),
    )
}

/** A glass pill, for the handful of places that need a button at all. */
@Composable
private fun GlassAction(label: String, backdrop: Backdrop, onClick: () -> Unit) {
    LiquidButton(
        onClick = onClick,
        backdrop = backdrop,
        contentHeight = 50.dp,
        contentPadding = 26.dp,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = Ink)
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

/** A harder film for the chip that is on, matching the home page. */
private val SelectedFilm = Color.White.copy(alpha = 0.26f)

/**
 * The artists the account follows, along the top of the library.
 *
 * Round, and in a row that scrolls sideways: it is the shape Spotify made mean
 * "a person" and the one that keeps a long list from pushing the playlists off
 * the screen. Part of the list rather than pinned above it, so it scrolls away
 * with everything else — a shelf that will not leave is a shelf in the way.
 */
@Composable
private fun ArtistShelf(
    artists: List<dev.lelonio.square.data.SearchItem>,
    onOpen: (dev.lelonio.square.data.SearchItem) -> Unit,
) {
    Column(Modifier.padding(bottom = 18.dp)) {
        Text(
            stringResource(R.string.artists_you_follow),
            style = MaterialTheme.typography.titleSmall,
            color = Ink,
            modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(artists, key = { it.uri }) { artist ->
                Column(
                    Modifier
                        .width(76.dp)
                        .pressable({ onOpen(artist) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Artwork(
                        url = artist.artworkUrl,
                        title = artist.title,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .softShadow(CircleShape, elevation = 10.dp),
                        corner = 36.dp,
                        decodeSize = 72.dp,
                    )
                    Text(
                        artist.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}
