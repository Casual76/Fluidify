package dev.lelonio.square.ui.player

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.antigravity.fluidengine.ui.fluid.*
import dev.lelonio.square.ui.theme.SquareTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real wide-panel slot and the same hidden admission used by SquareApp, without a live account. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = "w1400dp-h900dp-mdpi")
class HiddenPanelExtensionTest {
    @get:Rule val compose = createComposeRule()
    private val hidden = mutableStateOf(false)
    private val reach = mutableStateOf(2f)
    private val calls = mutableListOf<Int>()
    private lateinit var inputMode: InputModeManager
    private fun row() = compose.onNode(hasText("Queue target") and hasClickAction())

    private fun mount() {
        val queue = listOf(QueueEntry(0, "queue-1", "Queue target", "Artist", null, false, false))
        compose.setContent {
            inputMode = LocalInputModeManager.current
            SquareTheme(darkTheme = false, amoled = false, systemBars = false) {
                val backdrop = rememberGlassBackdrop()
                CompositionLocalProvider(LocalGlassBackdrop provides backdrop,
                    LocalFluidMotionPolicy provides FluidMotionPolicy(0f)) {
                    val position = remember { mutableStateOf(25_000L) }
                    Column(Modifier.requiredSize(1400.dp, 900.dp).testTag("fixture")) {
                        Button(onClick = {}, modifier = Modifier.testTag("before")) { Text("Before") }
                        Box(Modifier.height(780.dp).graphicsLayer { alpha = if (hidden.value) 0f else 1f }
                            .then(if (hidden.value) Modifier.clearAndSetSemantics {} else Modifier)) {
                            NowPlayingPanel(
                                state = PlaybackState(hasItem = true, mediaId = "track", title = "Panel", durationMs = 100_000L),
                                positionMs = position, playingOn = null,
                                reach = reach, interactive = !hidden.value,
                                geometry = PanelGeometry.of(LocalDensity.current, 1400.dp, 780f, null),
                                extension = {
                                    PanelExtension(null, false, position, false, {}, queue,
                                        { calls.add(it) }, {}, { _, _ -> },
                                        interactive = !hidden.value && reach.value >= 2f)
                                },
                                onOpen = {}, onTogglePlay = {}, onNext = {}, onPrevious = {},
                            )
                        }
                        Button(onClick = {}, modifier = Modifier.testTag("after")) { Text("After") }
                    }
                }
            }
        }
        compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
    }

    @Test fun tabFromOutsideSkipsHiddenQueueAndRevealingRestoresItsActions() {
        mount()
        row().performSemanticsAction(SemanticsActions.RequestFocus)
        row().assertIsFocused()
        compose.onNodeWithTag("before").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.runOnIdle { hidden.value = true }
        row().assertDoesNotExist()
        compose.onNodeWithTag("fixture").performKeyInput { pressKey(Key.Tab); pressKey(Key.Enter) }
        assertTrue("A hidden queue must not receive newly traversed keyboard input", calls.isEmpty())
        compose.onNodeWithTag("after").assertIsFocused()
        compose.runOnIdle { hidden.value = false }
        row().performSemanticsAction(SemanticsActions.RequestFocus)
        row().performKeyInput { pressKey(Key.Enter) }
        assertEquals(listOf(0), calls)
    }

    @Test fun hidingTheFocusedQueueRemovesItsKeyboardAuthority() {
        mount()
        row().performSemanticsAction(SemanticsActions.RequestFocus)
        row().assertIsFocused()
        compose.runOnIdle { hidden.value = true }
        row().assertDoesNotExist()
        compose.onNodeWithTag("fixture").performKeyInput { pressKey(Key.Enter) }
        assertTrue("Focus retained from the visible queue must not invoke it after hiding", calls.isEmpty())
    }

    @Test fun foldedExtensionDoesNotOfferAnInvisibleQueueAction() {
        mount()
        row().assertExists()
        for (amount in listOf(1f, 1.5f, 0f)) {
            compose.runOnIdle { reach.value = amount }
            row().assertDoesNotExist()
        }
        compose.runOnIdle { reach.value = 2f }
        row().assertExists()
    }
}
