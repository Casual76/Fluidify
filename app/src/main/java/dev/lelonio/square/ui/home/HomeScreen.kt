package dev.lelonio.square.ui.home

import dev.antigravity.fluidengine.ui.fluid.fluidOverscrollContent
import dev.antigravity.fluidengine.ui.fluid.fluidOverscrollEdge
import dev.antigravity.fluidengine.ui.fluid.rememberFluidEdgeOverscroll
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.glassSurface
import dev.antigravity.fluidengine.ui.fluid.rememberCombinedGlassBackdrop
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.zIndex
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import dev.lelonio.square.ui.glass.backdrop.Backdrop
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.SpotifyLogo
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.material3.Icon
import dev.lelonio.square.R
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.data.SearchItem
import dev.lelonio.square.data.sortedByRecentlyOpened
import dev.lelonio.square.ui.MainViewModel
import dev.lelonio.square.ui.components.AppIcon
import dev.lelonio.square.ui.components.AppLockup
import dev.lelonio.square.ui.components.Artwork
import dev.lelonio.square.ui.components.PlaylistCover
import dev.lelonio.square.ui.components.FluidifyWordmark
import androidx.compose.ui.layout.layout
import dev.lelonio.square.ui.glass.LiquidButton
import dev.lelonio.square.ui.glass.pressable
import dev.lelonio.square.ui.player.GlassFilm
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import dev.lelonio.square.ui.theme.softShadow
import java.util.Calendar

/**
 * What the feed is showing.
 *
 * Chips rather than a second tab bar: they sit inside the page, under the name,
 * and "Tutto" is genuinely the whole thing rather than a fourth view. The
 * sections keep their order in every one of them, so switching never rearranges
 * what stays on screen.
 */
private enum class Feed(@StringRes val label: Int) {
    ALL(R.string.feed_all),
    FOR_YOU(R.string.feed_for_you),
    LIBRARY(R.string.library),
    RELEASES(R.string.feed_releases),
    ARTISTS(R.string.artists),
}

/**
 * The home page.
 *
 * Rewritten from scratch rather than adjusted. The previous version had grown
 * from a plain Material list — filled buttons, section headings, a progress
 * spinner in the middle of the page — and adding glass cards on top of it left
 * two design languages sharing a screen. Everything here is drawn on the same
 * material as the bars and the player: no Material containers, no elevation, no
 * accent-filled buttons.
 */
