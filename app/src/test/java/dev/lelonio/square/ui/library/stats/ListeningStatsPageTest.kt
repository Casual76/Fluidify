// SPDX-License-Identifier: GPL-3.0-only
package dev.lelonio.square.ui.library.stats

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import dev.lelonio.square.ui.theme.SquareTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35], qualifiers = "en-rUS-w412dp-h892dp-xhdpi")
class ListeningStatsPageTest {
    @get:Rule val compose = createComposeRule()

    /** A month step is a glass lens inside its 48 dp target: the tag is on the target, the action on the lens. */
    private fun monthStep(tag: String) = compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag(tag)))

    private fun showReady(fontScale: Float = 1f) = compose.setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            SquareTheme(darkTheme = false, amoled = false, systemBars = false) {
                ListeningStatsPage(ListeningStatsFixtures.ready, ListeningStatsFixtures.period.month,
                    {}, {}, {}, {}, {}, demo = true)
            }
        }
    }

    @Test fun monthButtonsRemainUsableAndCannotAdvancePastCurrentMonth() {
        var previous = 0
        var next = 0
        compose.setContent {
            SquareTheme(darkTheme = false, amoled = false, systemBars = false) {
                ListeningStatsPage(ListeningStatsFixtures.ready, ListeningStatsFixtures.period.month,
                    { previous++ }, { next++ }, {}, {}, {}, demo = true)
            }
        }
        compose.onNodeWithTag("listening-stats-demo").assertExists()
        compose.onNodeWithTag("listening-stats-next").assertExists()
        monthStep("listening-stats-next").assertIsNotEnabled()
        monthStep("listening-stats-previous").assertIsEnabled().performClick()
        assertEquals(1, previous)
        assertEquals(0, next)
    }

    @Test fun chartRowsGiveRankAndCountTheirOwnColumnsWhereThereIsRoom() {
        showReady()
        val second = ListeningStatsFixtures.summary.tracks[1].track.name
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(second))
        // One row, one node: rank, name and count are read together and none is lost.
        compose.onNode(hasText(second) and hasText("2") and hasText("25 listens")).assertExists()
    }

    @Test @Config(qualifiers = "en-rUS-w320dp-h760dp-xhdpi")
    fun chartRowsStackOnANarrowPhoneWithLargeText() {
        showReady(fontScale = 2f)
        val stacked = "2. " + ListeningStatsFixtures.summary.tracks[1].track.name
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(stacked))
        compose.onNode(hasText(stacked) and hasText("25 listens")).assertExists()
        compose.onNodeWithTag("listening-stats-partial").assertIsDisplayed()
    }

    @Test fun loadingErrorEmptyAndAbsentAccountHaveSeparateSemanticsAndNoOldSong() {
        val shown = mutableStateOf(ListeningStatsFixtures.ready)
        compose.setContent {
            SquareTheme(darkTheme = false, amoled = false, systemBars = false) {
                ListeningStatsPage(shown.value, ListeningStatsFixtures.period.month, {}, {}, {}, {}, {}, demo = true)
            }
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("listening-stats-song"))
        compose.onNodeWithTag("listening-stats-song").assertExists()
        val cases = listOf(
            ListeningStatsFixtures.ready.copy(content = StatsContent.Loading) to "loading",
            ListeningStatsFixtures.ready.copy(content = StatsContent.Error) to "error",
            ListeningStatsFixtures.empty() to "empty",
            ListeningStatsFixtures.noAccount() to "no-account",
        )
        cases.forEach { (state, tag) ->
            compose.runOnIdle { shown.value = state }
            compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("listening-stats-$tag"))
            compose.onNodeWithTag("listening-stats-$tag").assertExists()
            compose.onNodeWithTag("listening-stats-song").assertDoesNotExist()
        }
        compose.onNodeWithTag("listening-stats-reload").assertDoesNotExist()
    }

    @Test fun importedOnlyMonthNeverCreatesAMeasuredSpotifyMinute() {
        val stats = ListeningStatsFixtures.summary.copy(plays = 1, localPlays = 0, spotifyOnlyPlays = 1,
            measuredLocalMs = 0, tracks = emptyList(), artists = emptyList())
        compose.setContent {
            SquareTheme(darkTheme = false, amoled = false, systemBars = false) {
                ListeningStatsPage(ListeningStatsFixtures.ready.copy(content = StatsContent.Ready(stats)),
                    ListeningStatsFixtures.period.month, {}, {}, {}, {}, {}, demo = true)
            }
        }
        compose.onNodeWithTag("listening-stats-empty").assertDoesNotExist()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Minutes on this device"))
        compose.onNodeWithText("Minutes on this device").assertExists()
        compose.onNodeWithText("0").assertExists()
        compose.onNodeWithTag("listening-stats-partial").assertIsDisplayed()
        compose.onNodeWithTag("listening-stats-coverage").assertDoesNotExist()
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("listening-stats-details-toggle"))
        compose.onNodeWithTag("listening-stats-details-toggle").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("listening-stats-coverage"))
        compose.onNodeWithText("Partial history").assertExists()
    }

    @Test fun detailsAreSecondaryAndCanCloseWhileThePartialNoticeStaysVisible() {
        compose.setContent {
            SquareTheme(darkTheme = false, amoled = false, systemBars = false) {
                ListeningStatsPage(ListeningStatsFixtures.ready, ListeningStatsFixtures.period.month,
                    {}, {}, {}, {}, {}, demo = true)
            }
        }
        compose.onNodeWithTag("listening-stats-coverage").assertDoesNotExist()
        compose.onNodeWithTag("listening-stats-partial").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("listening-stats-details-toggle"))
        compose.onNodeWithTag("listening-stats-details-toggle").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("listening-stats-coverage"))
        compose.onNodeWithTag("listening-stats-coverage").assertExists()
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("listening-stats-details-toggle"))
        compose.onNodeWithTag("listening-stats-details-toggle").performClick()
        compose.onNodeWithTag("listening-stats-coverage").assertDoesNotExist()
        compose.onNodeWithTag("listening-stats-partial").assertIsDisplayed()
    }
}
