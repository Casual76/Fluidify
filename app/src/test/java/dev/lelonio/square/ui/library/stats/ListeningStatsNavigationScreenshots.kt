// SPDX-License-Identifier: GPL-3.0-only
// Copy into app/src/test/java/dev/lelonio/square/ui/library/stats ONLY after navigation.patch.
package dev.lelonio.square.ui.library.stats

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.lelonio.square.ui.navigation.FluidifyPageHost
import dev.lelonio.square.SquareApplication
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.ui.MainViewModel
import dev.lelonio.square.ui.library.LibraryScreen
import dev.lelonio.square.ui.theme.SquareTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** Suppress production startup; use only the actual Library's inert local preference container. */
@Implements(SquareApplication::class)
class ListeningStatsNoStartupShadow : org.robolectric.shadows.ShadowApplication() {
    @Implementation fun onCreate() = Unit
}

/** Actual LibraryScreen before/after plus FluidPageHost. Not proof of full SquareApp/device wiring. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = SquareApplication::class, sdk = [35], qualifiers = "it-rIT-w412dp-h892dp-xhdpi",
    shadows = [ListeningStatsNoStartupShadow::class])
class ListeningStatsNavigationScreenshots {
    @get:Rule val compose = createComposeRule()
    private fun capture(after: Boolean, grid: Boolean, navigate: Boolean = false, back: Boolean = false,
        dark: Boolean = false, fontScale: Float = 1f, variant: String = "phone") {
        val context = ApplicationProvider.getApplicationContext<Context>()
        (context as SquareApplication).libraryView.apply { this.grid = grid; order = null; descending = false }
        val output = File("build/listening-stats/captures").apply { mkdirs() }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            SquareTheme(darkTheme = dark, amoled = false, systemBars = false) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                var stack by remember { mutableStateOf(listOf("library")) }
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                    FluidifyPageHost(stack = stack, onBack = { stack = stack.dropLast(1) }) { page ->
                        if (page == "library") LibraryScreen(
                            state = MainViewModel.UiState.Ready("DEMO", listOf(
                                CatalogPlaylist("demo:playlist-one", "DEMO · Musica del mattino"),
                                CatalogPlaylist("demo:playlist-two", "DEMO · Viaggi immaginari"))),
                            contentPadding = PaddingValues(top = 24.dp, bottom = 80.dp),
                            ground = rememberGlassBackdrop(),
                            onLogIn = {}, onRetry = {}, onLogOut = {}, onOpenPlaylist = {},
                            playlistOrder = emptyList(),
                            onOpenListeningStats = if (after) ({ stack = stack + "stats" }) else null,
                        ) else ListeningStatsPage(ListeningStatsFixtures.ready, ListeningStatsFixtures.period.month,
                            {}, {}, {}, {}, { stack = stack.dropLast(1) }, demo = true)
                    }
                    if (stack.last() == "library") Text("DEMO · Libreria con dati inventati", style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(12.dp))
                }
                }
            }
            }
        }
        if (after) compose.onNodeWithTag("listening-stats-entry").assertExists()
        else compose.onNodeWithTag("listening-stats-entry").assertDoesNotExist()
        if (navigate) {
            compose.onNodeWithTag("listening-stats-entry").performClick()
            compose.onNodeWithTag("listening-stats-page").assertExists()
            if (back) {
                // The page owns the back action; use its actual accessible Fluid back control.
                val label = context.getString(dev.lelonio.square.R.string.back)
                compose.onNodeWithContentDescription(label).performClick()
                compose.onNodeWithTag("listening-stats-entry").assertExists()
            }
        }
        val suffix = when { back -> "back"; navigate -> "page"; after -> "after"; else -> "before" }
        compose.onRoot().captureRoboImage(File(output, "library-${if (grid) "grid" else "list"}-$variant-$suffix.png").path)
    }
    @Test fun gridBefore() = capture(after = false, grid = true)
    @Test fun gridAfter() = capture(after = true, grid = true)
    @Test fun listBefore() = capture(after = false, grid = false)
    @Test fun listAfter() = capture(after = true, grid = false)
    @Test fun entryOpensPage() = capture(after = true, grid = true, navigate = true)
    @Test fun backReturnsToLibrary() = capture(after = true, grid = true, navigate = true, back = true)
    @Test fun darkBefore() = capture(after = false, grid = true, dark = true, variant = "dark")
    @Test fun darkAfter() = capture(after = true, grid = true, dark = true, variant = "dark")
    @Test @Config(qualifiers = "it-rIT-w320dp-h760dp-xhdpi")
    fun largeFontBefore() = capture(after = false, grid = true, fontScale = 2f, variant = "320-font200")
    @Test @Config(qualifiers = "it-rIT-w320dp-h760dp-xhdpi")
    fun largeFontAfter() = capture(after = true, grid = true, fontScale = 2f, variant = "320-font200")
    @Test @Config(qualifiers = "it-rIT-w800dp-h1280dp-xhdpi")
    fun tabletBefore() = capture(after = false, grid = true, variant = "tablet")
    @Test @Config(qualifiers = "it-rIT-w800dp-h1280dp-xhdpi")
    fun tabletAfter() = capture(after = true, grid = true, variant = "tablet")
}
