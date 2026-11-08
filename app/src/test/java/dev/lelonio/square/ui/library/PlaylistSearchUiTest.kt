package dev.lelonio.square.ui.library

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.antigravity.fluidengine.ui.fluid.*
import dev.lelonio.square.R
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.ui.MainViewModel
import dev.lelonio.square.ui.theme.SquareTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Prepared only: NOT EXECUTED. Real IME visibility/TalkBack remain host/device checks. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = "w400dp-h900dp-mdpi")
class PlaylistSearchUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val tracks = (0 until 100).map {
        CatalogTrack(uri = "synthetic:$it", name = if (it == 77) "Caffè" else "Track $it", artist = "Artist")
    }
    private val detail = mutableStateOf(MainViewModel.PlaylistState(
        uri = "playlist:A", name = "A", tracks = tracks,
        artworkUrl = "android.resource://android/${android.R.drawable.ic_media_play}",
    ))
    private val direction = mutableStateOf(false)
    private var played: List<CatalogTrack> = emptyList()

    private fun mount(fontScale: Float = 1f, sort: TrackSort = TrackSort.ORIGINAL) {
        compose.setContent {
            SquareTheme(darkTheme = false, amoled = false, systemBars = false) {
                val density = LocalDensity.current
                val backdrop = rememberGlassBackdrop()
                val modal = rememberFluidGlassModalHostState()
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale),
                    LocalGlassBackdrop provides backdrop,
                    LocalFluidGlassModalHostState provides modal,
                    LocalFluidMotionPolicy provides FluidMotionPolicy(0f),
                ) {
                    PlaylistScreen(
                        state = detail.value, contentPadding = PaddingValues(0.dp), nowPlayingUri = null,
                        onBack = {}, onPlay = { list, _, _ -> played = list }, onEnqueue = {},
                        shuffleOn = false, onToggleShuffle = {}, onAddToPlaylist = {}, onTrackMenu = {},
                        storedSort = sort.name, storedSortDescending = direction.value,
                        onSortDescendingChange = { direction.value = it },
                    )
                }
            }
        }
    }

    private fun openFromDeepScroll() {
        // First collapse the separate hero through nested scroll, then move deep into the list.
        compose.onNodeWithTag("playlist-track-list").performTouchInput { swipeUp() }
        compose.onNodeWithTag("playlist-track-list").performScrollToIndex(80)
        compose.onNodeWithContentDescription(context.getString(R.string.search_in_tracks)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("playlist-track-search").assertIsDisplayed()
        input().assertIsFocused()
    }

    private fun input() = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("playlist-track-search")))

    @Test fun openingFromDeepScrollFocusesFiltersAccentsAndClosingClears() {
        mount()
        openFromDeepScroll()
        input().performTextInput("caffe")
        compose.onNodeWithText(context.getString(R.string.playlist_search_loaded_count, 1, 100)).assertIsDisplayed()
        compose.onNodeWithText("Caffè").assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.search_in_tracks)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("playlist-track-search").assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.search_in_tracks)).performClick()
        input().assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.EditableText,
            androidx.compose.ui.text.AnnotatedString(""),
        ))
    }

    @Test fun largeFontSearchRemainsVisibleFocusedAndReportsPartialLoading() {
        compose.runOnIdle { detail.value = detail.value.copy(loadingMore = true) }
        mount(fontScale = 2f)
        openFromDeepScroll()
        compose.onNodeWithText(context.getString(R.string.playlist_search_loading_scope)).assertExists()
        input().performTextInput("missing")
        compose.onNodeWithText(context.getString(R.string.playlist_search_loaded_count, 0, 100)).assertExists()
    }

    @Test fun detailIdentityClearsSearchAndScrollWithoutResettingGlobalTitleDirection() {
        direction.value = true
        mount(sort = TrackSort.TITLE)
        openFromDeepScroll()
        input().performTextInput("caffe")
        compose.runOnIdle { detail.value = detail.value.copy(uri = "album:B", name = "B") }
        compose.waitForIdle()
        compose.onNodeWithTag("playlist-track-search").assertDoesNotExist()
        // Original list position is restored for the new detail, with the same persisted Z–A choice.
        compose.onNodeWithText("Track 99").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertTrue(direction.value)
            assertEquals("Track 99", played.first().name)
            assertEquals(100, played.size)
        }
        openFromDeepScroll()
        input().assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.EditableText,
            androidx.compose.ui.text.AnnotatedString(""),
        ))
    }

    @Test fun addedDirectionsFeedTheVisiblePlaybackOrder() {
        val unknown = CatalogTrack("unknown", "Unknown", "Artist")
        val old = CatalogTrack("old", "Old", "Artist", addedAt = "2020-01-01T00:00:00Z")
        val recent = CatalogTrack("recent", "Recent", "Artist", addedAt = "2026-01-01T00:00:00Z")
        detail.value = detail.value.copy(tracks = listOf(unknown, old, recent))
        mount(sort = TrackSort.ADDED)
        compose.onNodeWithText("Recent").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(recent, old, unknown), played); direction.value = true }
        compose.onNodeWithText("Old").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(old, recent, unknown), played) }
    }

    @Test fun allUnknownDatesExplainsPreservedOrder() {
        mount(sort = TrackSort.ADDED)
        compose.onNodeWithText(context.getString(R.string.playlist_added_dates_unavailable))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Track 0").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(tracks, played) }
    }

    @Test fun titleAscendingStillUsesAToZ() {
        mount(sort = TrackSort.TITLE)
        compose.onNodeWithText("Track 0").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(tracks.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }), played)
        }
    }

    @Test fun artistAboutItemDoesNotOffsetTheSearchTarget() {
        detail.value = detail.value.copy(kind = MainViewModel.DetailKind.ARTIST)
        mount()
        openFromDeepScroll()
        input().performTextInput("caffe")
        compose.onNodeWithText("Caffè").assertIsDisplayed()
    }
}
