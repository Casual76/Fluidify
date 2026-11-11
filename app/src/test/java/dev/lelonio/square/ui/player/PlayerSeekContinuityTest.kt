package dev.lelonio.square.ui.player

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.media3.common.Player
import androidx.media3.common.FlagSet
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.antigravity.fluidengine.ui.fluid.*
import dev.lelonio.square.R
import dev.lelonio.square.ui.theme.SquareTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.reflect.Proxy

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w1000dp-h900dp-mdpi")
class PlayerSeekContinuityTest {
    @get:Rule val compose = createComposeRule()
    private val position = mutableStateOf(25_000L)
    private val duration = mutableStateOf(100_000L)
    private val reach = mutableStateOf(1f)
    private val hidden = mutableStateOf(false)
    private val interactive = mutableStateOf(true)
    private val trackKey = mutableStateOf("track-A")
    private val calls = mutableListOf<Long>()
    private lateinit var inputMode: InputModeManager
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val out = java.io.File("build/reports/player-seek").apply { mkdirs() }.path + java.io.File.separator
    private fun seek() = compose.onNodeWithContentDescription(context.getString(R.string.player_seek_position))
    private fun keyboard() = compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
    private fun focusSeek() {
        keyboard()
        seek().performSemanticsAction(SemanticsActions.RequestFocus)
    }

    @Composable private fun Theme(content: @Composable () -> Unit) {
        inputMode = LocalInputModeManager.current
        SquareTheme(darkTheme = false, amoled = false, systemBars = false) {
            val backdrop = rememberGlassBackdrop()
            CompositionLocalProvider(LocalGlassBackdrop provides backdrop,
                LocalFluidMotionPolicy provides FluidMotionPolicy(0f), content = content)
        }
    }

    private fun mountPanel() {
        compose.setContent {
            Theme {
                Column(Modifier.requiredSize(1000.dp, 900.dp).testTag("fixture")) {
                    Button(onClick = {}, modifier = Modifier.testTag("before")) { Text("Before panel") }
                    Box(Modifier.height(780.dp).graphicsLayer { alpha = if (hidden.value) 0f else 1f }
                        .then(if (hidden.value) Modifier.clearAndSetSemantics {} else Modifier)) {
                        NowPlayingPanel(
                            state = PlaybackState(hasItem = true, mediaId = trackKey.value, title = "A real panel", artist = "Artist",
                                durationMs = duration.value, hasNext = true, hasPrevious = true),
                            positionMs = position, playingOn = null,
                            reach = reach, interactive = interactive.value && !hidden.value,
                            geometry = PanelGeometry.of(LocalDensity.current, 1000.dp, 780f, null),
                            onOpen = {}, onTogglePlay = {}, onNext = {}, onPrevious = {},
                            onSeek = { calls.add(it) },
                        )
                    }
                    Button(onClick = {}, modifier = Modifier.testTag("after")) { Text("After panel") }
                }
            }
        }
    }

    private fun mountBare() {
        compose.setContent {
            Theme {
                GlassProgressBar(position, duration.value, { calls.add(it) }, Color.Magenta, Color.Gray,
                    Modifier.width(320.dp).testTag("bare"), amount = { reach.value }, interactive = interactive.value,
                    resetKey = trackKey.value)
            }
        }
    }

