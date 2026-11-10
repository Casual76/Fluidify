// SPDX-License-Identifier: GPL-3.0-only
package dev.lelonio.square.ui.library.stats

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.lelonio.square.SquareApplication
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The section on the debug glass stage ([ListeningStatsGlassStage]): the real page host, the real
 * Library, the artwork canvas wired as in SquareApp, invented data. Prepared captures, not
 * recorded or approved here.
 *
 * Like for like: the stage and this file use nothing the section did not expose before its glass
 * was reworked, so they run unchanged on the earlier sources. The directory is chosen by the
 * environment, so the two runs cannot overwrite each other:
 *
 *     LISTENING_STATS_CAPTURE=before   -> app/build/listening-stats-glass/before/
 *     (unset)                          -> app/build/listening-stats-glass/after/
 *
 * What these can and cannot show. Robolectric draws in software unless it is told otherwise, and
 * a software canvas has no RenderEffect: layout, type, spacing, wrapping and theme sides are
 * faithful here, the refraction and blur of the material are not. The material is judged on the
 * same stage on a device, through ListeningStatsGlassPreviewActivity.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = SquareApplication::class, sdk = [35], qualifiers = "it-rIT-w412dp-h892dp-xhdpi",
    shadows = [ListeningStatsNoStartupShadow::class])
class ListeningStatsGlassScreenshots {
    @get:Rule val compose = createComposeRule()

    private val variant = (System.getenv("LISTENING_STATS_CAPTURE") ?: "after")
        .filter { it.isLetterOrDigit() || it == '-' }.ifEmpty { "after" }

    private fun shoot(name: String) {
        val output = File("build/listening-stats-glass/$variant").apply { mkdirs() }
        compose.onRoot().captureRoboImage(File(output, "$name.png").path)
    }

    private fun page(name: String, dark: Boolean = false, fontScale: Float = 1f,
        state: ListeningStatsState = ListeningStatsFixtures.ready, scrollTo: String? = null) {
        compose.setContent { ListeningStatsGlassStage(dark = dark, fontScale = fontScale, state = state) }
        compose.onNodeWithTag("listening-stats-page").assertExists()
        if (scrollTo == "listening-stats-coverage" || scrollTo == "listening-stats-receipts-anchor") {
            compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("listening-stats-details-toggle"))
            compose.onNodeWithTag("listening-stats-details-toggle").performClick()
        }
        if (scrollTo != null) compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag(scrollTo))
        // The persistent notice overlays the lazy viewport: lift the target clear of it.
        if (scrollTo == "listening-stats-minutes") compose.onNode(hasScrollAction())
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 400f) }
        shoot(name)
    }

    private fun library(name: String, grid: Boolean, dark: Boolean = false, fontScale: Float = 1f) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        (context as SquareApplication).libraryView.apply { this.grid = grid; order = null; descending = false }
        compose.setContent { ListeningStatsGlassStage(dark = dark, fontScale = fontScale, library = true) }
        compose.onNodeWithTag("listening-stats-entry").assertExists()
        shoot(name)
    }

    /** "The same DEMO data" is a claim about two source sets; this is what holds it true. */
    @Test fun demoDataIsTheFixture() {
        assertEquals(ListeningStatsFixtures.ready, ListeningStatsDemo.ready)
        assertEquals(ListeningStatsFixtures.empty(), ListeningStatsDemo.empty)
        assertEquals(ListeningStatsFixtures.noAccount(), ListeningStatsDemo.noAccount)
        assertEquals(ListeningStatsFixtures.period, ListeningStatsDemo.period)
    }

    @Test fun entryOpensThePageOnTheStage() {
        compose.setContent { ListeningStatsGlassStage(dark = true, library = true) }
        compose.onNodeWithTag("listening-stats-entry").performClick()
        compose.onNodeWithTag("listening-stats-page").assertExists()
        compose.onNodeWithTag("listening-stats-partial").assertIsDisplayed()
    }

    @Test fun pageLight() = page("page-412-light")
    @Test fun pageDark() = page("page-412-dark", dark = true)
    @Test fun pageDarkTracks() = page("page-412-dark-tracks", dark = true, scrollTo = "listening-stats-tracks")
    @Test fun pageLightArtists() = page("page-412-light-artists", scrollTo = "listening-stats-artists")
    @Test fun pageDarkCoverage() = page("page-412-dark-coverage", dark = true, scrollTo = "listening-stats-coverage")
    @Test fun pageLightReceipts() = page("page-412-light-receipts", scrollTo = "listening-stats-receipts-anchor")
    @Test fun pageDarkEmpty() = page("page-412-dark-empty", dark = true, state = ListeningStatsFixtures.empty())
    @Test fun pageLightNoAccount() = page("page-412-light-no-account", state = ListeningStatsFixtures.noAccount())
    @Test fun pageDarkError() = page("page-412-dark-error", dark = true,
        state = ListeningStatsFixtures.ready.copy(content = StatsContent.Error))
    @Test fun pageLightLoading() = page("page-412-light-loading",
        state = ListeningStatsFixtures.ready.copy(content = StatsContent.Loading))

    @Test @Config(qualifiers = "it-rIT-w320dp-h760dp-xhdpi")
    fun narrowLargeLight() = page("page-320-font200-light", fontScale = 2f)
    @Test @Config(qualifiers = "it-rIT-w320dp-h760dp-xhdpi")
    fun narrowLargeDark() = page("page-320-font200-dark", dark = true, fontScale = 2f)
    @Test @Config(qualifiers = "it-rIT-w320dp-h760dp-xhdpi")
    fun narrowLargeTotals() = page("page-320-font200-dark-totals", dark = true, fontScale = 2f,
        scrollTo = "listening-stats-minutes")
    @Test @Config(qualifiers = "it-rIT-w320dp-h760dp-xhdpi")
    fun narrowLargeTracks() = page("page-320-font200-light-tracks", fontScale = 2f, scrollTo = "listening-stats-tracks")
    @Test @Config(qualifiers = "it-rIT-w320dp-h760dp-xhdpi")
    fun narrowPlain() = page("page-320-dark", dark = true)
    @Test @Config(qualifiers = "it-rIT-w800dp-h1280dp-xhdpi")
    fun tablet() = page("page-tablet-dark", dark = true)

    @Test fun libraryGridLight() = library("library-grid-412-light", grid = true)
    @Test fun libraryGridDark() = library("library-grid-412-dark", grid = true, dark = true)
    @Test fun libraryListLight() = library("library-list-412-light", grid = false)
    @Test fun libraryListDark() = library("library-list-412-dark", grid = false, dark = true)
    @Test @Config(qualifiers = "it-rIT-w320dp-h760dp-xhdpi")
    fun libraryNarrowLarge() = library("library-grid-320-font200-dark", grid = true, dark = true, fontScale = 2f)
}
