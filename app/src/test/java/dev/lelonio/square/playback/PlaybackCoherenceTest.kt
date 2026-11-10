@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.lelonio.square.playback

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import dev.antigravity.fluidengine.ui.fluid.*
import dev.lelonio.square.R
import dev.lelonio.square.data.*
import dev.lelonio.square.ui.MainViewModel
import dev.lelonio.square.ui.library.PlaylistPlaybackActions
import dev.lelonio.square.ui.library.PlaylistScreen
import dev.lelonio.square.ui.player.QueueList
import dev.lelonio.square.ui.theme.SquareTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real Compose screens, store and Spotify producer; only JNI/output and service swap are hosted locally. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = PlaybackCoherenceTest.Host::class, qualifiers = "w400dp-h900dp-mdpi")
class PlaybackCoherenceTest {
    class Host : Application(), CoreHost { override val qualityIsAutomatic = false }
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val players = mutableListOf<Player>()
    private val tracks = (0 until 500).map {
        CatalogTrack("spotify:track:$it", "Song $it", "Artist", durationMs = 180_000)
    }
    private data class Producer(val player: LibrespotPlayer, val queue: PlayQueue, val loads: MutableList<Pair<Int, Int>>, val active: MutableList<Boolean>)

    private fun producer(items: List<CatalogTrack> = tracks.take(5), index: Int = 1): Producer {
        val q = PlayQueue()
        val loads = mutableListOf<Pair<Int, Int>>()
        val active = mutableListOf<Boolean>()
        val p = LibrespotPlayer(context, Looper.getMainLooper(), q,
            onSpeedAndPitch = { _, _ -> }, fadeOutThen = { it() }, fadeIn = {},
            onPlaybackActive = { active += it }, onDuckReverb = {},
            loadQueueCommand = { _, i, _, pos, _, _, _ -> loads += i to pos },
            refreshRemoteState = {}, airPodsPauseCommand = {}, airPodsPlayCommand = {})
        p.shutdownEngineOnRelease = false
        players += p
        restore(p, SavedPlayback(items, index = index, positionMs = 12_345))
        return Producer(p, q, loads, active)
    }

    private fun restore(p: LibrespotPlayer, state: SavedPlayback) {
        p.restore(state.tracks.map { PlayQueue.Track(it.uri, it.name, it.artist,
            durationMs = it.durationMs, artworkUri = null) }, state.shuffleOrder, state.index,
            state.positionMs, state.repeatMode, state.contextUri, state.contextOrdered, state.contextLabel)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After fun release() {
        VideoPlaybackQueue.clear()
        players.asReversed().forEach { it.release() }
        shadowOf(Looper.getMainLooper()).idle()
        PlaybackStore(context).clear()
    }

    private fun playlist(p: Producer, current: Boolean) {
        val actions = PlaylistPlaybackActions(current,
            start = { items, index, asContext ->
                restore(p.player, SavedPlayback(items, index = index, positionMs = 0,
                    contextUri = "spotify:playlist:A", contextOrdered = asContext))
            },
            togglePlayback = { if (p.player.playWhenReady) p.player.pause() else p.player.play() })
        compose.setContent {
            SquareTheme(darkTheme = false, amoled = false, systemBars = false) {
                CompositionLocalProvider(LocalGlassBackdrop provides rememberGlassBackdrop(),
                    LocalFluidGlassModalHostState provides rememberFluidGlassModalHostState(),
                    LocalFluidMotionPolicy provides FluidMotionPolicy(0f)) {
                    PlaylistScreen(state = MainViewModel.PlaylistState(uri = "spotify:playlist:A", name = "A",
                        tracks = tracks.take(5), artworkUrl = "android.resource://android/${android.R.drawable.ic_media_play}"),
                        contentPadding = PaddingValues(0.dp), nowPlayingUri = tracks[1].uri,
                        onBack = {}, onPlay = actions::select, onPlayPrimary = actions::primary,
                        onEnqueue = {}, shuffleOn = false, onToggleShuffle = {}, playingThis = current,
                        onAddToPlaylist = {}, onTrackMenu = {})
                }
            }
        }
    }

    @Test fun firstPlaylistRowChoosesThatRowEvenInThePlayingContext() {
        val p = producer()
        playlist(p, current = true)
        compose.onNodeWithText("Song 0").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("row zero must select its track", tracks[0].uri, p.player.currentMediaItem?.mediaId)
            assertEquals(0, p.queue.currentIndex)
            assertEquals(0L, p.player.currentPosition)
        }
    }

