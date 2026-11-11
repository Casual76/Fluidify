// SPDX-License-Identifier: GPL-3.0-only
package dev.lelonio.square.ui.library.stats

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import dev.lelonio.square.ui.theme.SquareTheme
import dev.lelonio.square.ui.navigation.FluidifyPageSurface
import android.graphics.BitmapFactory
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Prepared captures, not recorded or approved. No root application, accounts, art URLs or service. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35], qualifiers = "it-rIT-w412dp-h892dp-xhdpi")
class ListeningStatsScreenshots {
    @get:Rule val compose = createComposeRule()
    private fun capture(name: String, dark: Boolean = false, fontScale: Float = 1f,
        state: ListeningStatsState = ListeningStatsFixtures.ready, scrollTo: String? = null) {
        val output = File("build/listening-stats/captures").apply { mkdirs() }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                SquareTheme(darkTheme = dark, amoled = false, systemBars = false) {
                    FluidifyPageSurface {
                    ListeningStatsPage(state, ListeningStatsFixtures.period.month, {}, {}, {}, {}, {}, demo = true)
                    }
                }
            }
        }
        if (scrollTo == "listening-stats-coverage" || scrollTo == "listening-stats-receipts-anchor") {
            compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("listening-stats-details-toggle"))
            compose.onNodeWithTag("listening-stats-details-toggle").performClick()
        }
        if (scrollTo != null) compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag(scrollTo))
        // The persistent notice overlays the lazy viewport: scroll the metric above that notice.
        if (scrollTo == "listening-stats-minutes") compose.onNode(hasScrollAction())
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 400f) }
        compose.onRoot().captureRoboImage(File(output, "$name.png").path)
        if (dark) {
            val bitmap = BitmapFactory.decodeFile(File(output, "$name.png").path)
            val pixel = bitmap.getPixel(bitmap.width - 4, bitmap.height / 3)
            assertTrue("Dark page paper must stay dark outside the cards", android.graphics.Color.red(pixel) < 100)
        }
    }
    @Test fun phoneLight() = capture("page-phone-light")
    @Test fun phoneDark() = capture("page-phone-dark", dark = true)
    @Test @Config(qualifiers = "it-rIT-w320dp-h760dp-xhdpi")
    fun narrowLargeText() = capture("page-320-font200", fontScale = 2f)
    @Test @Config(qualifiers = "it-rIT-w320dp-h760dp-xhdpi")
    fun narrowLargeTotals() = capture("page-320-font200-totals", fontScale = 2f, scrollTo = "listening-stats-minutes")
    @Test @Config(qualifiers = "it-rIT-w320dp-h760dp-xhdpi")
    fun narrowLargeTracks() = capture("page-320-font200-tracks", fontScale = 2f, scrollTo = "listening-stats-tracks")
    @Test @Config(qualifiers = "it-rIT-w800dp-h1280dp-xhdpi")
    fun tablet() = capture("page-tablet")
    @Test fun tracks() = capture("page-tracks", scrollTo = "listening-stats-tracks")
    @Test fun artists() = capture("page-artists", scrollTo = "listening-stats-artists")
    @Test fun coverage() = capture("page-coverage", scrollTo = "listening-stats-coverage")
    @Test fun receipts() = capture("page-receipts", scrollTo = "listening-stats-receipts-anchor")
    @Test fun empty() = capture("page-empty", state = ListeningStatsFixtures.empty())
    @Test fun signedOut() = capture("page-no-account", state = ListeningStatsFixtures.noAccount())
    @Test fun error() = capture("page-error", state = ListeningStatsFixtures.ready.copy(content = StatsContent.Error))
    @Test fun loading() = capture("page-loading", state = ListeningStatsFixtures.ready.copy(content = StatsContent.Loading))
}