@Composable
fun HomeScreen(
    state: MainViewModel.UiState,
    contentPadding: PaddingValues,
    /**
     * The artwork wash on its own, for the bar that takes the mark over.
     *
     * Not the page-wide recording: that one contains this bar, and glass
     * blurring a photograph of itself is the one thing this material cannot do.
     */
    ground: dev.antigravity.fluidengine.ui.fluid.GlassBackdropState,
    onLogIn: () -> Unit,
    onRetry: () -> Unit,
    onLogOut: () -> Unit,
    onOpenPlaylist: (CatalogPlaylist) -> Unit,
    /** URIs most recently opened first; see PlaylistOrderStore. */
    playlistOrder: List<String>,
    recent: List<CatalogTrack>,
    onPlayRecent: (List<CatalogTrack>, Int) -> Unit,
    /** Plays a row of the feed, told which row it was so the player can say so. */
    onPlayFeed: (List<CatalogTrack>, Int, String) -> Unit,
    feed: MainViewModel.FeedState,
    onOpenItem: (SearchItem) -> Unit,
    onOpenSettings: () -> Unit,
    /** Friend activity, for the faces beside the account picture. */
    friends: List<dev.lelonio.square.data.FriendListen> = emptyList(),
    onOpenFriends: () -> Unit = {},
    /** The layer the glass on this page refracts; see the note in SquareApp. */
    backdrop: Backdrop,
    onPlayTrending: (List<CatalogTrack>, Int) -> Unit = { _, _ -> },
    /**
     * Spotify's own personalised shelves, empty when the gateway said nothing.
     *
     * Shown above the rows this app builds itself. They are what the listener
     * recognises as their home page, and unlike everything below them they
     * cannot be reconstructed from the account's playlists.
     */
    shelves: List<dev.lelonio.square.data.HomeShelf> = emptyList(),
) {
    when (state) {
        MainViewModel.UiState.LoggedOut -> Centered {
            AppIcon(84.dp)
            FluidifyWordmark(height = 28.dp)
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
            // Saved rather than remembered, like the scroll position below it:
            // both are undone by leaving for a playlist and coming back, and a
            // page that forgets which shelf you were reading is the same
            // complaint as one that forgets where you were in it.
            var filterName by rememberSaveable { mutableStateOf(Feed.ALL.name) }
            val filter = Feed.entries.firstOrNull { it.name == filterName } ?: Feed.ALL
            // Read once here rather than inside the rows: the label travels
            // with the play so the player can say where the song came from,
            // and that is a plain string by the time it leaves this screen.
            val onRepeatLabel = stringResource(R.string.on_repeat)
            val classicsLabel = stringResource(R.string.all_time_favourites)
            // Spotify's rootlist arrives in the order the account added them,
            // which for an old account is close to arbitrary — the playlist
            // opened every day can sit thirtieth.
            val playlists = remember(state.playlists, playlistOrder) {
                // The phone's own music belongs to the library rather than to
                // this page: home is what the service put together for the
                // listener, and a folder of files is not that.
                state.playlists
                    .filterNot { dev.lelonio.square.data.LocalLibrary.isLocalContext(it.uri) }
                    .sortedByRecentlyOpened(playlistOrder)
            }
            val listState = rememberLazyListState()

            // Where the mark rests, and how far it has to go.
            //
            // The header is a list item now, so its position is the scroll's to
            // decide; the only thing measured here is where it starts, and it is
            // only believed while the list is actually at its top. Everything
            // else — the travel, the progress, the docked place — falls out of
            // that one number and the bar's own height.
            val density = LocalDensity.current
            val statusBar = contentPadding.calculateTopPadding()
            val barHeight = statusBar +
                dev.antigravity.fluidengine.ui.fluid.FluidScreenDefaults.ControlRowHeight
            var restingTopPx by remember { mutableFloatStateOf(Float.NaN) }
            var lockupHeightPx by remember { mutableFloatStateOf(0f) }
            val dockedCentrePx = with(density) {
                (statusBar + dev.antigravity.fluidengine.ui.fluid.FluidScreenDefaults
                    .ControlRowHeight / 2).toPx()
            }
            // The distance between the mark's two homes, measured centre to
            // centre. NaN until the header has been laid out once at the top.
            val travelPx = restingTopPx + lockupHeightPx / 2f - dockedCentrePx

            // How far along that travel the scroll has taken it, 0 to 1.
            //
            // Read from the list rather than driven by a nested-scroll
            // connection: the list keeps its own fling untouched. Past the first
            // item the mark is simply docked — asking for the exact offset of
            // something scrolled far off screen means measuring items that no
            // longer exist.
            val collapse by remember(travelPx) {
                derivedStateOf {
                    if (listState.firstVisibleItemIndex > 0) 1f
                    else if (!travelPx.isFinite() || travelPx <= 1f) 0f
                    else (listState.firstVisibleItemScrollOffset / travelPx).coerceIn(0f, 1f)
                }
            }

            // What this page's own body looks like, on its own. With the ground
            // under it that is an opaque image containing no chrome, which is
            // the only thing the bar above is allowed to blur.
            val bodyGlass = dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop()
            val barBackdrop = dev.antigravity.fluidengine.ui.fluid.rememberCombinedGlassBackdrop(
                ground,
                bodyGlass,
            )

            // Back to the top when the view changes. The lists have nothing in
            // common, so keeping the old offset drops the new one in the middle
            // of itself.
            //
            // The first run is skipped, and that is the whole point: an effect
            // keyed on the filter also runs when this screen is composed, which
            // is every time the listener comes back from a playlist. The page
            // they left halfway down was being scrolled to the top under them
            // for a filter that had not changed at all.
            var scrolledFor by rememberSaveable { mutableStateOf(filter.name) }
            LaunchedEffect(filter) {
                if (scrolledFor != filter.name) {
                    scrolledFor = filter.name
                    listState.scrollToItem(0)
                }
            }

            val overscroll = rememberFluidEdgeOverscroll()

            Box(Modifier.fillMaxSize()) {
                AnimatedContent(
                    targetState = filter,
                    transitionSpec = {
                        // Short and vertical: the sections do not move sideways
                        // when they are filtered, so neither should the change.
                        (fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 14 })
                            .togetherWith(fadeOut(tween(120)))
                    },
                    label = "feed",
                ) { current ->
                // "Everything" means Spotify's own home when there is one.
                //
                // The sections below it are this app standing in for a home
                // page it could not read: what the account plays most, what it
                // came back to, new releases. Once the real one arrives they
                // are a second, worse answer to the same question, printed
                // underneath the first. They keep their own chips, so nothing
                // is lost — only the pile on the front page.
                val ownFeed = shelves.isEmpty()
                val showReleases = (current == Feed.ALL && ownFeed) || current == Feed.RELEASES
                val showArtists = (current == Feed.ALL && ownFeed) || current == Feed.ARTISTS
                val showLibrary = (current == Feed.ALL && ownFeed) || current == Feed.LIBRARY
                val showForYou = (current == Feed.ALL && ownFeed) || current == Feed.FOR_YOU
                val filter = current

                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .fluidOverscrollEdge(overscroll)
                        .glassBackdropSource(bodyGlass)
                        .fluidOverscrollContent(overscroll),
                    state = listState,
                    // The bar over this list is glass, not a lid: it has to have
                    // the page's own top under it to refract, so the list starts
                    // at the top of the screen and the header inside it makes
                    // the room instead.
                    contentPadding = PaddingValues(
                        bottom = contentPadding.calculateBottomPadding(),
                    ),
                    overscrollEffect = null,
                ) {
                    // The header, as the first thing IN the page rather than
                    // chrome bolted over it. It used to shrink in place and stay
                    // — which meant the filters were always reachable and the
                    // top of the page was never the top of the page. Now it goes
                    // where the page goes, and the mark it carries is picked up
                    // by the bar on the way past.
                    item(key = "header", contentType = "header") {
                        Header(
                            name = state.displayName,
                            avatarUrl = state.avatarUrl,
                            service = R.string.backend_spotify,
                            serviceIcon = PhosphorIcons.Regular.SpotifyLogo,
                            // The chip that is lit is the one that was tapped.
                            // It used to follow whichever section the scroll had
                            // reached, which read as a control changing itself:
                            // the chips look like a filter, so a lit one has to
                            // mean "this is what you are looking at because you
                            // asked for it".
                            highlighted = filter,
                            backdrop = backdrop,
                            topPadding = barHeight,
                            onFilter = { filterName = it.name },
                            onOpenSettings = onOpenSettings,
                            friends = friends,
                            onOpenFriends = onOpenFriends,
                            onLockupPlaced = { top, height ->
                                // Believed only at the top: everywhere else this
                                // is the scroll's answer, not the layout's.
                                if (listState.firstVisibleItemIndex == 0 &&
                                    listState.firstVisibleItemScrollOffset == 0
                                ) {
                                    restingTopPx = top
                                    lockupHeightPx = height
                                }
                            },
                        )
                    }
                // Spotify's own shelves come first, because they are what the
                // listener recognises as their home and the only rows here that
                // this app could not have built itself. What the account has
                // been playing follows: it is worth having, and it is also the
                // same answer for weeks at a time.
                //
                // Every feed section keeps its place while it is still being
                // fetched. The account's playlists and its recent tracks are
                // held on the device and draw at once; everything else is a Web
                // API round trip a second or two behind, and without a
                // placeholder the page arrived in two halves and shifted under
                // whatever was being read.
                if (filter == Feed.ALL) {
                    shelves.forEach { shelf ->
                        item(contentType = "shelf") { Heading(shelf.title) }
                        item(contentType = "shelf") {
                            Carousel(shelf.items, key = { it.uri }) { entry ->
                                PlaylistTile(entry) { onOpenPlaylist(entry) }
                            }
                        }
                    }
                }

                if (showForYou && feed.topTracks.isEmpty() && feed.loading) {
                    item(contentType = "skeleton") {
                        SkeletonSection(tileWidth = 152.dp, tileHeight = 152.dp)
                    }
                }

                if (showForYou && feed.topTracks.isNotEmpty()) {
                    item(contentType = Feed.FOR_YOU.name) { Heading(stringResource(R.string.on_repeat)) }
                    item(contentType = Feed.FOR_YOU.name) {
                        TrackRow(feed.topTracks) { index ->
                            onPlayFeed(feed.topTracks, index, onRepeatLabel)
                        }
                    }
                }

                if (showForYou && feed.jumpBackIn.isEmpty() && feed.loading) {
                    item(contentType = "skeleton") {
                        SkeletonSection(tileWidth = 152.dp, tileHeight = 152.dp)
                    }
                }

                if (showForYou && feed.jumpBackIn.isNotEmpty()) {
                    item(contentType = Feed.FOR_YOU.name) { Heading(stringResource(R.string.jump_back_in)) }
                    item(contentType = Feed.FOR_YOU.name) {
                        Carousel(feed.jumpBackIn, key = { it.uri }) { item ->
                            FeedTile(item) { onOpenItem(item) }
                        }
                    }
                }

                if (showLibrary || (filter == Feed.ALL && shelves.isEmpty())) {
                    item(contentType = Feed.LIBRARY.name) { Heading(stringResource(R.string.your_playlists)) }
                    item(contentType = Feed.LIBRARY.name) {
                        Carousel(
                            playlists.take(if (showLibrary) LIBRARY_SIZE else CAROUSEL_SIZE),
                            key = { it.uri },
                        ) { playlist ->
                            PlaylistTile(playlist) { onOpenPlaylist(playlist) }
                        }
                    }
                }

                if (showReleases && feed.newReleases.isEmpty() && feed.loading) {
                    item(contentType = "skeleton") {
                        SkeletonSection(tileWidth = 0.dp, tileHeight = 200.dp, card = true)
                    }
                }

                if (showReleases && feed.newReleases.isNotEmpty()) {
                    item(contentType = Feed.RELEASES.name) { Heading(stringResource(R.string.feed_releases)) }
                    // Cards rather than another row of thumbnails. A carousel
                    // says "here is a list, pick one"; this is meant to be
                    // looked at, so each release gets the width of the page and
                    // the cover carries it.
                    items(
                        feed.newReleases.take(FEED_SIZE),
                        key = { it.uri },
                        contentType = { Feed.RELEASES.name },
                    ) { item ->
                        FeedCard(item) { onOpenItem(item) }
                    }
                }

                // Novità, but only from artists the account listens to. Sits
                // under the catalogue-wide releases on purpose: it is the
                // narrower of the two and the one worth reaching first.
                if ((showReleases || showForYou) && feed.fromYourArtists.isNotEmpty()) {
                    item(contentType = Feed.FOR_YOU.name) { Heading(stringResource(R.string.new_from_your_artists)) }
                    item(contentType = Feed.FOR_YOU.name) {
                        Carousel(feed.fromYourArtists, key = { it.uri }) { item ->
                            FeedTile(item) { onOpenItem(item) }
                        }
                    }
                }

                if (showArtists && feed.topArtists.isEmpty() && feed.loading) {
                    item(contentType = "skeleton") {
                        SkeletonSection(tileWidth = 120.dp, tileHeight = 120.dp, round = true)
                    }
                }

                if (showArtists && feed.topArtists.isNotEmpty()) {
                    item(contentType = Feed.ARTISTS.name) { Heading(stringResource(R.string.listening_artists)) }
                    // A grid rather than a carousel. A row shows three artists
                    // and hides the rest behind a sideways scroll nobody makes
                    // twice; the same list down the page is read at a glance.
                    // Rows of a list rather than a nested grid, which cannot go
                    // inside a scrolling column without being given a height.
                    items(
                        feed.topArtists.chunked(ARTIST_COLUMNS),
                        key = { row -> row.first().uri },
                        contentType = { Feed.ARTISTS.name },
                    ) { row ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            row.forEach { artist ->
                                ArtistCell(artist, Modifier.weight(1f)) { onOpenItem(artist) }
                            }
                            // The last row keeps the others\' spacing instead of
                            // stretching two artists across the page.
                            repeat(ARTIST_COLUMNS - row.size) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }

                if (recent.isNotEmpty() && filter != Feed.ARTISTS) {
                    item(contentType = Feed.LIBRARY.name) { Heading(stringResource(R.string.play_again)) }
                    item(contentType = Feed.LIBRARY.name) {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(top = 14.dp),
                        ) {
                            itemsIndexed(recent, key = { _, track -> track.uri }) { index, track ->
                                TrackTile(track) { onPlayRecent(recent, index) }
                            }
                        }
                    }
                }

                // Last, because it is the least of a surprise: everything above
                // changes month to month, this is the account's own constants.
                if (showForYou && feed.allTimeTracks.isNotEmpty()) {
                    item(contentType = Feed.FOR_YOU.name) { Heading(stringResource(R.string.all_time_favourites)) }
                    item(contentType = Feed.FOR_YOU.name) {
                        TrackRow(feed.allTimeTracks) { index ->
                            onPlayFeed(feed.allTimeTracks, index, classicsLabel)
                        }
                    }
                }

                item(contentType = "tail") { Box(Modifier.height(24.dp)) }
                }
                }

                // The bar the mark docks into: clear at the top of the page,
                // glass once the page has moved under the status bar.
                HomeTopBar(
                    backdrop = barBackdrop,
                    plate = backdrop,
                    height = barHeight,
                    statusBar = statusBar,
                    collapse = { collapse },
                    restingTopPx = { restingTopPx },
                    contentTranslation = { overscroll.offsetPx },
                )
            }
        }
    }
}

