package dev.lelonio.square.ui.player

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.antigravity.fluidengine.ui.fluid.*
import dev.lelonio.square.R
import dev.lelonio.square.data.LyricLine
import dev.lelonio.square.data.Lyrics
import dev.lelonio.square.ui.theme.SquareTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w400dp-h700dp-mdpi")
class I13PlayerAccessibilityTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var input: InputModeManager

    @Composable private fun Theme(content: @Composable () -> Unit) {
        input = LocalInputModeManager.current
        SquareTheme(darkTheme = false, amoled = false, systemBars = false) {
            val backdrop = rememberGlassBackdrop()
            CompositionLocalProvider(LocalGlassBackdrop provides backdrop,
                LocalGlassEnabled provides false,
                LocalFluidMotionPolicy provides FluidMotionPolicy(0f)) {
                Box(Modifier.requiredSize(400.dp, 700.dp)) { content() }
            }
        }
    }

    @Test fun translationSwitchIsOperableBySemanticsAndKeyboardWhileBusyCanCancel() {
        val enabled = mutableStateOf(false)
        val busy = mutableStateOf(false)
        val calls = mutableListOf<Boolean>()
        compose.setContent { Theme {
            TranslationToggle(enabled.value, busy.value, { calls += it; enabled.value = it },
                Modifier.testTag("translation"))
        } }
        val toggle = compose.onNodeWithTag("translation")
        toggle.assertIsOff().assertHasClickAction().performClick()
        toggle.assertIsOn()
        compose.runOnIdle { busy.value = true }
        toggle.performClick()
        toggle.assertIsOff()
        compose.runOnIdle { busy.value = false; input.requestInputMode(InputMode.Keyboard) }
        toggle.performSemanticsAction(SemanticsActions.RequestFocus)
        toggle.performKeyInput { pressKey(Key.Spacebar) }
        toggle.assertIsOn()
        assertEquals(listOf(true, false, true), calls)
    }

    @Test fun karaokeReportsRangeAndSupportsProgressStepsOffAndKeyboard() {
        val level = mutableStateOf(0f)
        compose.setContent { Theme {
            KaraokeDial(level.value, { level.value = it }, Modifier.testTag("karaoke"))
        } }
        val dial = compose.onNodeWithTag("karaoke")
        dial.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(.6f)) }
        compose.runOnIdle { assertEquals(.6f, level.value, .001f) }
        assertEquals(.6f, dial.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, .001f)
        val more = context.getString(R.string.wf_i13_karaoke_more)
        val off = context.getString(R.string.wf_i13_karaoke_off)
        val increase = dial.fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == more }
        compose.runOnIdle { assertTrue(increase.action()) }
        compose.runOnIdle { assertEquals(.7f, level.value, .001f) }
        val disable = dial.fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == off }
        compose.runOnIdle {
            assertTrue(disable.action())
            input.requestInputMode(InputMode.Keyboard)
        }
        compose.runOnIdle { assertEquals(0f, level.value, .001f) }
        dial.performSemanticsAction(SemanticsActions.RequestFocus)
        dial.performKeyInput { pressKey(Key.DirectionUp) }
        compose.runOnIdle { assertEquals(.1f, level.value, .001f) }
        dial.performKeyInput { pressKey(Key.MoveEnd) }
        compose.runOnIdle { assertEquals(1f, level.value, .001f) }
        dial.performSemanticsAction(SemanticsActions.SetProgress) { assertFalse(it(Float.NaN)) }
    }

    @Test fun queueActionsMoveTheSelectedOccurrenceAndRespectCurrentRowAndBounds() {
        val queue = mutableStateOf(listOf(
            QueueEntry(0, "current", "Current", "Artist", null, false, true),
            QueueEntry(1, "duplicate", "First copy", "Artist", null, false, false),
            QueueEntry(2, "duplicate", "Second copy", "Artist", null, true, false)))
        val moves = mutableListOf<Pair<Int, Int>>()
        val scroll = androidx.compose.foundation.lazy.LazyListState()
        compose.setContent { Theme {
            QueueList(queue.value, {}, {}, listState = scroll, onMove = { from, to ->
                moves += from to to
                val changed = queue.value.toMutableList()
                changed.add(to, changed.removeAt(from))
                queue.value = changed.mapIndexed { index, entry -> entry.copy(index = index) }
            })
        } }
        compose.runOnIdle {
            assertEquals(0, scroll.firstVisibleItemIndex)
            assertEquals(3, scroll.layoutInfo.totalItemsCount)
        }
        fun row(title: String) = compose.onNode(hasText(title) and hasClickAction())
        fun move(title: String, label: Int) {
            val action = row(title).fetchSemanticsNode().config[SemanticsActions.CustomActions]
                .single { it.label == context.getString(label) }
            compose.runOnIdle { assertTrue(action.action()) }
        }
        assertTrue(row("Current").fetchSemanticsNode().config[SemanticsActions.CustomActions].isEmpty())
        assertFalse(row("Second copy").fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .any { it.label == context.getString(R.string.wf_i13_move_down) })
        move("Second copy", R.string.wf_i13_move_up)
        compose.runOnIdle { assertEquals(listOf("Current", "Second copy", "First copy"), queue.value.map { it.title }) }
        move("Second copy", R.string.wf_i13_move_down)
        compose.runOnIdle { assertEquals(listOf(2 to 1, 1 to 2), moves) }
        assertEquals(context.getString(R.string.wf_i13_queue_position, 3, 3),
            row("Second copy").fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        assertFalse(i13MoveQueueBy(queue.value, 0, 1) { _, _ -> fail("current row") })
        assertFalse(i13MoveQueueBy(queue.value, 2, 1) { _, _ -> fail("past end") })
    }

    @Test fun failedTranslationIsOffWithAnOperableRetryInTheRealLyricsStage() {
        var calls = 0
        val provider: suspend (List<String>, String) -> List<String>? = { _, _ ->
            if (++calls == 1) null else listOf("Tradotta")
        }
        compose.setContent { Theme {
            LyricsStage(Lyrics(listOf(LyricLine(0L, "Original")), true), false,
                remember { mutableStateOf(0L) }, false, {}, 0, translate = provider)
        } }
        val toggle = compose.onNodeWithContentDescription(context.getString(R.string.translation))
        toggle.performClick()
        compose.onNodeWithText(context.getString(R.string.wf_i13_translation_failed)).assertExists()
        toggle.assertIsOff()
        compose.onNodeWithText(context.getString(R.string.wf_i13_retry_translation)).performClick()
        toggle.assertIsOn()
        compose.onNodeWithText("Tradotta").assertExists()
        compose.onNodeWithText(context.getString(R.string.wf_i13_translation_failed)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, calls) }
    }
}
