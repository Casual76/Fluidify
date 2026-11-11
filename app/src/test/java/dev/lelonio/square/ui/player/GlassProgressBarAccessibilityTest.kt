package dev.lelonio.square.ui.player

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lelonio.square.R
import dev.lelonio.square.ui.theme.SquareTheme
import dev.antigravity.fluidengine.ui.fluid.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w400dp-h600dp-mdpi")
class GlassProgressBarAccessibilityTest {
    @get:Rule val compose = createComposeRule()
    private val position = mutableStateOf(25_000L)
    private val duration = mutableStateOf(100_000L)
    private val interactive = mutableStateOf(true)
    private val amount = mutableStateOf(1f)
    private val calls = mutableListOf<Long>()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun node() = compose.onNodeWithTag("seek")
    private fun mount() {
        compose.setContent {
            Box(Modifier.width(320.dp)) {
                GlassProgressBar(position, duration.value,
                    { calls.add(it); position.value = it }, Color.Magenta, Color.Gray,
                    Modifier.testTag("seek"), amount = { amount.value }, interactive = interactive.value)
            }
        }
    }

    @Test fun exposesCurrentPositionAndSemanticSeekCommitsOnce() {
        mount()
        val info = node().fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(25_000f, info.current, 0f)
        assertEquals(0f..100_000f, info.range)
        node().performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(60_000f)) }
        assertEquals(listOf(60_000L), calls)
        compose.onNodeWithContentDescription(context.getString(R.string.player_seek_position)).assertExists()
    }

    @Test fun keyboardMovesTenSecondsAndHomeEndClamp() {
        mount()
        node().performSemanticsAction(SemanticsActions.RequestFocus)
        node().performKeyInput { pressKey(Key.DirectionRight) }
        node().performKeyInput { pressKey(Key.DirectionLeft) }
        node().performKeyInput { pressKey(Key.MoveHome) }
        node().performKeyInput { pressKey(Key.MoveEnd) }
        assertEquals(listOf(35_000L, 25_000L, 0L, 100_000L), calls)
    }

    @Test fun interactiveTargetHasMinimumHeight() {
        mount()
        node().assertHeightIsAtLeast(48.dp)
    }

    @Test fun foldedDecorationDoesNotAcceptInputOrAdvertiseSeeking() {
        amount.value = 0f
        interactive.value = false
        mount()
        node().assertHeightIsEqualTo(3.dp)
        assertNull(node().fetchSemanticsNode().config.getOrNull(SemanticsActions.SetProgress))
        node().performTouchInput { click() }
        assertTrue(calls.isEmpty())
    }

    @Test fun unknownDurationDoesNotSeekOnTapOrDrag() {
        duration.value = 0L
        mount()
        node().performTouchInput {
            click()
            swipe(Offset(20f, centerY), Offset(width - 20f, centerY), 250)
        }
        assertTrue(calls.isEmpty())
        assertNull(node().fetchSemanticsNode().config.getOrNull(SemanticsActions.SetProgress))
    }

    @Test fun pointerDragCommitsOnlyOnRelease() {
        mount()
        node().performTouchInput {
            down(Offset(30f, centerY))
            moveTo(Offset(150f, centerY))
        }
        assertTrue(calls.isEmpty())
        node().performTouchInput { up() }
        assertEquals(1, calls.size)
        assertTrue(calls.single() in 40_000L..60_000L)
    }

    @Test fun customActionsUseTenSecondsAndRespectBounds() {
        mount()
        val backActions = node().fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle {
            val actions = backActions
            assertTrue(actions.single { it.label == context.getString(R.string.player_seek_back) }.action())
        }
        compose.runOnIdle { assertEquals(listOf(15_000L), calls); position.value = 95_000L }
        val forwardActions = node().fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle {
            val actions = forwardActions
            assertTrue(actions.single { it.label == context.getString(R.string.player_seek_forward) }.action())
        }
        assertEquals(listOf(15_000L, 100_000L), calls)
    }

    @Test fun nonFiniteSemanticRequestIsRejectedAndFiniteValuesClamp() {
        mount()
        node().performSemanticsAction(SemanticsActions.SetProgress) {
            assertFalse(it(Float.NaN)); assertFalse(it(Float.POSITIVE_INFINITY))
            assertTrue(it(150_000f))
        }
        assertEquals(listOf(100_000L), calls)
    }

    @Test fun foldingDuringDragCancelsWithoutSeek() {
        mount()
        node().performTouchInput { down(Offset(30f, centerY)); moveTo(Offset(150f, centerY)) }
        compose.runOnIdle { interactive.value = false; amount.value = 0f }
        node().performTouchInput { up() }
        assertTrue(calls.isEmpty())
    }

    @Test fun mainScrubberUsesAppLabelsAndLocalizedSpokenValue() {
        compose.setContent {
            SquareTheme(darkTheme = false, amoled = false, systemBars = false) {
                val backdrop = rememberGlassBackdrop()
                CompositionLocalProvider(LocalGlassBackdrop provides backdrop,
                    LocalFluidMotionPolicy provides FluidMotionPolicy(0f)) {
                    PlaybackScrubber(position, duration.value, { calls.add(it) })
                }
            }
        }
        val node = compose.onNodeWithContentDescription(context.getString(R.string.player_seek_position))
        val config = node.fetchSemanticsNode().config
        assertEquals(context.getString(R.string.player_seek_value, "0:25", "1:40"),
            config[SemanticsProperties.StateDescription])
        val labels = config[SemanticsActions.CustomActions].map { it.label }
        assertEquals(listOf(context.getString(R.string.player_seek_back),
            context.getString(R.string.player_seek_forward)), labels)
    }
}