/**
 * The mark, drawn once, on its way between its two homes.
 *
 * The copy in the header only reserves the room; this is the one that is seen,
 * transformed onto the resting place while the page is at its top and onto the
 * middle of the bar once it has scrolled away. One piece travelling, never two
 * taking turns — the same handover the engine gives a title, for a mark that is
 * drawn rather than typed and therefore has no baseline to hang it on.
 */
@Composable
private fun HomeTopBar(
    backdrop: dev.antigravity.fluidengine.ui.fluid.GlassBackdropState,
    plate: Backdrop,
    height: Dp,
    statusBar: Dp,
    collapse: () -> Float,
    restingTopPx: () -> Float,
    contentTranslation: () -> Float,
) {
    val density = LocalDensity.current
    val restingLeftPx = with(density) { HeaderSidePadding.toPx() }
    val dockedTopPx = with(density) {
        (statusBar + (dev.antigravity.fluidengine.ui.fluid.FluidScreenDefaults.ControlRowHeight -
            LockupHeight * DockedLockupScale) / 2).toPx()
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(height + dev.antigravity.fluidengine.ui.fluid.FluidScreenDefaults.GlassFadeTail),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .glassSurface(
                    state = backdrop,
                    tint = dev.antigravity.fluidengine.ui.fluid.GlassDefaults.barTint(),
                    edge = dev.antigravity.fluidengine.ui.fluid.GlassEdge.None,
                    falloff = dev.antigravity.fluidengine.ui.fluid.GlassFalloff.FadeDown,
                    // A dead zone first: a bar that frosts on the first pixel of
                    // a scroll frosts on a touch that was going nowhere.
                    intensity = {
                        val p = ((collapse() - 0.16f) / 0.84f).coerceIn(0f, 1f)
                        p * p * (3f - 2f * p)
                    },
                ),
        )
        // Measured so the docked pose can be centred on the page rather than on
        // a guess at how wide the mark is.
        var lockupWidthPx by remember { mutableFloatStateOf(0f) }
        val fullWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
        AppLockup(
            iconSize = 44.dp,
            nameHeight = 22.dp,
            plate = plate,
            modifier = Modifier
                .onSizeChanged { lockupWidthPx = it.width.toFloat() }
                .graphicsLayer {
                    val p = collapse().coerceIn(0f, 1f)
                    val eased = p * p * (3f - 2f * p)
                    val scale = 1f - (1f - DockedLockupScale) * eased
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = scale
                    scaleY = scale
                    val centred = (fullWidthPx - lockupWidthPx * scale) / 2f
                    translationX = restingLeftPx + (centred - restingLeftPx) * eased
                    val resting = restingTopPx()
                    val from = if (resting.isFinite()) resting else dockedTopPx
                    // Never above the docked place: the mark is content while it
                    // is in the page, and content may ride the elastic edge, but
                    // nothing rides it into the status bar.
                    translationY = (
                        dockedTopPx + (from - dockedTopPx) * (1f - p) +
                            contentTranslation() * (1f - eased)
                        ).coerceAtLeast(dockedTopPx)
                },
        )
    }
}

