// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.ui.airpods

import android.app.Application
import android.content.Context
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.antigravity.fluidengine.ui.fluid.*
import dev.lelonio.square.R
import dev.lelonio.square.ui.theme.SquareTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real settings and modal hosts over an underlying player stand-in; no radio or runtime owner. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "en-rUS-w800dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AirPodsPlayerSettingsOverlayTest {
    @get:Rule val compose = createComposeRule()

    @Test fun backClosesConfirmationThenSettingsAndOnlyThenReachesThePlayer() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var visible by mutableStateOf(true)
        var playerBacks = 0
        var dispatcher: OnBackPressedDispatcherOwner? = null
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current
            SquareTheme(darkTheme = false, amoled = false, systemBars = false) {
                val ground = rememberGlassBackdrop()
                CompositionLocalProvider(LocalFluidMotionPolicy provides FluidMotionPolicy(0f)) {
                    Box(Modifier.fillMaxSize()) {
                        Text("Expanded player", Modifier.testTag("underlying-player"))
                        BackHandler { playerBacks++ }
                        AirPodsPlayerSettingsOverlay(visible, { visible = false }, ground) { close, backdrop ->
                            AirPodsSettingsPages(
                                uiFixture().copy(availability = mapOf("setup.FORGET" to UiAvailability(true))),
                                {}, PaddingValues(0.dp), backdrop, close,
                            )
                        }
                    }
                }
            }
        }
        fun back() {
            compose.runOnIdle { requireNotNull(dispatcher).onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
        }
        compose.onNodeWithTag("airpods-player-settings").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.airpods_feature_forget))
            .performScrollTo().performTouchInput { click(center) }
        compose.onNodeWithText(context.getString(R.string.airpods_ui_forget_confirm)).assertIsDisplayed()
        back()
        compose.onNodeWithText(context.getString(R.string.airpods_ui_forget_confirm)).assertDoesNotExist()
        compose.onNodeWithTag("airpods-player-settings").assertIsDisplayed()
        assertEquals(0, playerBacks)
        back()
        compose.onNodeWithTag("airpods-player-settings").assertDoesNotExist()
        compose.onNodeWithTag("underlying-player").assertIsDisplayed()
        assertEquals(0, playerBacks)
        back()
        assertEquals(1, playerBacks)
    }
}
