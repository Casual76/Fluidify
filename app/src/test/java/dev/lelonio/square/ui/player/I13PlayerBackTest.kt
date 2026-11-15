package dev.lelonio.square.ui.player

import android.app.Application
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.antigravity.fluidengine.ui.fluid.*
import dev.lelonio.square.ui.theme.SquareTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class)
class I13PlayerBackTest {
    @get:Rule val compose = createComposeRule()

    @Test fun everyInternalPanelClosesBeforeTheFullscreenParent() {
        val panel = mutableStateOf(PlayerPanel.NONE)
        var collapses = 0
        lateinit var dispatcher: OnBackPressedDispatcher
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            BackHandler { collapses++ }
            I13PlayerBackHandler(panel.value, false,
                onClosePanel = { panel.value = PlayerPanel.NONE }, onExitImmersive = {})
        }
        for (kind in PlayerPanel.entries.filter { it != PlayerPanel.NONE }) {
            val before = collapses
            compose.runOnIdle { panel.value = kind }
            compose.runOnIdle { dispatcher.onBackPressed() }
            compose.runOnIdle {
                assertEquals(PlayerPanel.NONE, panel.value)
                assertEquals("$kind collapsed the fullscreen player", before, collapses)
            }
            compose.runOnIdle { dispatcher.onBackPressed() }
            compose.runOnIdle { assertEquals(before + 1, collapses) }
        }
    }

    @Test fun hostModalAndItsSubpagePrecedePanelImmersionAndCollapse() {
        val modal = mutableStateOf(true)
        val subpage = mutableStateOf(true)
        val panel = mutableStateOf(PlayerPanel.QUEUE)
        val immersive = mutableStateOf(true)
        val events = mutableListOf<String>()
        lateinit var dispatcher: OnBackPressedDispatcher
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            BackHandler(enabled = !modal.value) { events += "collapse" }
            I13PlayerBackHandler(panel.value, immersive.value, enabled = !modal.value,
                onClosePanel = { events += "panel"; panel.value = PlayerPanel.NONE },
                onExitImmersive = { events += "immersion"; immersive.value = false })
            if (modal.value) {
                BackHandler { events += "modal"; modal.value = false }
                if (subpage.value) BackHandler { events += "subpage"; subpage.value = false }
            }
        }
        repeat(5) { compose.runOnIdle { dispatcher.onBackPressed() }; compose.waitForIdle() }
        assertEquals(listOf("subpage", "modal", "panel", "immersion", "collapse"), events)
    }

    @Test fun cancelledPredictiveBackLeavesPanelAndRealSheetOpen() {
        val progress = Animatable(1f)
        val panel = mutableStateOf(PlayerPanel.QUEUE)
        lateinit var dispatcher: OnBackPressedDispatcher
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            SquareTheme(darkTheme = false, amoled = false, systemBars = false) {
                val backdrop = rememberGlassBackdrop()
                CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
                    Box(Modifier.requiredSize(400.dp, 700.dp)) {
                        NowPlayingSheet(progress, remember { mutableStateOf(Rect(24f, 600f, 376f, 660f)) },
                            backdrop, pillFace = {}, background = {}, expandedContent = {
                                I13PlayerBackHandler(panel.value, false,
                                    onClosePanel = { panel.value = PlayerPanel.NONE }, onExitImmersive = {})
                            })
                    }
                }
            }
        }
        compose.runOnIdle {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 200f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(80f, 200f, .4f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackCancelled()
        }
        compose.runOnIdle {
            assertEquals(PlayerPanel.QUEUE, panel.value)
            assertEquals(1f, progress.value, .001f)
            dispatcher.onBackPressed()
        }
        compose.runOnIdle {
            assertEquals(PlayerPanel.NONE, panel.value)
            assertEquals(1f, progress.value, .001f)
        }
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0f, progress.value, .001f) }
    }
}