/** How much smaller the mark is once it has docked in the bar. */
private const val DockedLockupScale = 0.62f

/** The mark's own height in the header, icon included. */
private val LockupHeight = 44.dp

/** Where the header's row starts, which is where the mark rests. */
private val HeaderSidePadding = 24.dp

/**
 * Name, picture and filters, at the top of the page.
 *
 * It used to shrink in place and stay, on the argument that the filter chips are
 * a control and a control you have to scroll back to may as well not be there.
 * The cost was that the top of the page was never the top of the page: three
 * rows of chrome sat over every list, permanently. It scrolls away now, and the
 * way back is a flick — the bar below unfolds on the first upward scroll rather
 * than only at the top, so the chips are one gesture from anywhere.
 *
 * What it keeps is the mark's resting place, reported through [onLockupPlaced]:
 * the copy here only holds the room, and the one that is seen is drawn by the
 * bar. See [HomeTopBar].
 */
@Composable
private fun Header(
    modifier: Modifier = Modifier,
    name: String,
    avatarUrl: String?,
    /** Which service the page is showing: see the note on the line it draws. */
    @StringRes service: Int,
    /** Its mark, so the source is recognisable before the line is read. */
    serviceIcon: androidx.compose.ui.graphics.vector.ImageVector,
    /**
     * Where the mark ended up, in the window's own pixels, and how tall it is.
     *
     * Reported rather than computed: the row above it is a line of type whose
     * height is the font's business, not this file's, and a resting place
     * guessed from paddings is a resting place that drifts with the text size.
     */
    onLockupPlaced: (top: Float, height: Float) -> Unit,
    /** The chip drawn as active: the one that was tapped. */
    highlighted: Feed,
    backdrop: Backdrop,
    topPadding: Dp,
    onFilter: (Feed) -> Unit,
    onOpenSettings: () -> Unit,
    /** Who the account follows and what they play; empty hides the control. */
    friends: List<dev.lelonio.square.data.FriendListen> = emptyList(),
    onOpenFriends: () -> Unit = {},
    /**
     * False on a page with no feed to filter.
     *
     * The chips are the one part of this header that is Spotify's: they pick
     * between sections built out of that account's data, and YouTube's page is
     * whatever shelves the service itself sent.
     */
    showFilters: Boolean = true,
) {
    Column(
        modifier
            .fillMaxWidth()
            // Seats the header on the page instead of leaving it floating over
            // whatever the list has scrolled underneath it.
            .background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.45f),
                    1f to Color.Transparent,
                ),
            )
            .padding(top = topPadding),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp)
                .padding(top = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                // The account, and which service it is an account for.
                //
                // Small and above rather than large and below, which is where
                // the name used to be: whose account it is changes nothing about
                // the page, while *which service* changes everything on it — the
                // catalogue, the playlists, what the player can do — and that
                // was nowhere on screen. Together they read as one line of
                // provenance over the app's own name.
                //
                // Height goes with the alpha, so the collapsed header is a bar
                // rather than a bar with a blank line in it.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        serviceIcon,
                        contentDescription = stringResource(service),
                        tint = InkDim,
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .size(15.dp),
                    )
                    Text(
                        listOfNotNull(
                            stringResource(service),
                            name.takeIf { it.isNotBlank() },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelLarge,
                        color = InkDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // The app's own name, at the size the account name used to be.
                // It is what the page is, and unlike the account it is worth
                // reading at a glance; it shrinks a little as the header
                // collapses rather than leaving, because a bar with nothing in
                // it says nothing about where you are.
                // The room the mark needs, and nothing else: the visible copy
                // is the bar's, transformed onto exactly this place while the
                // page is at its top. Two copies drawn at once is the flicker
                // this arrangement exists to avoid.
                AppLockup(
                    // Larger than the bare mark was: the glass around it is
                    // part of the shape now, and the drawing inside has to stay
                    // the size it was to read at a glance.
                    iconSize = 44.dp,
                    nameHeight = 22.dp,
                    plate = backdrop,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .onGloballyPositioned {
                            onLockupPlaced(
                                it.positionInWindow().y,
                                it.size.height.toFloat(),
                            )
                        }
                        .graphicsLayer { alpha = 0f },
                )
            }

            // Falls back to the generated cover keyed on the name, which is what
            // every other missing image in the app gets rather than a grey
            // circle.
            // The way into the settings. They have no tab of their own — see
            // SettingsScreen — and the account picture is where anyone looks for
            // them anyway.
            // The people the account follows, as a huddle of faces.
            //
            // Beside the account's own picture because that is what it is about
            // — who is listening — and because a list nobody has asked for does
            // not deserve a tab. Absent rather than empty when nobody is
            // playing: a control that opens onto nothing is worse than no
            // control.
            if (friends.isNotEmpty()) {
                FriendFaces(friends, onOpenFriends)
            }

            Artwork(
                url = avatarUrl,
                title = name,
                modifier = Modifier
                    .padding(start = 14.dp)
                    // Measured from the scroll rather than sized in
                    // composition; see the note on `collapse`.
                    .size(46.dp)
                    .clip(CircleShape)
                    .pressable(onOpenSettings, pressedScale = 0.90f)
                    .softShadow(CircleShape, elevation = 10.dp),
                corner = 23.dp,
                decodeSize = 46.dp,
            )
        }

        // The chips carry the header's bottom margin with them; without them
        // the list would start against the app's own name.
        if (showFilters) FilterRow(highlighted, backdrop, onFilter) else Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun FilterRow(selected: Feed, backdrop: Backdrop, onSelect: (Feed) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 14.dp, bottom = 10.dp),
    ) {
        items(Feed.entries.toList(), key = { it.name }) { entry ->
            // The engine's chip: the selected one fills with the app's own
            // amethyst — legible over any artwork now that the accent no
            // longer comes from the artwork — and the change is animated.
            dev.antigravity.fluidengine.ui.fluid.FluidChip(
                label = stringResource(entry.label),
                selected = entry == selected,
                onClick = { onSelect(entry) },
            )
        }
    }
}

