package dev.lelonio.square.ui.navigation

import android.app.Application
import android.content.Context
import android.os.Parcel
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import dev.antigravity.fluidengine.ui.navigation.FluidNavHost
import dev.antigravity.fluidengine.ui.page.FluidPageHostState
import dev.antigravity.fluidengine.ui.page.FluidPageTransition
import dev.antigravity.fluidengine.ui.page.rememberFluidPageHostState
import dev.lelonio.square.R
import dev.lelonio.square.data.AccountLocalDataFixture
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.data.SearchItem
import dev.lelonio.square.ui.MainViewModel
import dev.lelonio.square.ui.MainViewModel.PlaylistState
import dev.lelonio.square.ui.library.PlaylistScreen
import dev.lelonio.square.ui.library.TrackSort
import dev.lelonio.square.ui.requests.RequestOwnership
import dev.lelonio.square.ui.theme.SquareTheme
import dev.lelonio.square.ui.theme.pageFloor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * PREPARED, NOT EXECUTED. Actual Fluid host, app destination binding, selection/read consumers and
 * PlaylistScreen. Only the context service and authority are synthetic. Does not construct the
 * whole SquareApplication/MainViewModel or exercise Spotify, native code, offline policy or a GPU.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = "w400dp-h900dp-mdpi")
class PlaylistNavigationUiTest {
    @get:Rule val compose = createComposeRule()
    private val art = "android.resource://android/${android.R.drawable.ic_media_play}"
    private val artist = CatalogPlaylist("spotify:artist:synthetic", "Synthetic Artist", art)
    private val a = CatalogPlaylist("spotify:album:synthetic-a", "Album A", art)
    private val b = CatalogPlaylist("spotify:album:synthetic-b", "Album B", art)
    private val fixtures = mutableListOf<Consumer>()
    private lateinit var current: Consumer
    private var replacement: Consumer? = null
    private var compositionMounts = 0
    private var compositionDisposals = 0
    private lateinit var nav: NavHostController
    private lateinit var motion: FluidPageHostState
    private lateinit var restoration: StateRestorationTester

    private fun page(descriptor: CatalogPlaylist) = PlaylistState(
        uri = descriptor.uri, name = descriptor.name, artworkUrl = art,
        kind = if (descriptor.uri == artist.uri) MainViewModel.DetailKind.ARTIST else MainViewModel.DetailKind.ALBUM,
        tracks = List(40) { CatalogTrack("spotify:track:synthetic-$it", "Synthetic track $it", "Artist", durationMs = 1000L + it) },
        albums = if (descriptor.uri == artist.uri) listOf(a, b).map { SearchItem(it.uri, it.name, "", art) } else emptyList(),
        following = descriptor.uri == artist.uri,
    )

