package dev.lelonio.square.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.antigravity.fluidengine.ui.fluid.*
import dev.lelonio.square.R
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.data.HomeShelf
import dev.lelonio.square.data.SearchResults
import dev.lelonio.square.data.SearchResultScope
import dev.lelonio.square.ui.home.HomeScreen
import dev.lelonio.square.ui.onboarding.OnboardingScreen
import dev.lelonio.square.ui.requests.I07FeedAvailability
import dev.lelonio.square.ui.search.SearchScreen
import dev.lelonio.square.ui.settings.I10WebApiAccess
import dev.lelonio.square.ui.settings.I10WebApiRequest
import dev.lelonio.square.ui.theme.SquareTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** PREPARED, NOT EXECUTED. Actual composables and click destinations, no VM/provider startup. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = "w440dp-h1000dp-mdpi")
class I10DiscoveryAccessUiTest {
    @get:Rule val compose = createComposeRule()
    private val song = CatalogTrack("fixture:song", "Fixture Song", "Fixture Artist")

    @Composable private fun Theme(body: @Composable () -> Unit) {
        SquareTheme(darkTheme = true, amoled = false, systemBars = false) {
            CompositionLocalProvider(LocalFluidMotionPolicy provides FluidMotionPolicy(0f), content = body)
        }
    }

    @Test fun forYouContainsTheSameGatewayMixAndItsRealDestinationWithoutWebApi() {
        val mix = CatalogPlaylist("spotify:playlist:mix-a", "Fixture Daily Mix", null)
        var opened: CatalogPlaylist? = null
        compose.setContent {
            val ground = rememberGlassBackdrop()
            Theme {
                CompositionLocalProvider(LocalGlassBackdrop provides ground) {
                    HomeScreen(state = MainViewModel.UiState.Ready("Fixture", emptyList()),
                        contentPadding = PaddingValues(0.dp), ground = ground,
                        onLogIn = {}, onRetry = {}, onLogOut = {}, onOpenPlaylist = { opened = it },
                        playlistOrder = emptyList(), recent = emptyList(), onPlayRecent = { _, _ -> },
                        onPlayFeed = { _, _, _ -> }, onOpenItem = {}, onOpenSettings = {},
                        feed = MainViewModel.FeedState(availability = I07FeedAvailability.NEEDS_SETUP),
                        shelves = listOf(HomeShelf("Fixture Personal Shelf", listOf(mix))))
                }
            }
        }
        compose.onNodeWithText(word(R.string.feed_for_you)).performClick()
        compose.onNodeWithText("Fixture Daily Mix").assertIsDisplayed().performClick()
        compose.onNodeWithText(word(R.string.wf_i10_home_setup)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(mix, opened) }
    }

    @Test fun emptyArtistFilterShowsSetupCallbackAndExitToAll() {
        var setup = 0
        compose.setContent {
            val ground = rememberGlassBackdrop()
            Theme {
                CompositionLocalProvider(LocalGlassBackdrop provides ground) {
                    HomeScreen(state = MainViewModel.UiState.Ready("Fixture", emptyList()),
                        contentPadding = PaddingValues(0.dp), ground = ground,
                        onLogIn = {}, onRetry = {}, onLogOut = {}, onOpenPlaylist = {},
                        playlistOrder = emptyList(), recent = emptyList(), onPlayRecent = { _, _ -> },
                        onPlayFeed = { _, _, _ -> }, onOpenItem = {}, onOpenSettings = {},
                        onSetupWebApi = { setup++ },
                        feed = MainViewModel.FeedState(availability = I07FeedAvailability.NEEDS_SETUP))
                }
            }
        }
        compose.onNodeWithText(word(R.string.artists)).performScrollTo().performClick()
        compose.onNodeWithText(word(R.string.wf_i10_home_setup)).assertIsDisplayed()
        compose.onNodeWithText(word(R.string.wf_i10_setup)).performClick()
        compose.runOnIdle { assertEquals(1, setup) }
        compose.onNodeWithText(word(R.string.wf_i10_show_all)).performClick()
        compose.onNodeWithText(word(R.string.feed_all)).assertIsSelected()
    }

    @Test fun emptySearchCategoryNamesCategoryAndQueryAndCanReturnToPlayableAnswer() {
        var played: CatalogTrack? = null
        compose.setContent { Theme {
            SearchScreen(state = MainViewModel.SearchState(query = "Fixture", results = SearchResults(tracks = listOf(song))),
                webApi = MainViewModel.WebApiState(), contentPadding = PaddingValues(0.dp), nowPlayingUri = null,
                onClientIdChange = {}, onConnectWebApi = {}, onPlayTrack = { tracks, index -> played = tracks[index] },
                history = emptyList(), onClearHistory = {}, onEnqueue = {}, onTrackMenu = {}, onOpenContext = {})
        } }
        compose.onNodeWithText(word(R.string.albums)).performScrollTo().performClick()
        compose.onNodeWithText(word(R.string.wf_i10_search_category_empty, word(R.string.albums), "Fixture")).assertIsDisplayed()
        compose.onNodeWithText(word(R.string.wf_i10_show_all)).performClick()
        compose.onNodeWithText(song.name).performClick()
        compose.runOnIdle { assertEquals(song, played) }
    }