/**
 * One release, the full width of the page.
 *
 * The caption sits in a pane of glass over the cover rather than under it, the
 * way the bars over the rest of the app do. Text straight on artwork needs a
 * gradient to survive a light cover, and a gradient large enough to do that
 * ends up dimming the picture it is supposed to be showing.
 */
@Composable
private fun FeedCard(item: SearchItem, onClick: () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Box(
        Modifier
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .fillMaxWidth()
            .softShadow(shape, elevation = 26.dp, spot = 0.55f)
            .clip(shape)
            .pressable(onClick, pressedScale = 0.98f),
    ) {
        // The same cover twice, and that is the point.
        //
        // Spotify serves album art at 640px and no larger, so a cover stretched
        // across a 1080px-wide card is upscaled by nearly two and looks soft —
        // which is exactly the "low quality covers" this replaced. The
        // background is allowed to be soft, so it takes the full width; the copy
        // that has to be sharp is drawn well under its native size.
        //
        // Softened by decoding it tiny and letting the upscale blur it, rather
        // than by `Modifier.blur`. That modifier renders into a layer of its own
        // which is not bound by this box's clip, so it painted a hard black
        // rectangle past the card's rounded corners and up into the header.
        Artwork(
            url = item.artworkUrl,
            title = item.title,
            modifier = Modifier.matchParentSize(),
            corner = 0.dp,
            decodeSize = 24.dp,
        )
        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.42f)))

        Column(
            Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Artwork(
                url = item.artworkUrl,
                title = item.title,
                modifier = Modifier
                    .size(COVER_SIZE)
                    .softShadow(RoundedCornerShape(18.dp), elevation = 24.dp, spot = 0.5f),
                corner = 18.dp,
                decodeSize = COVER_SIZE,
            )
            Text(
                item.title,
                style = MaterialTheme.typography.headlineLarge,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 18.dp),
            )
            if (item.subtitle.isNotBlank()) {
                Text(
                    item.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.76f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

/** How many artists fit across the page without the names going to three lines. */
private const val ARTIST_COLUMNS = 3

/**
 * One cell of the artist grid: the same round portrait, sized by its column.
 *
 * Separate from [ArtistTile], which is fixed at the carousel's width because a
 * horizontal row has no column to take its width from.
 */
@Composable
private fun ArtistCell(artist: SearchItem, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.pressable(onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Artwork(
            url = artist.artworkUrl,
            title = artist.title,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .softShadow(CircleShape, elevation = 14.dp),
            corner = 200.dp,
            decodeSize = 160.dp,
        )
        Text(
            artist.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

/** Round, because that is how every music app has drawn an artist for a decade. */
@Composable
private fun ArtistTile(artist: SearchItem, onClick: () -> Unit) {
    Column(
        Modifier
            .width(110.dp)
            .pressable(onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Artwork(
            url = artist.artworkUrl,
            title = artist.title,
            modifier = Modifier
                .size(110.dp)
                .softShadow(CircleShape, elevation = 14.dp),
            corner = 55.dp,
            decodeSize = 110.dp,
        )
        Text(
            artist.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
private fun PlaylistTile(playlist: CatalogPlaylist, onClick: () -> Unit) {
    Column(
        Modifier
            .width(152.dp)
            .pressable(onClick),
    ) {
        PlaylistCover(
            playlist = playlist,
            modifier = Modifier
                .size(152.dp)
                .softShadow(RoundedCornerShape(20.dp), elevation = 18.dp),
            corner = 20.dp,
        )
        Text(
            playlist.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp, start = 2.dp, end = 2.dp),
        )
    }
}

@Composable
private fun TrackTile(track: CatalogTrack, onClick: () -> Unit) {
    Column(
        // No clip on the column: it would cut off the artwork's drop shadow,
        // which spreads wider than the tile itself.
        Modifier
            .width(128.dp)
            .pressable(onClick),
    ) {
        Artwork(
            url = track.artworkUrl,
            title = track.name,
            modifier = Modifier
                .size(128.dp)
                .softShadow(RoundedCornerShape(18.dp), elevation = 14.dp),
            corner = 18.dp,
            decodeSize = 128.dp,
        )
        Text(
            track.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp, start = 2.dp, end = 2.dp),
        )
        Text(
            track.artist,
            style = MaterialTheme.typography.bodySmall,
            color = InkDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp, end = 2.dp),
        )
    }
}

/** A row of tracks that play where they are tapped. */
/**
 * A section's shape, before the section has arrived.
 *
 * Deliberately still — no shimmer. A sweep across half the page draws the eye
 * to the part of it that has nothing to look at, and these are gone within a
 * second or two anyway. What they are for is the layout: holding the space
 * means the rows that land later land in the place they already occupied.
 */
@Composable
private fun SkeletonSection(
    tileWidth: Dp,
    tileHeight: Dp,
    round: Boolean = false,
    card: Boolean = false,
) {
    val fill = Ink.copy(alpha = 0.07f)
    Column(Modifier.padding(top = 22.dp)) {
        // Stands in for the heading.
        Box(
            Modifier
                .padding(horizontal = 20.dp)
                .width(140.dp)
                .height(20.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(fill),
        )
        if (card) {
            Box(
                Modifier
                    .padding(horizontal = 20.dp, vertical = 14.dp)
                    .fillMaxWidth()
                    .height(tileHeight)
                    .clip(RoundedCornerShape(28.dp))
                    .background(fill),
            )
        } else {
            Row(
                Modifier
                    .padding(start = 20.dp, top = 14.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                repeat(SKELETON_TILES) {
                    Box(
                        Modifier
                            .width(tileWidth)
                            .height(tileHeight)
                            .clip(if (round) CircleShape else RoundedCornerShape(18.dp))
                            .background(fill),
                    )
                }
            }
        }
    }
}

/** Enough to reach the edge of the screen, which is all a placeholder row is. */
private const val SKELETON_TILES = 3

@Composable
private fun TrackRow(tracks: List<CatalogTrack>, onPlay: (Int) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(top = 14.dp),
    ) {
        itemsIndexed(tracks, key = { _, track -> track.uri }) { index, track ->
            TrackTile(track) { onPlay(index) }
        }
    }
}

/** An album or a playlist in a carousel: square art, name, who it is by. */
@Composable
private fun FeedTile(item: SearchItem, onClick: () -> Unit) {
    Column(
        Modifier
            .width(152.dp)
            .pressable(onClick),
    ) {
        Artwork(
            url = item.artworkUrl,
            title = item.title,
            modifier = Modifier
                .size(152.dp)
                .softShadow(RoundedCornerShape(20.dp), elevation = 18.dp),
            corner = 20.dp,
        )
        Text(
            item.title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp, start = 2.dp, end = 2.dp),
        )
        Text(
            item.subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = InkDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp, end = 2.dp),
        )
    }
}

@Composable
private fun <T> Carousel(
    items: List<T>,
    key: (T) -> Any,
    item: @Composable (T) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(top = 14.dp),
    ) {
        items(items, key = key) { item(it) }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.headlineLarge,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 30.dp),
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

@StringRes
private fun greeting(): Int = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..12 -> R.string.good_morning
    in 13..17 -> R.string.good_afternoon
    else -> R.string.good_evening
}

/** A harder film for the chip that is on; see the note at the call site. */
private val SelectedFilm = Color.White.copy(alpha = 0.26f)

/** Comfortably under the 640px Spotify serves, so it is never upscaled. */
private val COVER_SIZE = 210.dp

private const val FEED_SIZE = 6
private const val CAROUSEL_SIZE = 8

/** With the library filter on, the carousel is the whole point of the page. */
private const val LIBRARY_SIZE = 30

/**
 * Up to three friends, overlapping, as one control.
 *
 * Stacked rather than listed: the header has room for a gesture, not for a row
 * of names, and overlapping faces are the one shape everybody already reads as
 * "these people, together". The count is left off deliberately — a badge would
 * turn a glance into a number to keep at zero.
 */
@Composable
private fun FriendFaces(
    friends: List<dev.lelonio.square.data.FriendListen>,
    onClick: () -> Unit,
) {
    val shown = friends.take(3)
    // Measured rather than laid out in a row: each face sits on the one before
    // it, so the box is one face wide plus what the others peek out by.
    val width = FACE + FACE_PEEK * (shown.size - 1)
    Box(
        Modifier
            .padding(start = 8.dp)
            .width(width)
            .height(FACE)
            .pressable(onClick, pressedScale = 0.92f),
    ) {
        shown.forEachIndexed { index, friend ->
            Artwork(
                url = friend.avatarUrl,
                title = friend.userName,
                modifier = Modifier
                    .offset(x = FACE_PEEK * index)
                    .size(FACE)
                    .clip(CircleShape)
                    // The first face on top, so the stack leans the way a hand
                    // of cards does rather than away from the reader.
                    .zIndex((shown.size - index).toFloat()),
                corner = FACE / 2,
                decodeSize = FACE,
            )
        }
    }
}

/** One face, and how much of the one behind it is left showing. */
private val FACE = 30.dp
private val FACE_PEEK = 20.dp