    private inner class Consumer(val account: AccountLocalDataFixture) : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
        val entries = PlaylistEntries()
        val requests = RequestOwnership()
        val loads = mutableListOf<String>()
        var storedSort by mutableStateOf(TrackSort.ORIGINAL.name)
        var storedDescending by mutableStateOf(false)
        var playedOrder = emptyList<String>()
        val blocked = mutableSetOf<String>()
        val replies = mutableMapOf<String, Pair<PlaylistReadTicket, PlaylistEntries.Entry>>()
        val selection = PlaylistEntrySelection(entries, { account.accounts.capture()?.binding?.lineage },
            stopRead = { requests.invalidate() }, changed = {}, reconcile = { it },
            open = { descriptor ->
                val entry = checkNotNull(entries.pending())
                loads += descriptor.uri
                val ticket = PlaylistReadTicket(requests.begin(), account.accounts,
                    account.accounts.captureOnline(), 0L, entries.activeState)
                replies[entry.id] = ticket to entry
                // Keep the fixture's local resource cover during loading as well. The real
                // no-cover mosaic uses SquareApplication's cache, outside this synthetic host.
                entries.activeState.value = PlaylistState(uri = descriptor.uri, name = descriptor.name,
                    artworkUrl = descriptor.artworkUrl ?: art, loading = true)
                if (descriptor.uri !in blocked) reply(entry.id, page(when (descriptor.uri) {
                    artist.uri -> artist; a.uri -> a; else -> b
                }))
            })
        val observation = account.accounts.observe { selection.revoke() }
        fun reply(id: String, page: PlaylistState) {
            val (ticket, entry) = replies.getValue(id)
            try { ticket.update { page }; entries.completed(entry) } catch (_: CancellationException) { }
        }
        fun push(descriptor: CatalogPlaylist) = nav.pushPlaylist(descriptor,
            account.accounts.capture()?.binding?.lineage, selection::prepare)
        fun close() { observation.close(); viewModelStore.clear() }
    }

    private fun consumer(account: AccountLocalDataFixture? = null): Consumer {
        val source = account ?: AccountLocalDataFixture().also { runTest { it.connect() } }
        return Consumer(source).also { fixtures += it }
    }

    private fun mount(origin: String = "library") {
        current = consumer()
        compose.mainClock.autoAdvance = false
        restoration = StateRestorationTester(compose)
        restoration.setContent {
            val consumer = current
            DisposableEffect(consumer) {
                compositionMounts++
                onDispose {
                    compositionDisposals++
                    // Save/dispose the old tree before replacing its process-owned store.
                    replacement?.let { current = it; replacement = null }
                }
            }
            CompositionLocalProvider(LocalViewModelStoreOwner provides consumer) {
                SquareTheme(darkTheme = false, amoled = false, systemBars = false) {
                    nav = rememberNavController()
                    motion = rememberFluidPageHostState()
                    val revision by consumer.entries.revision.collectAsState()
                    PlaylistNavigationBinding(nav, revision, "home", consumer.selection::select,
                        consumer.entries::release, {})
                    FluidNavHost(nav, startDestination = origin, state = motion, pageColor = pageFloor) {
                        fluidifyComposable("home") { Text("Home") }
                        fluidifyComposable(origin) {
                            Text("Open artist from $origin", Modifier.clickable { consumer.push(artist) })
                        }
                        fluidifyComposable(PlaylistDestination.route, arguments = PlaylistDestination.arguments,
                            transition = FluidPageTransition.Expand) { entry ->
                            val slot = remember(entry.id) {
                                retainPlaylistEntry(entry, consumer.entries::release)
                                consumer.entries.entry(entry.id, CatalogPlaylist(PlaylistDestination.context(entry).orEmpty(), "", art))
                            }
                            val state by slot.state.collectAsStateWithLifecycle()
                            PlaylistScreen(state, PaddingValues(0.dp), null, onBack = { nav.popBackStack() },
                                onPlay = { tracks, _, _ -> consumer.playedOrder = tracks.map { it.name } },
                                onEnqueue = {}, shuffleOn = false,
                                onToggleShuffle = {}, onAddToPlaylist = {}, onTrackMenu = {},
                                pageMemory = slot.memory,
                                storedSort = consumer.storedSort, storedSortDescending = consumer.storedDescending,
                                onSortChange = { consumer.storedSort = it },
                                onSortDescendingChange = { consumer.storedDescending = it },
                                onOpenItem = { consumer.push(CatalogPlaylist(it.uri, it.title, it.artworkUrl)) })
                        }
                    }
                }
            }
        }
        settle()
    }

    private fun frame() { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
    private fun settle() {
        repeat(3) { frame() }
        repeat(180) {
            frame()
            if (compose.runOnIdle { !motion.isMoving }) {
                repeat(3) { frame() }
                if (compose.runOnIdle { !motion.isMoving }) return
            }
        }
        fail("Fluid transition did not settle")
    }
    private fun contextUri() = PlaylistDestination.context(checkNotNull(nav.currentBackStackEntry))
    private fun id() = checkNotNull(nav.currentBackStackEntry).id
    private fun viewport() = compose.onNodeWithTag("playlist-track-list").fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun scrollTo(matcher: SemanticsMatcher) {
        // Semantic ScrollBy waits for layout/bring-into-view as the separate hero collapses.
        // Let those frames run; only the page/edge-back choreography needs the manual clock.
        compose.mainClock.autoAdvance = true
        try {
            compose.onNodeWithTag("playlist-track-list").performScrollToNode(matcher)
        } finally {
            compose.mainClock.autoAdvance = false
        }
        settle()
    }
    private fun album(name: String) {
        scrollTo(hasText(name))
        compose.onNodeWithText(name).performTouchInput { click(center) }
        settle()
    }
    private fun back() { compose.runOnIdle { nav.popBackStack() }; settle() }
    private fun restoreState() {
        val mounts = compositionMounts
        val disposals = compositionDisposals
        // StateRestorationTester needs a composition frame between hiding and re-emitting its
        // children. A paused clock conflates those writes and never recreates the subtree.
        compose.mainClock.autoAdvance = true
        try {
            restoration.emulateSavedInstanceStateRestore()
        } finally {
            compose.mainClock.autoAdvance = false
        }
        settle()
        assertTrue("Restoration must dispose the previous composition", compositionDisposals > disposals)
        assertTrue("Restoration must create a new composition", compositionMounts > mounts)
    }
    private fun openArtist(origin: String) {
        compose.onNodeWithText("Open artist from $origin").performClick(); settle()
    }

    private fun compareAlbums(origin: String) {
        mount(origin); openArtist(origin)
        val artistId = id()
        // Use the real search field; retaining its ready flag must not scroll to it on return.
        compose.onNodeWithTag("playlist-track-list").performTouchInput { swipeUp(durationMillis = 500) }
        settle()
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.onNodeWithContentDescription(context.getString(R.string.search_in_tracks)).performClick()
        settle()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("playlist-track-search")))
            .assertIsFocused().performTextInput("Synthetic track")
        scrollTo(hasText("Album A"))
        val scroll = viewport()
        compose.onNodeWithText("Album A").performTouchInput { click(center) }; settle()
        assertEquals(a.uri, contextUri())
        assertEquals("", current.entries.memory(id()).query)
        back()
        assertEquals(artistId, id())
        assertEquals(artist.uri, contextUri())
        assertEquals(scroll, viewport(), 1f)
        assertEquals("Synthetic track", current.entries.memory(artistId).query)
        assertTrue(current.entries.activeState.value.following == true)
        assertEquals(1, current.loads.count { it == artist.uri })
        album("Album B")
        assertEquals(b.uri, contextUri())
        back(); assertEquals(artistId, id()); back()
        assertEquals(origin, nav.currentBackStackEntry?.destination?.route)
    }

    @Test fun libraryArtistAlbumABackAlbumBKeepsStateAndViewport() = compareAlbums("library")
    @Test fun searchArtistAlbumABackAlbumBKeepsStateAndViewport() = compareAlbums("search")

    @Test fun coveredEntryKeepsItsSortWhenAnotherPageChangesTheGlobalSeed() {
        mount()
        compose.runOnIdle { current.storedSort = TrackSort.TITLE.name }
        openArtist("library")
        scrollTo(hasText("Synthetic track 0"))
        compose.onNodeWithText("Synthetic track 0").performClick()
        assertEquals(listOf("Synthetic track 0", "Synthetic track 1", "Synthetic track 10"), current.playedOrder.take(3))
        album("Album A")
        // Change the persisted preference while the artist is COVERED. The active album must
        // react, whereas returning to the artist must restore that entry's previous choice.
        compose.runOnIdle { current.storedSort = TrackSort.DURATION.name; current.storedDescending = true }
        scrollTo(hasText("Synthetic track 39"))
        compose.onNodeWithText("Synthetic track 39").performClick()
        assertEquals("Synthetic track 39", current.playedOrder.first())
        back()
        scrollTo(hasText("Synthetic track 0"))
        compose.onNodeWithText("Synthetic track 0").performClick()
        assertEquals(listOf("Synthetic track 0", "Synthetic track 1", "Synthetic track 10"), current.playedOrder.take(3))
        // Updating only direction must not replace this entry's restored sort criterion.
        compose.runOnIdle { current.storedDescending = false }
        scrollTo(hasText("Synthetic track 0"))
        compose.onNodeWithText("Synthetic track 0").performClick()
        assertEquals(listOf("Synthetic track 0", "Synthetic track 1", "Synthetic track 10"), current.playedOrder.take(3))
        // Once selected again, this same entry must still follow a changed persisted criterion.
        compose.runOnIdle { current.storedSort = TrackSort.ORIGINAL.name }
        scrollTo(hasText("Synthetic track 0"))
        compose.onNodeWithText("Synthetic track 0").performClick()
        assertEquals(listOf("Synthetic track 0", "Synthetic track 1", "Synthetic track 2"), current.playedOrder.take(3))
    }

    @Test fun cancelledEdgeBackKeepsAlbumAndCompletedEdgeBackSelectsArtist() {
        mount(); openArtist("library")
        val artistId = id()
        album("Album A")
        val albumId = id()
        compose.onNodeWithTag("playlist-track-list").performScrollToIndex(12)
        val albumScroll = viewport()
        compose.onRoot().performTouchInput {
            down(Offset(2f, height * 0.65f))
            moveTo(Offset(width * 0.16f, height * 0.65f), delayMillis = 200)
        }
        frame()
        assertTrue(motion.isInteractive)
        assertEquals(albumId, id())
        assertEquals(albumId, current.entries.activeId)
        compose.onRoot().performTouchInput {
            moveTo(Offset(width * 0.16f, height * 0.65f), delayMillis = 240); up()
        }
        settle()
        assertEquals(albumId, id()); assertEquals(albumScroll, viewport(), 1f)
        compose.onRoot().performTouchInput {
            down(Offset(2f, height * 0.65f))
            moveTo(Offset(width * 0.82f, height * 0.65f), delayMillis = 400)
            moveTo(Offset(width * 0.82f, height * 0.65f), delayMillis = 240); up()
        }
        settle()
        assertEquals(artistId, id()); assertEquals(artistId, current.entries.activeId)
        assertEquals(artist.uri, contextUri())
        album("Album B"); assertEquals(b.uri, contextUri())
    }

    @Test fun loadingAndErrorBelongToTheirEntryAndAStaleReplyCannotReplaceTheArtist() {
        mount(); openArtist("library")
        val artistId = id()
        current.blocked += a.uri
        album("Album A")
        val albumId = id()
        assertTrue(current.entries.activeState.value.loading)
        compose.runOnIdle { current.reply(albumId, page(a).copy(tracks = emptyList(), error = "Synthetic A failure")) }
        frame()
        compose.onNodeWithText("Synthetic A failure").assertIsDisplayed()
        back(); assertEquals(artistId, id())
        compose.runOnIdle { current.reply(albumId, page(a).copy(error = "STALE FAILURE")) }
        frame()
        compose.onNodeWithText("STALE FAILURE").assertDoesNotExist()
        assertEquals(artist.uri, current.entries.activeState.value.uri)
    }

    @Test fun processRecreationRestoresUrisAndSmallViewportWithoutSavingTracksOrUserFilter() {
        mount(); openArtist("library")
        val artistId = id()
        compose.runOnIdle { current.entries.memory(artistId).query = "Synthetic track" }
        scrollTo(hasText("Album A"))
        val artistScroll = viewport()
        compose.onNodeWithText("Album A").performTouchInput { click(center) }; settle()
        compose.onNodeWithTag("playlist-track-list").performScrollToIndex(12)
        val albumScroll = viewport()
        val account = current.account
        val saved = compose.runOnIdle { nav.saveState() }
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(saved)
            assertTrue("Navigation should contain identifiers, not 40 tracks per entry", parcel.dataSize() < 32_768)
            assertFalse(String(parcel.marshall(), Charsets.UTF_16LE).contains("Synthetic track"))
        } finally { parcel.recycle() }
        // A new owner/store models process loss. The synthetic persisted local authority is kept.
        val previousOwner = current
        compose.runOnIdle { replacement = consumer(account).also { it.blocked += a.uri } }
        restoreState()
        assertNotSame(previousOwner, current)
        assertTrue("Restored route=${contextUri()}, active=${current.entries.activeId}, " +
            "loads=${current.loads}, blocked=${current.blocked}, state=${current.entries.activeState.value}",
            current.entries.activeState.value.loading)
        // The restored position must survive this one-row loading layout until tracks arrive.
        compose.runOnIdle { current.reply(id(), page(a)) }; settle()
        assertEquals(a.uri, contextUri()); assertEquals(albumScroll, viewport(), 1f)
        assertEquals(listOf(a.uri), current.loads)
        back()
        assertEquals(artist.uri, contextUri()); assertEquals(artistScroll, viewport(), 1f)
        assertEquals("", current.entries.memory(id()).query)
        assertEquals(listOf(a.uri, artist.uri), current.loads)
    }

    @Test fun compositionRecreationWithRetainedOwnerKeepsQueryViewportAndLoadedArtist() {
        mount(); openArtist("library")
        val artistId = id()
        compose.runOnIdle { current.entries.memory(artistId).query = "Synthetic track" }
        scrollTo(hasText("Album A"))
        val scroll = viewport()
        compose.onNodeWithText("Album A").performTouchInput { click(center) }; settle()
        val albumId = id()
        restoreState()
        assertEquals(albumId, id())
        assertEquals(2, current.loads.size)
        back()
        assertEquals(artistId, id()); assertEquals(scroll, viewport(), 1f)
        assertEquals("Synthetic track", current.entries.memory(artistId).query)
        assertEquals(1, current.loads.count { it == artist.uri })
    }

    @Test fun authorityChangeRevokesRetainedPagesAndRestoredForeignRouteReturnsHome() {
        mount(); openArtist("library"); album("Album A")
        val held = current.entries.activeState
        runTest { current.account.connect("another-synthetic-account") }
        settle()
        assertNull(held.value.uri)
        assertEquals("home", nav.currentBackStackEntry?.destination?.route)
        // Restore a route saved before the authority changed; it must not trigger a service read.
        compose.runOnIdle {
            nav.navigate(PlaylistDestination.route(artist.uri, "old-authority-lineage"))
        }
        settle()
        assertEquals("home", nav.currentBackStackEntry?.destination?.route)
        assertEquals(2, current.loads.size)
    }

    @After fun cleanup() { fixtures.forEach { it.close() } }
}