    @Test fun panelTargetPointerSemanticFocusAndFolding() {
        mountPanel()
        seek().assertHeightIsAtLeast(48.dp)
        seek().performTouchInput { click(Offset(width * .6f, centerY)) }
        assertEquals(1, calls.size)
        assertTrue(calls.single() in 59_000L..61_000L)
        seek().performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(80_000f)) }
        assertEquals(80_000L, calls.last())
        focusSeek()
        seek().assertIsFocused()
        seek().performKeyInput { pressKey(Key.DirectionDown) }
        println("PANEL_DOWN_KEY_FOCUSED=${seek().fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused)}")
        seek().performKeyInput { pressKey(Key.Tab) }
        seek().assertIsNotFocused()
        compose.runOnIdle { reach.value = 0f; calls.clear() }
        seek().assertDoesNotExist()
        compose.runOnIdle { reach.value = .8f }
        seek().assertDoesNotExist()
        compose.runOnIdle { reach.value = 1f }
        seek().assertExists()
        focusSeek()
        compose.runOnIdle { duration.value = 0L }
        assertNull(seek().fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused))
        assertNull(seek().fetchSemanticsNode().config.getOrNull(SemanticsActions.SetProgress))
        compose.runOnIdle { duration.value = 100_000L; interactive.value = false }
        seek().assertDoesNotExist()
    }

    @Test fun panelRepeatedKeysAccumulateBeforeRemoteAcknowledges() {
        mountPanel()
        focusSeek()
        seek().performKeyInput { repeat(3) { pressKey(Key.DirectionRight) } }
        println("REPEATED_PANEL_KEYS=$calls; incomingPosition=${position.value}")
        assertEquals(listOf(35_000L, 45_000L, 55_000L), calls)
    }

    @Test fun mainRepeatedKeysAccumulateBeforeRemoteAcknowledges() {
        compose.setContent { Theme { PlaybackScrubber(position, duration.value, { calls.add(it) }) } }
        focusSeek()
        seek().performKeyInput { repeat(3) { pressKey(Key.DirectionRight) } }
        println("REPEATED_MAIN_KEYS=$calls; incomingPosition=${position.value}")
        assertEquals(listOf(35_000L, 45_000L, 55_000L), calls)
    }

    @Test fun mainOlderAcknowledgementDoesNotDiscardLatestRelativeTarget() {
        compose.setContent { Theme { PlaybackScrubber(position, duration.value, { calls.add(it) }) } }
        focusSeek()
        seek().performKeyInput { pressKey(Key.DirectionRight); pressKey(Key.DirectionRight) }
        compose.runOnIdle { position.value = 35_000L }
        seek().performKeyInput { pressKey(Key.DirectionRight) }
        assertEquals(listOf(35_000L, 45_000L, 55_000L), calls)
    }

    @Test fun panelCustomActionsAccumulateBeforeRemoteAcknowledges() {
        mountPanel()
        repeat(3) {
            val forward = seek().fetchSemanticsNode().config[SemanticsActions.CustomActions].last()
            compose.runOnIdle { assertTrue(forward.action()) }
        }
        println("REPEATED_PANEL_ACTIONS=$calls; incomingPosition=${position.value}")
        assertEquals(listOf(35_000L, 45_000L, 55_000L), calls)
    }

    @Test fun pausedLocalPlayerKeysContinueFromActualSeekEvenAfterWaiting() {
        var actual = 25_000L
        val listeners = mutableListOf<Player.Listener>()
        var shown: State<Long>? = null
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { proxy, method, args ->
            when (method.name) {
                "getCurrentPosition" -> actual
                "seekTo" -> {
                    actual = args!![0] as Long
                    listeners.toList().forEach { it.onEvents(proxy as Player,
                        Player.Events(FlagSet.Builder().add(Player.EVENT_POSITION_DISCONTINUITY).build())) }
                    null
                }
                "addListener" -> { listeners.add(args!![0] as Player.Listener); null }
                "removeListener" -> { listeners.remove(args!![0] as Player.Listener); null }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                "toString" -> "Paused seek test Player"
                else -> error("Unexpected Player call ${method.name}")
            }
        } as Player
        compose.setContent {
            Theme {
                val localPosition = rememberPositionMs(player, isPlaying = false)
                shown = localPosition
                GlassProgressBar(localPosition, 100_000L, { calls.add(it); player.seekTo(it) }, Color.Magenta, Color.Gray)
            }
        }
        focusSeek()
        seek().performKeyInput { pressKey(Key.DirectionRight) }
        compose.mainClock.advanceTimeBy(2000)
        compose.runOnIdle { assertEquals(actual, shown!!.value) }
        seek().performKeyInput { pressKey(Key.DirectionRight) }
        println("PAUSED_LOCAL_KEYS=$calls; actualPlayer=$actual; shownPosition=${shown!!.value}")
        assertEquals(listOf(35_000L, 45_000L), calls)
    }

    @Test fun hiddenPanelLosesFocusBeforeFurtherKeyboardSeeking() {
        mountPanel()
        focusSeek()
        compose.runOnIdle { hidden.value = true }
        seek().assertDoesNotExist()
        compose.onNodeWithTag("fixture").performKeyInput { pressKey(Key.DirectionRight) }
        println("HIDDEN_PANEL_KEYS=$calls")
        assertTrue("A hidden panel must not issue seeks", calls.isEmpty())
        seek().assertDoesNotExist()
    }

    @Test fun panelFocusHasVisibleFeedbackCapture() {
        mountPanel()
        keyboard()
        compose.onNodeWithTag("before").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("fixture").captureRoboImage(out + "panel-before-focus.png")
        focusSeek()
        seek().assertIsFocused()
        compose.onNodeWithTag("fixture").captureRoboImage(out + "panel-focused.png")
        println("PANEL_FOCUSED_BOUNDS=${seek().fetchSemanticsNode().boundsInRoot}")
    }

    @Test fun pointerCancelDurationChangeAndPartialFoldNeverCommit() {
        mountBare()
        seek().performTouchInput { down(Offset(30f, centerY)); moveTo(Offset(150f, centerY)); cancel() }
        assertTrue(calls.isEmpty())
        seek().performTouchInput { down(Offset(30f, centerY)); moveTo(Offset(150f, centerY)) }
        compose.runOnIdle { duration.value = 90_000L }
        seek().performTouchInput { up() }
        assertTrue(calls.isEmpty())
        seek().performTouchInput { down(Offset(30f, centerY)); moveTo(Offset(150f, centerY)) }
        compose.runOnIdle { reach.value = .8f }
        compose.onNodeWithTag("bare").performTouchInput { up() }
        assertTrue(calls.isEmpty())
    }

    @Test fun negativeDurationAndOutOfRangePositionAreSafe() {
        duration.value = -1L
        position.value = Long.MAX_VALUE
        mountBare()
        val range = seek().fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(0f..0f, range.range)
        assertEquals(0f, range.current, 0f)
        seek().performTouchInput { click() }
        assertTrue(calls.isEmpty())
    }

    @Test fun unacknowledgedSeekExpiresInsteadOfRetainingAnInventedPosition() {
        mountBare()
        focusSeek()
        seek().performKeyInput { pressKey(Key.DirectionRight) }
        assertEquals(listOf(35_000L), calls)
        // The coroutine timeout uses the Android dispatcher, not only animation-frame time.
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 5_000) {
            seek().fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current == 25_000f
        }
        seek().performKeyInput { pressKey(Key.DirectionRight) }
        assertEquals(listOf(35_000L, 35_000L), calls)
    }

    @Test fun newTrackWithSameDurationDiscardsPendingPosition() {
        mountBare()
        focusSeek()
        seek().performKeyInput { pressKey(Key.DirectionRight) }
        compose.runOnIdle { trackKey.value = "track-B"; position.value = 5_000L }
        seek().performKeyInput { pressKey(Key.DirectionRight) }
        assertEquals(listOf(35_000L, 15_000L), calls)
    }

    @Test fun disposedLocalPlayerListenerCannotPublishIntoReplacement() {
        class Fake(var actual: Long) {
            val listeners = mutableListOf<Player.Listener>()
            val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { proxy, method, args ->
                when (method.name) {
                    "getCurrentPosition" -> actual
                    "addListener" -> { listeners.add(args!![0] as Player.Listener); null }
                    "removeListener" -> { listeners.remove(args!![0] as Player.Listener); null }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args!![0]
                    "toString" -> "Position listener fixture"
                    else -> error("Unexpected call ${method.name}")
                }
            } as Player
        }
        val first = Fake(25_000L)
        val next = Fake(5_000L)
        val selected = mutableStateOf(first.player)
        var shown: State<Long>? = null
        compose.setContent { shown = rememberPositionMs(selected.value, isPlaying = false) }
        val oldCallback = compose.runOnIdle { first.listeners.single() }
        compose.runOnIdle { selected.value = next.player }
        compose.runOnIdle {
            assertTrue(first.listeners.isEmpty())
            first.actual = 90_000L
            oldCallback.onEvents(first.player, Player.Events(FlagSet.Builder().add(Player.EVENT_POSITION_DISCONTINUITY).build()))
            assertEquals(5_000L, shown!!.value)
        }
    }
}
