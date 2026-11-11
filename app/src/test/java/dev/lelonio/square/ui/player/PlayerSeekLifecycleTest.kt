package dev.lelonio.square.ui.player

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.antigravity.fluidengine.ui.fluid.*
import dev.lelonio.square.R
import dev.lelonio.square.ui.MainViewModel
import dev.lelonio.square.ui.theme.SquareTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w1400dp-h900dp-mdpi")
class PlayerSeekLifecycleTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var inputMode: InputModeManager
    private fun seek() = compose.onNodeWithContentDescription(context.getString(R.string.player_seek_position))
    private fun focusSeek() {
        compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
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

    @Test fun realPausedMedia3PlayerPublishesPositionsThroughRememberPosition() {
        lateinit var player: ExoPlayer
        var observed: State<Long>? = null
        val calls = mutableListOf<Long>()
        compose.runOnIdle {
            player = ExoPlayer.Builder(context).build()
            player.setMediaItem(MediaItem.fromUri("file:///host-review-unprepared-audio.mp3"))
            player.seekTo(25_000L)
        }
        try {
            compose.setContent {
                Theme {
                    val position = rememberPositionMs(player, isPlaying = false)
                    observed = position
                    NowPlayingPanel(
                        state = PlaybackState(hasItem = true, mediaId = "local-track", title = "Local", durationMs = 100_000L),
                        positionMs = position, playingOn = null, reach = remember { mutableStateOf(1f) },
                        geometry = PanelGeometry.of(LocalDensity.current, 1400.dp, 780f, null),
                        modifier = Modifier.height(780.dp),
                        onSeek = { calls.add(it); player.seekTo(it) },
                        onOpen = {}, onTogglePlay = {}, onNext = {}, onPrevious = {})
                }
            }
            focusSeek()
            seek().assertIsFocused()
            compose.runOnIdle {
                println("CRITIC_REAL_INITIAL actual=${player.currentPosition}; observed=${observed!!.value}")
                assertEquals(25_000L, observed!!.value)
            }
            seek().performKeyInput { pressKey(Key.DirectionRight) }
            compose.waitUntil(5_000) { compose.runOnIdle { observed!!.value == 35_000L } }
            // Let the optimistic hold expire on the real dispatcher before the next input.
            val afterHold = System.nanoTime() + 3_200_000_000L
            compose.waitUntil(5_000) { System.nanoTime() >= afterHold }
            seek().performKeyInput { pressKey(Key.DirectionRight) }
            compose.waitUntil(5_000) { compose.runOnIdle { observed!!.value == 45_000L } }
            val forward = seek().fetchSemanticsNode().config[SemanticsActions.CustomActions].last()
            compose.runOnIdle { assertTrue(forward.action()) }
            compose.waitUntil(5_000) { compose.runOnIdle { observed!!.value == 55_000L } }
            assertEquals(listOf(35_000L, 45_000L, 55_000L), calls)
            println("CRITIC_REAL_PAUSED_EXOPLAYER=$calls; observed=${observed!!.value}")
        } finally {
            compose.runOnIdle {
                println("CRITIC_REAL_FINAL calls=$calls; actual=${player.currentPosition}; observed=${observed?.value}")
                player.release()
            }
        }
    }

    @Test fun olderAcknowledgementCannotDiscardNewRelativeTarget() {
        val position = mutableStateOf(25_000L)
        val panelPosition = mutableStateOf(25_000L)
        val calls = mutableListOf<Long>()
        val panelCalls = mutableListOf<Long>()
        compose.setContent { Theme {
            Column {
                GlassProgressBar(panelPosition, 100_000L, { panelCalls.add(it) },
                    androidx.compose.ui.graphics.Color.Magenta, androidx.compose.ui.graphics.Color.Gray,
                    Modifier.testTag("panel-ack"))
                PlaybackScrubber(position, 100_000L, { calls.add(it) }, Modifier.testTag("main-ack"))
            }
        } }
        compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        val panel = compose.onNodeWithTag("panel-ack")
        panel.performSemanticsAction(SemanticsActions.RequestFocus)
        panel.performKeyInput { pressKey(Key.DirectionRight); pressKey(Key.DirectionRight) }
        compose.runOnIdle { panelPosition.value = 35_000L }
        panel.performKeyInput { pressKey(Key.DirectionRight) }
        assertEquals(listOf(35_000L, 45_000L, 55_000L), panelCalls)
        println("CRITIC_OLDER_ACK_PANEL=$panelCalls")
        val main = compose.onNodeWithTag("main-ack")
        main.performSemanticsAction(SemanticsActions.RequestFocus)
        main.performKeyInput { pressKey(Key.DirectionRight); pressKey(Key.DirectionRight) }
        compose.runOnIdle { position.value = 35_000L }
        main.performKeyInput { pressKey(Key.DirectionRight) }
        println("CRITIC_OLDER_ACK_MAIN=$calls")
        assertEquals(listOf(35_000L, 45_000L, 55_000L), calls)
    }

    @Test fun hiddenWidePanelProbeForQueueKeyboardFocus() {
        val hidden = mutableStateOf(false)
        val reach = mutableStateOf(2f)
        val calls = mutableListOf<Int>()
        val queue = listOf(QueueEntry(0, "queue-1", "Queue target", "Artist", null, false, false))
        compose.setContent { Theme {
            val position = remember { mutableStateOf(25_000L) }
            Column(Modifier.requiredSize(1400.dp, 900.dp).testTag("fixture")) {
                Button(onClick = {}, modifier = Modifier.testTag("before-wide-panel")) { Text("Before") }
                Box(Modifier.height(780.dp).graphicsLayer { alpha = if (hidden.value) 0f else 1f }
                    .then(if (hidden.value) Modifier.clearAndSetSemantics {} else Modifier)) {
                    NowPlayingPanel(
                        state = PlaybackState(hasItem = true, mediaId = "track", title = "Panel", durationMs = 100_000L),
                        positionMs = position, playingOn = null,
                        reach = reach, interactive = !hidden.value,
                        geometry = PanelGeometry.of(LocalDensity.current, 1400.dp, 780f, null),
                        extension = {
                            PanelExtension(null, false, position, false, {}, queue, { calls.add(it) }, {}, { _, _ -> },
                                interactive = !hidden.value && reach.value >= 2f)
                        },
                        onOpen = {}, onTogglePlay = {}, onNext = {}, onPrevious = {})
                }
                Button(onClick = {}, modifier = Modifier.testTag("after-wide-panel")) { Text("After") }
            }
        } }
        compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        val row = compose.onNode(hasText("Queue target") and hasClickAction())
        row.performSemanticsAction(SemanticsActions.RequestFocus)
        row.assertIsFocused()
        compose.onNodeWithTag("before-wide-panel").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.runOnIdle { hidden.value = true }
        row.assertDoesNotExist()
        compose.onNodeWithTag("fixture").performKeyInput { pressKey(Key.Tab); pressKey(Key.Enter) }
        println("CRITIC_HIDDEN_QUEUE_CALLS=$calls")
        // A diagnostic probe: a newly traversed Tab must skip the panel after it disappears.
        assertTrue("Hidden wide panel still invokes a queue action: $calls", calls.isEmpty())
        compose.onNodeWithTag("after-wide-panel").assertIsFocused()
        compose.runOnIdle { hidden.value = false }
        row.performSemanticsAction(SemanticsActions.RequestFocus)
        row.performKeyInput { pressKey(Key.Enter) }
        assertEquals(listOf(0), calls)
        calls.clear()
        for (amount in listOf(1f, 1.5f, 0f)) {
            compose.onNodeWithTag("before-wide-panel").performSemanticsAction(SemanticsActions.RequestFocus)
            compose.runOnIdle { reach.value = amount }
            row.assertDoesNotExist()
            compose.onNodeWithTag("fixture").performKeyInput { repeat(10) { pressKey(Key.Tab); pressKey(Key.Enter) } }
            assertTrue("Folded queue received keyboard action at reach=$amount: $calls", calls.isEmpty())
            compose.runOnIdle { reach.value = 2f }
            row.assertExists()
        }
    }

    @Test fun semanticStepsRetainLatestTargetAcrossOlderAcknowledgementAndReverse() {
        val position = mutableStateOf(25_000L)
        val calls = mutableListOf<Long>()
        compose.setContent { Theme { PlaybackScrubber(position, 100_000L, { calls.add(it) }) } }
        fun action(forward: Boolean) {
            val actions = seek().fetchSemanticsNode().config[SemanticsActions.CustomActions]
            compose.runOnIdle { assertTrue(actions[if (forward) 1 else 0].action()) }
        }
        action(true); action(true)
        compose.runOnIdle { position.value = 35_000L }
        action(true)
        compose.runOnIdle { position.value = 45_000L }
        action(false)
        action(false)
        println("CRITIC_SEMANTIC_ACK_REVERSE=$calls")
        assertEquals(listOf(35_000L, 45_000L, 55_000L, 45_000L, 35_000L), calls)
    }

    @Test fun disabledMainScrubberCancelsAnActiveDragWithoutCommit() {
        val position = mutableStateOf(25_000L)
        val duration = mutableStateOf(100_000L)
        val calls = mutableListOf<Long>()
        compose.setContent { Theme { PlaybackScrubber(position, duration.value, { calls.add(it) }, Modifier.width(320.dp).testTag("main")) } }
        // Start from the naked track so this is a real drag, independent of thumb geometry.
        val main = compose.onNodeWithTag("main")
        main.performTouchInput { down(Offset(30f, 24f)); moveTo(Offset(180f, 24f)) }
        assertTrue(calls.isEmpty())
        compose.runOnIdle { duration.value = 0L }
        main.performTouchInput { up() }
        println("CRITIC_DISABLE_DURING_MAIN_DRAG=$calls")
        assertTrue("A disabled control must not commit an unfinished drag", calls.isEmpty())
    }

    @Test fun realPlayerScreenTrackTransitionMustNotRetainThePreviousTracksRelativeSeek() {
        val position = mutableStateOf(25_000L)
        val playback = mutableStateOf(PlaybackState(hasItem = true, mediaId = "track-A", title = "First", durationMs = 100_000L))
        val calls = mutableListOf<Long>()
        compose.setContent { Theme {
            val ground = rememberGlassBackdrop()
            Box(Modifier.requiredSize(1400.dp, 900.dp)) {
                PlayerScreen(
                    state = playback.value, positionMs = position,
                    onCollapse = {}, onTogglePlay = {}, onNext = {}, onPrevious = {}, onSeek = { calls.add(it) },
                    onToggleShuffle = {}, onCycleRepeat = {}, queue = emptyList(),
                    lyrics = null, lyricsLoading = false, credits = null, creditsLoading = false,
                    onWantCredits = {}, onWantArtist = { _, _ -> }, onPlayQueueItem = {},
                    onRemoveQueueItem = {}, onMoveQueueItem = { _, _ -> }, canvas = null,
                    devices = MainViewModel.DevicesState(), onOpenDevices = {}, onCloseDevices = {},
                    onMorphDrag = {}, onMorphRelease = {}, playerOpen = { true }, ground = ground,
                    artist = null, artistLoading = false, onRefreshDevices = {}, onSelectDevice = {},
                    onSetDeviceVolume = { _, _ -> }, onAddToPlaylist = {},
                    addToPlaylist = MainViewModel.AddToPlaylistState(), onPickPlaylist = {},
                )
            }
        } }
        focusSeek()
        seek().performKeyInput { pressKey(Key.DirectionRight) }
        compose.runOnIdle {
            playback.value = PlaybackState(hasItem = true, mediaId = "track-B", title = "Second", durationMs = 80_000L)
            position.value = 5_000L
        }
        seek().performKeyInput { pressKey(Key.DirectionRight) }
        println("CRITIC_MAIN_TRACK_TRANSITION=$calls")
        assertEquals(listOf(35_000L, 15_000L), calls)
    }

    @Test fun queueTakingTheExtensionCannotLeaveKeyboardAuthorityOnAnInvisibleVerse() {
        val calls = mutableListOf<Long>()
        val queue = (0..29).map { QueueEntry(it, "queue-$it", "Queue $it", "Artist", null, false, false) }
        val lyrics = dev.lelonio.square.data.Lyrics(
            (0..5).map { dev.lelonio.square.data.LyricLine((it + 1) * 10_000L, "Visible verse $it") }, synced = true)
        compose.setContent { Theme {
            Column(Modifier.requiredSize(600.dp, 850.dp).testTag("extension-fixture")) {
                Button(onClick = {}, modifier = Modifier.testTag("extension-before")) { Text("Before extension") }
                Box(Modifier.weight(1f)) {
                    // Intro before the first line: no auto-follow is needed for this focus probe.
                    PanelExtension(lyrics, false, remember { mutableStateOf(0L) }, false,
                        { calls.add(it) }, queue, {}, {}, { _, _ -> })
                }
                Button(onClick = {}, modifier = Modifier.testTag("extension-after")) { Text("After extension") }
            }
        } }
        compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        val verse = compose.onNode(hasText("Visible verse 0") and hasClickAction())
        verse.performSemanticsAction(SemanticsActions.RequestFocus)
        verse.assertIsFocused()
        compose.onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText("Queue 0"))).performScrollToIndex(8)
        compose.mainClock.advanceTimeBy(1500)
        verse.assertIsNotDisplayed()
        compose.onNodeWithTag("extension-fixture").performKeyInput { pressKey(Key.Enter) }
        println("CRITIC_HIDDEN_VERSE_AFTER_QUEUE_SCROLL=$calls")
        assertTrue("A verse folded out while the queue takes its room must not retain keyboard authority", calls.isEmpty())
    }

    @Test fun realWidePanelCanMountWithActiveSynchronizedLyrics() {
        val queue = (0..29).map { QueueEntry(it, "queue-$it", "Queue $it", "Artist", null, false, false) }
        val lyrics = dev.lelonio.square.data.Lyrics(
            (0..5).map { dev.lelonio.square.data.LyricLine(it * 10_000L, "Active lyric $it") }, synced = true)
        compose.setContent { Theme {
            val position = remember { mutableStateOf(25_000L) }
            Box(Modifier.requiredSize(1400.dp, 780.dp)) {
                NowPlayingPanel(
                    state = PlaybackState(hasItem = true, mediaId = "track", title = "Panel", durationMs = 100_000L),
                    positionMs = position, playingOn = null,
                    reach = remember { mutableStateOf(2f) },
                    geometry = PanelGeometry.of(LocalDensity.current, 1400.dp, 780f, null),
                    extension = { PanelExtension(lyrics, false, position, false, {}, queue, {}, {}, { _, _ -> }, interactive = true) },
                    onOpen = {}, onTogglePlay = {}, onNext = {}, onPrevious = {},
                )
            }
        } }
        compose.onNodeWithText("Active lyric 2").assertIsDisplayed()
    }

    @Test fun sameDurationReplacementResetsPendingSeekAndRetainsKeyboardFocus() {
        val position = mutableStateOf(25_000L)
        val identity = mutableStateOf("A")
        val calls = mutableListOf<Long>()
        compose.setContent { Theme {
            PlaybackScrubber(position, 100_000L, { calls.add(it) }, resetKey = identity.value)
        } }
        focusSeek()
        seek().performKeyInput { pressKey(Key.DirectionRight) }
        compose.runOnIdle { identity.value = "B"; position.value = 5_000L }
        seek().assertIsFocused()
        seek().performKeyInput { pressKey(Key.DirectionRight) }
        assertEquals(listOf(35_000L, 15_000L), calls)
    }

    @Test fun replacementDoesNotStealFocusFromAnotherControl() {
        val position = mutableStateOf(25_000L)
        val identity = mutableStateOf("A")
        val calls = mutableListOf<Long>()
        compose.setContent { Theme {
            Column {
                PlaybackScrubber(position, 100_000L, { calls.add(it) }, resetKey = identity.value)
                Button(onClick = {}, modifier = Modifier.testTag("outside-seek")) { Text("Outside") }
            }
        } }
        focusSeek()
        seek().performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithTag("outside-seek").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.runOnIdle { identity.value = "B"; position.value = 5_000L }
        compose.onNodeWithTag("outside-seek").assertIsFocused()
        assertEquals(listOf(35_000L), calls)
        focusSeek()
        seek().performKeyInput { pressKey(Key.DirectionRight) }
        assertEquals(listOf(35_000L, 15_000L), calls)
    }

    @Test fun replacementDuringDragCannotCommitToTheNewSource() {
        val position = mutableStateOf(25_000L)
        val identity = mutableStateOf("A")
        val calls = mutableListOf<Long>()
        compose.setContent { Theme {
            PlaybackScrubber(position, 100_000L, { calls.add(it) },
                Modifier.width(320.dp), resetKey = identity.value)
        } }
        seek().performTouchInput { down(Offset(30f, 24f)); moveTo(Offset(180f, 24f)) }
        assertTrue(calls.isEmpty())
        compose.runOnIdle { identity.value = "B"; position.value = 5_000L }
        seek().performTouchInput { up() }
        assertTrue("The old pointer cannot seek the replacement source", calls.isEmpty())
        focusSeek()
        seek().performKeyInput { pressKey(Key.DirectionRight) }
        assertEquals(listOf(15_000L), calls)
    }
}
