package dev.lelonio.square.ui.airpods.host

import android.app.Application
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.antigravity.fluidengine.ui.fluid.*
import dev.lelonio.square.ui.airpods.*
import dev.lelonio.square.ui.theme.SquareTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36], application=Application::class, qualifiers="w800dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AirPodsHostNavigationTest {
    @get:Rule val compose = createComposeRule()
    private var visible by mutableStateOf(true)
    private var opened = false
    private var playerBacks = 0
    private var back: OnBackPressedDispatcherOwner? = null
    private fun content() {
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current
            val modal = rememberFluidGlassModalHostState()
            val backdrop = rememberGlassBackdrop()
            SquareTheme(darkTheme=true, amoled=false, systemBars=false) {
                CompositionLocalProvider(LocalFluidGlassModalHostState provides modal, LocalGlassBackdrop provides backdrop) {
                    androidx.activity.compose.BackHandler { playerBacks++ }
                    Box(Modifier.fillMaxSize().glassBackdropSource(backdrop)) { Text("Player") }
                    AirPodsQuickOverlay(uiFixture(), visible, { visible=false }, {}, {
                        assertFalse("Quick closes before full settings navigation", visible)
                        opened=true
                    })
                    FluidGlassModalHost(modal, backdrop)
                }
            }
        }
        compose.waitForIdle()
    }
    @Test fun fullSettingsClosesQuickBeforeNavigation() {
        content()
        compose.onNodeWithText("All AirPods settings", ignoreCase=true).assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(opened); assertFalse(visible) }
    }
    @Test fun quickBackWinsOverPlayerBack() {
        content()
        compose.runOnIdle { requireNotNull(back).onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(visible); assertEquals(0,playerBacks) }
        compose.runOnIdle { requireNotNull(back).onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertEquals(1,playerBacks) }
    }
}