    @Test fun primaryControlPausesAndResumesWithoutReplacingTheQueue() {
        val p = producer()
        p.player.play()
        p.player.onEvent("playing", tracks[1].uri, 12_345)
        shadowOf(Looper.getMainLooper()).idle()
        playlist(p, current = true)
        p.active.clear()
        compose.onAllNodesWithText(context.getString(R.string.pause)).onFirst().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle {
            assertEquals(false, p.active.last())
            p.player.onEvent("paused", tracks[1].uri, 12_345)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(p.player.playWhenReady)
            assertEquals(1, p.queue.currentIndex)
            assertEquals(12_345L, p.player.currentPosition)
        }
        p.active.clear()
        // JNI is replaced, so confirmations above and below enter the real native-event parser.
        compose.onAllNodesWithText(context.getString(R.string.pause)).onFirst().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle {
            assertEquals(true, p.active.last())
            p.player.onEvent("playing", tracks[1].uri, 12_345)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(p.player.playWhenReady); assertEquals(1, p.queue.currentIndex)
        }
    }

    @Test fun primaryControlStartsTheFirstRowForAnotherContext() {
        val p = producer()
        playlist(p, current = false)
        compose.onAllNodesWithText(context.getString(R.string.play)).onFirst().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals(0, p.queue.currentIndex); assertEquals(0L, p.player.currentPosition) }
    }

    private fun roundTrip(index: Int, shuffle: List<Int>? = null) {
        val expected = SavedPlayback(tracks, shuffle, index, 47_321, Player.REPEAT_MODE_ALL,
            "spotify:playlist:long", true, "Long playlist", "owned-occurrence", "history-fence")
        val store = PlaybackStore(context)
        store.save(expected)
        val loaded = PlaybackStore(context).load()!!
        val restored = producer(items = tracks.take(1), index = 0)
        restore(restored.player, loaded)
        val order = shuffle?.map(tracks::get) ?: tracks
        assertEquals("restored current track", order[index].uri, restored.player.currentMediaItem?.mediaId)
        assertEquals(47_321L, restored.player.currentPosition)
        assertEquals("restored continuation", order.drop(index).map { it.uri }, restored.queue.items.drop(index).map { it.uri })
        assertEquals(expected, loaded)
        assertEquals(shuffle, restored.queue.shuffleOrder)
        assertEquals(index to 47_321, restored.loads.last())
        assertFalse(restored.player.playWhenReady)
    }

    @Test fun longUnshuffledQueueRestoresTrackPastTwoHundredAndItsContinuation() = roundTrip(349)
    @Test fun longUnshuffledQueueKeepsTheContinuationEvenNearTheBeginning() = roundTrip(3)
    @Test fun shuffledQueueKeepsItsExactPermutationAndOccurrence() = roundTrip(349, tracks.indices.reversed().toList())

    private fun video(p: Producer): ExoPlayer {
        val video = ExoPlayer.Builder(context).build().also { players += it }
        video.setMediaItem(MediaItem.Builder().setMediaId(tracks[1].uri).setUri("file:///not-opened-video.mp4").build())
        VideoPlaybackQueue.show(VideoQueueOwner(p.player, PlaybackService.mediaAccountEpoch,
            handOver = { snapshot, selected ->
                // Host the service's swap locally; selection must still reach the actual Spotify producer.
                if (snapshot.isCurrent()) {
                    VideoPlaybackQueue.clear()
                    selected(p.player)
                }
            }, changed = {}))
        return video
    }

    @Test fun videoQueueSelectionReachesTheSpotifyProducerFromTheRealQueueRow() {
        val p = producer()
        val video = video(p)
        compose.setContent {
            SquareTheme(darkTheme = false, amoled = false, systemBars = false) {
                CompositionLocalProvider(LocalGlassBackdrop provides rememberGlassBackdrop(),
                    LocalFluidMotionPolicy provides FluidMotionPolicy(0f)) {
                    val displayed = rememberDisplayedPlaybackQueue(video)
                    QueueList(displayed.entries, displayed::select, displayed::remove, displayed::move)
                }
            }
        }
        compose.onNodeWithText("Song 3").performClick()
        compose.runOnIdle {
            assertEquals(3, p.queue.currentIndex)
            assertEquals(tracks[3].uri, p.player.currentMediaItem?.mediaId)
            assertEquals(1, video.mediaItemCount)
        }
    }

    @Test fun videoQueueRemovalChangesSpotifyAndRefreshesTheDisplayedRows() {
        val p = producer()
        val video = video(p)
        lateinit var displayed: DisplayedPlaybackQueue
        compose.setContent { displayed = rememberDisplayedPlaybackQueue(video) }
        compose.runOnIdle { displayed.remove(3) }
        compose.runOnIdle {
            assertEquals(listOf(0, 1, 2, 4).map { tracks[it].uri }, p.queue.items.map { it.uri })
            assertEquals(listOf(1, 2, 4).map { tracks[it].uri }, displayed.entries.map { it.uri })
            assertEquals(1, video.mediaItemCount)
        }
    }

    @Test fun videoQueueMoveUsesAbsoluteIndicesAndKeepsDuplicateOccurrences() {
        val items = listOf(tracks[0], tracks[1], tracks[2], tracks[2].copy(name = "Second occurrence"), tracks[4])
        val p = producer(items)
        val video = video(p)
        val displayed = DisplayedPlaybackQueue(video)
        displayed.move(3, 2)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("Song 0", "Song 1", "Second occurrence", "Song 2", "Song 4"), p.queue.items.map { it.title })
        assertEquals(1, p.queue.currentIndex)
        assertEquals(1, video.mediaItemCount)
        displayed.remove(2) // A callback retained from before the move must not remove the new occupant.
        assertEquals(5, p.queue.items.size)
    }

    @Test fun videoQueueRejectsCallbacksFromAnOldAccountOrOwner() {
        val p = producer()
        val video = video(p)
        var epoch = 7L
        val binding = VideoQueueOwner(p.player, epoch, { _, _ -> fail("stale selection") }, {})
        VideoPlaybackQueue.show(binding)
        val displayed = DisplayedPlaybackQueue(video, binding, epoch, { epoch })
        epoch++
        displayed.remove(2); displayed.move(2, 3); displayed.select(3)
        assertEquals(5, p.queue.items.size)
        val current = DisplayedPlaybackQueue(video, binding, 7L, { 7L })
        VideoPlaybackQueue.clear()
        current.remove(2); current.move(2, 3); current.select(3)
        assertEquals(5, p.queue.items.size)
    }

    @Test fun consecutiveDragCrossingsUseTheUpdatedProductionQueueConnection() {
        val p = producer()
        val video = video(p)
        val dragStates = mutableListOf<Boolean>()
        compose.setContent {
            SquareTheme(darkTheme = false, amoled = false, systemBars = false) {
                CompositionLocalProvider(LocalGlassBackdrop provides rememberGlassBackdrop(),
                    LocalFluidMotionPolicy provides FluidMotionPolicy(0f)) {
                    val displayed = rememberDisplayedPlaybackQueue(video)
                    QueueList(displayed.entries, displayed::select, displayed::remove, displayed::move,
                        onReorderingChange = { dragStates += it })
                }
            }
        }
        // The clickable row merges its children; target the actual grip icon, not that row.
        val grips = compose.onAllNodesWithContentDescription(context.getString(R.string.reorder), useUnmergedTree = true)
        val row = grips[1].fetchSemanticsNode().boundsInRoot.center.y - grips[0].fetchSemanticsNode().boundsInRoot.center.y
        val start = grips[0].fetchSemanticsNode().boundsInRoot.center
        assertTrue("queue row height: $row", row > 0f)
        compose.onRoot().performTouchInput { down(start); moveBy(Offset(0f, row * 1.3f), delayMillis = 32) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("drag states=$dragStates row=$row start=$start root=${compose.onRoot().fetchSemanticsNode().boundsInRoot}", dragStates.contains(true))
            assertEquals("first drag crossing", tracks[3].uri, p.queue.items[2].uri)
        }
        compose.onRoot().performTouchInput { moveBy(Offset(0f, row)) }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(listOf(0, 1, 3, 4, 2).map { tracks[it].uri }, p.queue.items.map { it.uri })
            assertEquals(1, video.mediaItemCount)
        }
    }

    @Test fun ordinaryQueueRetainsSelectionRemovalAndMoveWithoutVideo() {
        val p = producer()
        DisplayedPlaybackQueue(p.player).move(4, 2)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(0, 1, 4, 2, 3).map { tracks[it].uri }, p.queue.items.map { it.uri })
        DisplayedPlaybackQueue(p.player).remove(3)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(0, 1, 4, 3).map { tracks[it].uri }, p.queue.items.map { it.uri })
        DisplayedPlaybackQueue(p.player).select(3)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(tracks[3].uri, p.player.currentMediaItem?.mediaId)
        assertEquals(3, p.queue.currentIndex)
    }
}
