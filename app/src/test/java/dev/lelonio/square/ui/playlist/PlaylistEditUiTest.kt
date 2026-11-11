package dev.lelonio.square.ui.playlist

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.antigravity.fluidengine.ui.fluid.*
import dev.lelonio.square.R
import dev.lelonio.square.data.AccountLocalDataFixture
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.ui.MainViewModel
import dev.lelonio.square.ui.library.PlaylistScreen
import dev.lelonio.square.ui.library.TrackSort
import dev.lelonio.square.ui.theme.SquareTheme
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Prepared, NOT EXECUTED. Production receipt consumer, PlaylistScreen, menus and FluidAlert. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h900dp-mdpi")
class PlaylistEditUiTest {
    @get:Rule val compose = createComposeRule()
    private val writes = TestScope()
    private val account = AccountLocalDataFixture()
    private val row = CatalogPlaylist("spotify:playlist:a", "Original A")
    private val old = CatalogTrack("spotify:track:t", "Track", "Artist", addedAt = "2020-01-01T00:00:00Z")
    private val recent = old.copy(addedAt = "2026-01-01T00:00:00Z")
    private val library = MutableStateFlow<MainViewModel.UiState>(MainViewModel.UiState.Ready("Account", listOf(row)))
    private val detail = MutableStateFlow(MainViewModel.PlaylistState(
        uri = row.uri, name = row.name, tracks = listOf(old, recent), mine = true, saved = true,
        artworkUrl = "android.resource://android/${android.R.drawable.ic_media_play}",
    ))
    private val renameReply = CompletableDeferred<Unit>()
    private var renames = 0
    private var removedIndex: Int? = null
    private val service = object : PlaylistEditService {
        override suspend fun create(name: String): CatalogPlaylist = error("Unused synthetic create")
        override suspend fun rename(uri: String, name: String) { renames++; renameReply.await() }
        override suspend fun delete(uri: String) { error("Unused synthetic delete") }
        override suspend fun remove(uri: String, trackUri: String, index: Int) { removedIndex = index }
    }
    private lateinit var edits: PlaylistEdits
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun mount(sort: TrackSort = TrackSort.ORIGINAL) {
        runTest { account.connect() }
        edits = PlaylistEdits(writes.backgroundScope, account.accounts, library, detail, { service },
            invalidate = { _, _ -> }, stopDetailRead = {}, reloadDetail = {})
        writes.runCurrent()
        compose.setContent {
            SquareTheme(darkTheme = false, amoled = false, systemBars = false) {
                val backdrop = rememberGlassBackdrop()
                val modal = rememberFluidGlassModalHostState()
                val page by detail.collectAsState()
                val feedback by edits.feedback.collectAsState()
                CompositionLocalProvider(LocalGlassBackdrop provides backdrop,
                    LocalFluidGlassModalHostState provides modal,
                    LocalFluidMotionPolicy provides FluidMotionPolicy(0f)) {
                    Box {
                        PlaylistScreen(state = page, contentPadding = PaddingValues(0.dp), nowPlayingUri = null,
                            onBack = {}, onPlay = { _, _, _ -> }, onEnqueue = {}, shuffleOn = false,
                            onToggleShuffle = {}, onAddToPlaylist = {}, onTrackMenu = {}, storedSort = sort.name,
                            trackActionsAt = { track, index ->
                                val remove = edits.removalAction(page, track, index)
                                listOf(FluidContextAction(label = "Remove occurrence") { remove() })
                            })
                        PlaylistEditAlert(feedback.firstOrNull(), edits::dismiss)
                        FluidGlassModalHost(modal, backdrop)
                    }
                }
            }
        }
    }

    @After fun cleanup() { writes.backgroundScope.cancel() }

    @Test fun failedRenameRendersOriginalDetailTitleAndDismissibleFluidErrorWithoutRetry() {
        mount()
        compose.runOnIdle { edits.rename(row.uri, "Rejected title") }
        writes.runCurrent()
        renameReply.completeExceptionally(IOException("Synthetic error"))
        writes.runCurrent()
        compose.waitForIdle()
        compose.onNodeWithText("Original A").assertExists()
        compose.onNodeWithText("Rejected title").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.playlist_edit_rename_unconfirmed, row.name)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.playlist_edit_check_before_retry)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.close)).performClick()
        compose.runOnIdle {
            assertEquals(listOf(row), (library.value as MainViewModel.UiState.Ready).playlists)
            assertEquals(1, renames)
        }
    }

    @Test fun lateFailureAfterNewPageDoesNotRenderOldAlertOrChangeNewTitle() {
        mount()
        compose.runOnIdle { edits.rename(row.uri, "Rejected title") }
        writes.runCurrent()
        compose.runOnIdle {
            edits.detailOpened()
            detail.value = detail.value.copy(uri = "spotify:playlist:b", name = "New page")
        }
        renameReply.completeExceptionally(IOException("Late synthetic error"))
        writes.runCurrent()
        compose.waitForIdle()
        compose.onNodeWithText("New page").assertExists()
        compose.onNodeWithText(context.getString(R.string.playlist_edit_rename_unconfirmed, row.name)).assertDoesNotExist()
    }

    @Test fun realSortedRowMenuRemovesSecondSourceOccurrence() {
        mount(TrackSort.ADDED)
        compose.onAllNodesWithText("Track")[0].performTouchInput { longClick() }
        compose.onNodeWithText("Remove occurrence").performClick()
        writes.runCurrent()
        compose.runOnIdle {
            assertEquals(1, removedIndex)
            assertEquals(listOf(old), detail.value.tracks)
        }
    }
}
