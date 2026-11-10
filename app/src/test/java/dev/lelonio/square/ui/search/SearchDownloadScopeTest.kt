package dev.lelonio.square.ui.search

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lelonio.square.R
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.data.SearchItem
import dev.lelonio.square.data.SearchResults
import dev.lelonio.square.data.SearchResultScope
import dev.lelonio.square.ui.MainViewModel
import dev.lelonio.square.ui.theme.SquareTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/** Prepared real-composition regressions; NOT EXECUTED. No backend or device access. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = "w360dp-h800dp-mdpi")
class SearchDownloadScopeTest {
    @get:Rule val compose = createComposeRule()
    private val tracks = List(6) { CatalogTrack("fixture:$it", "Song $it", "Artist") }
    private val state = mutableStateOf(MainViewModel.SearchState(query = "Song"))
    private var played: Pair<List<CatalogTrack>, Int>? = null

    private fun show(history: List<CatalogTrack> = emptyList()) {
        compose.setContent {
            SquareTheme(darkTheme = true, amoled = false, systemBars = false) {
                SearchScreen(
                    state = state.value, webApi = MainViewModel.WebApiState(),
                    contentPadding = PaddingValues(0.dp), nowPlayingUri = null,
                    onClientIdChange = {}, onConnectWebApi = {},
                    onPlayTrack = { list, index -> played = list to index },
                    history = history, onClearHistory = {}, onEnqueue = {},
                    onTrackMenu = {}, onOpenContext = {},
                )
            }
        }
    }

    @Test fun artistFilterCannotHideLocalTracksAndIsResetOnReturnOnline() {
        val online = SearchResults(tracks = tracks,
            artists = listOf(SearchItem("artist", "Fixture Artist", "", null)))
        state.value = state.value.copy(results = online)
        show()
        compose.onNodeWithText(word(R.string.artists)).performClick()
        compose.runOnIdle {
            state.value = state.value.copy(resultScope = SearchResultScope.DOWNLOADS,
                results = SearchResults(tracks = tracks))
        }
        compose.onNodeWithText(word(R.string.search_downloads_scope).uppercase(Locale.getDefault())).assertIsDisplayed()
        compose.onNodeWithText("Song 0").assertIsDisplayed()
        compose.onNodeWithText(word(R.string.artists)).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(resultScope = SearchResultScope.ONLINE, results = online) }
        compose.onNodeWithText(word(R.string.feed_all)).assertIsSelected()
        compose.onNodeWithText("Song 0").assertIsDisplayed()
    }

    @Test fun fallbackExplainsScopeAndEmptyResultPrecisely() {
        state.value = state.value.copy(resultScope = SearchResultScope.DOWNLOADS_NETWORK_FALLBACK)
        show()
        compose.onNodeWithText(word(R.string.search_downloads_network_fallback)).assertIsDisplayed()
        compose.onNodeWithText(word(R.string.search_downloads_empty, "Song")).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
    }

    @Test fun queryChangeResetsTypeFilterWithinOnlineScope() {
        state.value = state.value.copy(results = SearchResults(tracks = tracks,
            artists = listOf(SearchItem("artist", "Fixture Artist", "", null))))
        show()
        compose.onNodeWithText(word(R.string.artists)).performClick()
        compose.runOnIdle { state.value = state.value.copy(query = "New song") }
        compose.onNodeWithText(word(R.string.feed_all)).assertIsSelected()
        compose.onNodeWithText("Song 0").assertIsDisplayed()
    }

    @Test fun removedLastDownloadReplacesRowWithLocalEmptyState() {
        state.value = state.value.copy(resultScope = SearchResultScope.DOWNLOADS,
            results = SearchResults(tracks = tracks.take(1)))
        show()
        compose.onNodeWithText("Song 0").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(results = SearchResults()) }
        compose.onNodeWithText("Song 0").assertDoesNotExist()
        compose.onNodeWithText(word(R.string.search_downloads_empty, "Song")).assertIsDisplayed()
        compose.onNodeWithText(word(R.string.search_downloads_network_fallback)).assertDoesNotExist()
    }

    @Test fun localResultsBeyondOnlinePreviewRemainPlayableInTheirFullQueue() {
        state.value = state.value.copy(resultScope = SearchResultScope.DOWNLOADS,
            results = SearchResults(tracks = tracks))
        show()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Song 5"))
        compose.onNodeWithText("Song 5").performClick()
        compose.runOnIdle { assertEquals(tracks to 5, played) }
    }

    @Test fun blankLocalQueryStillShowsHistoryWithoutScopeOrLocalEmptyText() {
        state.value = state.value.copy(query = "", resultScope = SearchResultScope.DOWNLOADS)
        show(history = tracks.take(1))
        compose.onNodeWithText("Song 0").assertIsDisplayed()
        compose.onNodeWithText(word(R.string.search_downloads_scope).uppercase(Locale.getDefault())).assertDoesNotExist()
        compose.onNodeWithText(word(R.string.search_downloads_empty, "")).assertDoesNotExist()
    }

    private fun word(id: Int, vararg args: Any): String =
        RuntimeEnvironment.getApplication().getString(id, *args)
}
