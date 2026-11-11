// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.ui

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.antigravity.fluidengine.ui.fluid.FluidMotionPolicy
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.antigravity.fluidengine.ui.fluid.LocalGlassBackdrop
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.lelonio.square.R
import dev.lelonio.square.ui.home.HomeScreen
import dev.lelonio.square.ui.library.LibraryScreen
import dev.lelonio.square.ui.theme.SquareTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Prepared only. Exercises the production transient branches with real pointer clicks.
 * No MainViewModel, application startup, native session, network or Bluetooth is created.
 * Proves entry callback availability; full Settings/debug navigation still needs device QA.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = "w360dp-h640dp-mdpi")
class TransientPhaseSemanticsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun connectingHomeOffersSettingsWithoutAccountActions() =
        assertSettingsEntry(MainViewModel.UiState.Connecting, library = false)

    @Test fun loadingHomeOffersSettingsWithoutAccountActions() =
        assertSettingsEntry(MainViewModel.UiState.Loading, library = false)

    @Test fun connectingLibraryOffersSettingsWithoutAccountActions() =
        assertSettingsEntry(MainViewModel.UiState.Connecting, library = true)

    @Test fun loadingLibraryOffersSettingsWithoutAccountActions() =
        assertSettingsEntry(MainViewModel.UiState.Loading, library = true)

    private fun assertSettingsEntry(state: MainViewModel.UiState, library: Boolean) {
        var opened = 0
        var logins = 0
        var retries = 0
        var logouts = 0
        compose.setContent {
            val ground = rememberGlassBackdrop()
            SquareTheme(darkTheme = true, amoled = false, systemBars = false) {
                CompositionLocalProvider(
                    LocalGlassBackdrop provides ground,
                    LocalFluidMotionPolicy provides FluidMotionPolicy(0f),
                ) {
                    if (library) {
                        LibraryScreen(
                            state = state,
                            contentPadding = PaddingValues(0.dp),
                            ground = ground,
                            onLogIn = { logins++ },
                            onRetry = { retries++ },
                            onLogOut = { logouts++ },
                            onOpenPlaylist = {},
                            onOpenSettings = { opened++ },
                            playlistOrder = emptyList(),
                        )
                    } else {
                        HomeScreen(
                            state = state,
                            contentPadding = PaddingValues(0.dp),
                            ground = ground,
                            onLogIn = { logins++ },
                            onRetry = { retries++ },
                            onLogOut = { logouts++ },
                            onOpenPlaylist = {},
                            playlistOrder = emptyList(),
                            recent = emptyList(),
                            onPlayRecent = { _, _ -> },
                            onPlayFeed = { _, _, _ -> },
                            feed = MainViewModel.FeedState(),
                            onOpenItem = {},
                            onOpenSettings = { opened++ },
                        )
                    }
                }
            }
        }

        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.onNodeWithText(context.getString(R.string.settings))
            .assertIsDisplayed()
            .assertHasClickAction()
            .performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, opened)
            assertEquals(0, logins)
            assertEquals(0, retries)
            assertEquals(0, logouts)
        }
    }
}
