package dev.lelonio.square.ui.library

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.antigravity.fluidengine.ui.fluid.*
import dev.lelonio.square.R
import dev.lelonio.square.SquareApplication
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.ui.MainViewModel
import dev.lelonio.square.ui.player.PlaylistPicker
import dev.lelonio.square.ui.theme.SquareTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Prepared, NOT EXECUTED. Real screen/IME/TalkBack qualification remains with ROOT. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = SquareApplication::class, qualifiers = "w400dp-h900dp-mdpi")
class I09CollectionSearchUiTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<SquareApplication>()
    private val context: Context get() = app
    private val rows = listOf(CatalogPlaylist("spotify:playlist:a", "Caffè"), CatalogPlaylist("spotify:playlist:b", "Estate"))
    private var historyOpened = 0
    private var selected: CatalogPlaylist? = null

    @Composable private fun Host(content: @Composable () -> Unit) {
        SquareTheme(darkTheme = false, amoled = false, systemBars = false) {
            val backdrop = rememberGlassBackdrop()
            val modal = rememberFluidGlassModalHostState()
            CompositionLocalProvider(LocalGlassBackdrop provides backdrop,
                LocalFluidGlassModalHostState provides modal,
                LocalFluidMotionPolicy provides FluidMotionPolicy(0f)) {
                Box(Modifier.fillMaxSize()) { content(); FluidGlassModalHost(modal, backdrop) }
            }
        }
    }
    private fun search(tag: String) = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(tag)))
    private fun mountLibrary(grid: Boolean, empty: Boolean = false) {
        app.libraryView.grid = grid
        app.libraryView.order = "name"
        app.libraryView.descending = false
        compose.setContent { Host {
            LibraryScreen(MainViewModel.UiState.Ready("Synthetic", if (empty) emptyList() else rows),
                PaddingValues(top = 24.dp, bottom = 80.dp), rememberGlassBackdrop(),
                onLogIn = {}, onRetry = {}, onLogOut = {}, onOpenPlaylist = { selected = it },
                playlistOrder = emptyList(),
                onOpenListeningHistory = { historyOpened++ },
                downloaded = setOf("spotify:album:orphan"),
                downloadedCollections = listOf(CatalogPlaylist("spotify:album:orphan", "Orphan download")))
        } }
    }
    private fun historySurvivesSearch(grid: Boolean, empty: Boolean = false) {
        mountLibrary(grid, empty)
        search("wf_i09_library_search").performTextInput("unmatched")
        compose.onNodeWithText(context.getString(R.string.wf_i09_no_results)).assertExists()
        compose.onNodeWithTag("wf_i14_library_entry").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, historyOpened) }
        search("wf_i09_library_search").performTextClearance()
        if (!empty) {
            search("wf_i09_library_search").performTextInput("caffe")
            compose.onNodeWithText("Caffè").assertExists().performClick()
            compose.onNodeWithText("Estate").assertDoesNotExist()
            compose.runOnIdle { assertEquals(rows.first(), selected) }
        }
    }
    @Test fun gridSearchPreservesHistoryAndCollectionIdentity() = historySurvivesSearch(true)
    @Test fun listSearchPreservesHistoryAndCollectionIdentity() = historySurvivesSearch(false)
    @Test fun gridHistoryVisibleWithNoCollectionsAndUnmatchedSearch() = historySurvivesSearch(true, true)
    @Test fun listHistoryVisibleWithNoCollectionsAndUnmatchedSearch() = historySurvivesSearch(false, true)

    private fun orphanDownload(grid: Boolean) {
        mountLibrary(grid)
        compose.onNodeWithText(context.getString(R.string.downloads)).performClick()
        search("wf_i09_library_search").performTextInput("orphan")
        compose.onNodeWithText("Orphan download").assertExists().performClick()
        compose.runOnIdle { assertEquals("spotify:album:orphan", selected?.uri) }
    }
    @Test fun gridDownloadFilterContainsAnUnsavedAlbum() = orphanDownload(true)
    @Test fun listDownloadFilterContainsAnUnsavedAlbum() = orphanDownload(false)

    @Test fun pickerSearchKeepsLikedDestinationAndBusyReceiptState() {
        val liked = CatalogPlaylist("spotify:user:a:collection", "Preferiti", editable = null)
        val distant = CatalogPlaylist("spotify:playlist:121", "Caffè dopo pagina 100", editable = true)
        val input = mutableStateOf(MainViewModel.AddToPlaylistState(open = true, trackUri = "spotify:track:t",
            trackTitle = "Captured track", liked = true,
            playlists = listOf(liked) + (1..120).map { CatalogPlaylist("spotify:playlist:$it", "Playlist $it", editable = true) } + distant))
        val selections = mutableListOf<CatalogPlaylist>()
        compose.setContent { Host { PlaylistPicker(input.value, onSelect = { selections += it }) } }
        search("wf_i09_picker_search").performTextInput("preferiti")
        compose.onNodeWithText("Preferiti").performClick()
        compose.runOnIdle {
            assertSame(liked, selections.single())
            input.value = input.value.copy(busy = liked.uri)
        }
        compose.onNodeWithText("Preferiti").performClick()
        compose.runOnIdle { assertEquals(1, selections.size); input.value = input.value.copy(busy = null, removed = liked.name, liked = false) }
        // Completion and membership updates keep the query and the originally captured track.
        compose.onNodeWithText(context.getString(R.string.removed_from, liked.name)).assertExists()
        search("wf_i09_picker_search").performTextClearance()
        search("wf_i09_picker_search").performTextInput("caffe")
        compose.onNodeWithText(distant.name).assertExists().performClick()
        compose.runOnIdle { assertSame(distant, selections.last()); assertEquals("spotify:track:t", input.value.trackUri) }
        search("wf_i09_picker_search").performTextClearance()
        search("wf_i09_picker_search").performTextInput("no match")
        compose.onNodeWithText(context.getString(R.string.wf_i09_no_results)).assertExists()
        compose.runOnIdle { input.value = input.value.copy(trackUri = "spotify:track:new", trackTitle = "New captured track") }
        // A different request clears the old query and restores all destinations.
        compose.onNodeWithText("Preferiti").assertExists()
    }
}