    @Test fun fallbackRetryKeepsDownloadsAndManualModeRemovesOnlineAction() {
        var retries = 0
        val online = mutableStateOf(true)
        compose.setContent { Theme {
            SearchScreen(state = MainViewModel.SearchState(query = "Fixture", retryAvailable = true,
                results = SearchResults(tracks = listOf(song)), resultScope = SearchResultScope.DOWNLOADS_NETWORK_FALLBACK),
                webApi = MainViewModel.WebApiState(), contentPadding = PaddingValues(0.dp), nowPlayingUri = null,
                onClientIdChange = {}, onConnectWebApi = {}, onPlayTrack = { _, _ -> },
                history = emptyList(), onClearHistory = {}, onEnqueue = {}, onTrackMenu = {}, onOpenContext = {},
                onRetrySearch = { retries++ }, retryOnline = online.value)
        } }
        compose.onNodeWithText(song.name).assertIsDisplayed()
        compose.onNodeWithText(word(R.string.wf_i07_search_retry)).performClick()
        compose.runOnIdle { assertEquals(1, retries); online.value = false }
        compose.onNodeWithText(word(R.string.wf_i07_search_retry)).assertDoesNotExist()
        compose.onNodeWithText(song.name).assertIsDisplayed()
    }

    @Test fun onboardingShowsFailureAndCancelsBrowserWaitWithoutAnotherLogin() {
        val state = mutableStateOf<MainViewModel.UiState>(MainViewModel.UiState.Failed("Fixture denied"))
        val waiting = mutableStateOf(false)
        var starts = 0; var cancels = 0
        compose.setContent { Theme {
            OnboardingScreen(state = state.value, webApi = MainViewModel.WebApiState(),
                onLogIn = { starts++; waiting.value = true; state.value = MainViewModel.UiState.Loading },
                onClientIdChange = {}, onConnectWebApi = {}, language = "", onLanguage = {},
                onFinish = {}, onOpenAirPods = {}, loginWaiting = waiting.value,
                onCancelLogIn = { cancels++; waiting.value = false; state.value = MainViewModel.UiState.Failed("Fixture cancelled") })
        } }
        compose.onNodeWithText(word(R.string.next_step)).performClick()
        compose.onNodeWithText("Fixture denied").assertIsDisplayed()
        compose.onNodeWithText(word(R.string.log_in_with_spotify)).performClick()
        compose.onNodeWithText(word(R.string.wf_i10_cancel_wait)).performClick()
        compose.onNodeWithText("Fixture cancelled").assertIsDisplayed()
        compose.onNodeWithText(word(R.string.log_in_with_spotify)).assertHasClickAction()
        compose.runOnIdle { assertEquals(1, starts); assertEquals(1, cancels) }
    }

    @Test fun artistRequirementHasActualSetupAndNeverOpensAutomaticallyOnLateSuccess() {
        val artist = CatalogPlaylist("spotify:artist:fixture", "Fixture Artist", null)
        val web = mutableStateOf(MainViewModel.WebApiState(clientId = "fixture-client"))
        var connects = 0; var opened: CatalogPlaylist? = null
        compose.setContent {
            val host = rememberFluidGlassModalHostState()
            val backdrop = rememberGlassBackdrop()
            Theme { CompositionLocalProvider(LocalFluidGlassModalHostState provides host) {
                Box {
                    I10WebApiAccess(I10WebApiRequest(artist), web.value, {}, { connects++ }, {}, {}, { opened = it })
                    FluidGlassModalHost(state = host, backdrop = backdrop)
                }
            } }
        }
        compose.onNodeWithText(word(R.string.wf_i10_artist_requirement)).assertIsDisplayed()
        compose.onNodeWithText(word(R.string.wf_i10_open_dashboard)).assertHasClickAction()
        compose.onNodeWithText(word(R.string.connect)).performClick()
        compose.runOnIdle { assertEquals(1, connects); assertNull(opened); web.value = web.value.copy(connected = true) }
        compose.runOnIdle { assertNull(opened) }
        compose.onNodeWithText(word(R.string.wf_i10_open_artist)).performClick()
        compose.runOnIdle { assertEquals(artist, opened) }
    }

    private fun word(id: Int, vararg args: Any): String = RuntimeEnvironment.getApplication().getString(id, *args)
}
