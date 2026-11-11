// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.ui.library.stats

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.lelonio.square.R
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import java.io.File
import dev.lelonio.square.SquareApplication
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.ui.MainViewModel
import dev.lelonio.square.ui.library.LibraryScreen
import dev.lelonio.square.ui.theme.SquareTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual LibraryScreen and Engine header. Synthetic catalog, no account/device/provider. */
@OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = SquareApplication::class, sdk = [35],
    qualifiers = "it-rIT-w412dp-h892dp-xhdpi", shadows = [ListeningStatsNoStartupShadow::class])
class OfflineLibraryNoticeLayoutTest {
    @get:Rule val compose = createComposeRule()
    private fun checkNotice(grid: Boolean, fontScale: Float, manual: Boolean = false) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        (context as SquareApplication).libraryView.apply { this.grid = grid; order = null; descending = false }
        var retries = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                SquareTheme(darkTheme = false, amoled = false, systemBars = false) {
                    LibraryScreen(state = MainViewModel.UiState.Ready("DEMO", listOf(
                        CatalogPlaylist("demo:offline", "Downloaded playlist")), offline = true),
                        contentPadding = PaddingValues(top = 24.dp, bottom = 80.dp),
                        ground = rememberGlassBackdrop(), onLogIn = {}, onRetry = { retries++ }, onLogOut = {},
                        manualOffline = manual,
                        onOpenPlaylist = {}, playlistOrder = emptyList())
                }
            }
        }
        compose.waitForIdle()
        val sort = compose.onNodeWithContentDescription(context.getString(R.string.sort)).fetchSemanticsNode().boundsInRoot
        val notice = compose.onNodeWithText(context.getString(R.string.library_offline)).fetchSemanticsNode().boundsInRoot
        assertTrue("Offline notice overlaps the Library header: grid=$grid font=$fontScale sort=$sort notice=$notice",
            notice.top >= sort.bottom)
        val action = context.getString(R.string.library_offline_action)
        if (manual) compose.onNodeWithText(action).assertDoesNotExist()
        else {
            compose.onNodeWithText(action).performClick()
            assertTrue("Recoverable offline keeps its explicit retry", retries == 1)
        }
        val file = File("build/final-product-layout/library-grid-$grid-font-$fontScale-manual-$manual.png")
        file.parentFile.mkdirs()
        check(!file.exists() || file.delete())
        compose.onRoot().captureRoboImage(file.path,
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        check(file.isFile)
    }
    @Test fun gridFont100() = checkNotice(true, 1f)
    @Test fun listFont100() = checkNotice(false, 1f)
    @Test fun gridFont200() = checkNotice(true, 2f)
    @Test fun listFont200() = checkNotice(false, 2f)
    @Test fun manualGridFont100HasNoIneffectiveRetry() = checkNotice(true, 1f, true)
    @Test fun manualListFont200HasNoIneffectiveRetry() = checkNotice(false, 2f, true)
}
