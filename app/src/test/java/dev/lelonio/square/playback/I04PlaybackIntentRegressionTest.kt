@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.lelonio.square.playback

import android.app.Application
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import dev.lelonio.square.data.SpotifyNetworkPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Real queue, Media3 adapter and event parser; only JNI and the output fade are controlled. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = I04PlaybackIntentRegressionTest.Host::class)
@LooperMode(LooperMode.Mode.PAUSED)
class I04PlaybackIntentRegressionTest {
    class Host : Application(), CoreHost { override val qualityIsAutomatic = false }

    private val rigs = mutableListOf<Rig>()
    @Before fun offline() { SpotifyNetworkPolicy.setManual(true) }
    @After fun release() {
        rigs.asReversed().forEach { it.player.release() }
        idle()
        SpotifyNetworkPolicy.setManual(false)
        idle()
    }

    @Test fun actualPlayingSnapshotFollowsDuplicateOccurrenceWithoutFollowingPreload() {
        for (uris in listOf(listOf("a", "b", "a", "c"), listOf("a", "a", "b"))) {
            val r = Rig(uris.size)
            val tracks = uris.map { PlayQueue.Track("spotify:track:$it", it, "Artist", durationMs = 60_000, artworkUri = null) }
            r.player.restore(tracks, null, 0, 0, Player.REPEAT_MODE_OFF, null, false, "")
            r.finishFade()
            r.player.play()
            if (r.fades.isNotEmpty()) r.finishFade()
            r.adopted = true
            val target = if (uris.size == 4) 2 else 1
            r.nativeSnapshot = org.json.JSONObject().put("trackUri", tracks[target].uri)
                .put("index", target).put("tracks", org.json.JSONArray(tracks.map { it.uri })).toString()
            r.player.onEvent("loading", tracks[target].uri, 0); idle()
            assertEquals(0, r.queue.currentIndex)
            r.player.onEvent("playing", tracks[target].uri, 0); idle()
            assertEquals(target, r.queue.currentIndex)
            r.player.onEvent("playing", tracks[target].uri, 123); idle()
            assertEquals(target, r.queue.currentIndex)
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private data class Load(val uris: List<String>, val index: Int, val playing: Boolean, val position: Int)

    private inner class Rig(count: Int = 1, repeat: Int = Player.REPEAT_MODE_OFF) {
        val tracks = (0 until count).map {
            PlayQueue.Track("spotify:track:i04$it", "Song $it", "Artist", durationMs = 60_000, artworkUri = null)
        }
        val queue = PlayQueue()
        val fades = mutableListOf<() -> Unit>()
        val loads = mutableListOf<Load>()
        val active = mutableListOf<Boolean>()
        var playCommands = 0
        var pauseCommands = 0
        var fadeIns = 0
        var nativePlaying = false
        var adopted = false
        var nativeSnapshot = "{}"
        val player = LibrespotPlayer(RuntimeEnvironment.getApplication(), Looper.getMainLooper(), queue,
            onSpeedAndPitch = { _, _ -> }, fadeOutThen = { fades += it }, fadeIn = { fadeIns++ },
            onPlaybackActive = { active += it }, onDuckReverb = {},
            loadQueueCommand = { uris, index, playing, position, _, _, _ ->
                loads += Load(uris.toList(), index, playing, position)
                nativePlaying = playing
            },
            refreshRemoteState = {}, airPodsPauseCommand = {}, airPodsPlayCommand = {},
            playCommand = { playCommands++; nativePlaying = true },
            pauseCommand = { pauseCommands++; nativePlaying = false },
            queueIsAdopted = { adopted }, nativeQueueSnapshot = { nativeSnapshot }).apply { shutdownEngineOnRelease = false }

        init {
            rigs += this
            player.restore(tracks, null, 0, 0, repeat, null, false, "")
            finishFade()
            loads.clear()
        }

        fun finishFade() {
            assertTrue("a real load must have requested a fade", fades.isNotEmpty())
            fades.removeAt(0).invoke()
            idle()
        }
        fun emit(type: String, index: Int = queue.currentIndex, position: Long = 0) {
            player.onEvent(type, tracks[index].uri, position)
            idle()
        }
        fun start() {
            player.play()
            emit("playing")
            loads.clear(); active.clear(); fadeIns = 0
        }
        fun select(index: Int) {
            player.setMediaItems(tracks.map { MediaItem.Builder().setMediaId(it.uri).build() }, index, 0)
        }
        private fun controller() = LibrespotPlayer::class.java.getDeclaredField("focus")
            .apply { isAccessible = true }.get(player) as AudioFocusController
        fun holdsFocus() = AudioFocusController::class.java.getDeclaredField("holdsFocus")
            .apply { isAccessible = true }.getBoolean(controller())
        fun focus(change: Int) {
            val controller = controller()
            val request = AudioFocusController::class.java.getDeclaredField("request")
                .apply { isAccessible = true }.get(controller) as AudioFocusRequest
            requireNotNull(org.robolectric.util.ReflectionHelpers.callInstanceMethod<AudioManager.OnAudioFocusChangeListener>(request, "getOnAudioFocusChangeListener")).onAudioFocusChange(change)
            idle()
        }
    }

    private fun assertRepeated(repeatMode: Int) {
        val r = Rig(repeat = repeatMode)
        r.start()
        repeat(2) {
            val before = r.loads.size
            r.emit("end_of_track", position = 60_000)
            assertEquals(Player.STATE_BUFFERING, r.player.playbackState)
            r.finishFade()
            assertEquals(before + 1, r.loads.size)
            assertEquals(Load(listOf(r.tracks.single().uri), 0, true, 0), r.loads.last())
            assertEquals(0, r.queue.currentIndex)
            assertEquals(repeatMode, r.player.repeatMode)
            r.emit("playing")
            assertEquals(Player.STATE_READY, r.player.playbackState)
            assertTrue(r.player.isPlaying)
            assertEquals(0L, r.player.currentPosition)
        }
    }

    @Test fun offlineRepeatOneReloadsTheCompletedRequestAtZeroEveryTime() = assertRepeated(Player.REPEAT_MODE_ONE)
    @Test fun offlineSingleTrackRepeatAllReloadsTheCompletedRequestAtZeroEveryTime() = assertRepeated(Player.REPEAT_MODE_ALL)

    @Test fun offlineRepeatOneInAMultiTrackQueueKeepsTheChosenOccurrence() {
        val r = Rig(count = 3, repeat = Player.REPEAT_MODE_ONE)
        r.start(); r.select(1); r.finishFade(); r.emit("playing", index = 1)
        r.loads.clear()
        r.emit("end_of_track", index = 1, position = 60_000); r.finishFade()
        assertEquals(1, r.loads.single().index)
        assertEquals(0, r.loads.single().position)
        assertTrue(r.loads.single().playing)
        assertEquals(r.tracks[1].uri, r.player.currentMediaItem?.mediaId)
    }

    @Test fun offlineRepeatAllWrapsFromTheLastTrackToTheFirst() {
        val r = Rig(count = 3, repeat = Player.REPEAT_MODE_ALL)
        r.start(); r.select(2); r.finishFade(); r.emit("playing", index = 2)
        r.loads.clear()
        r.emit("end_of_track", index = 2, position = 60_000); r.finishFade()
        assertEquals(Load(r.tracks.map { it.uri }, 0, true, 0), r.loads.single())
        assertEquals(0, r.queue.currentIndex)
    }

    @Test fun offlineEndWithoutRepeatStopsInsteadOfLoadingAgain() {
        val r = Rig()
        r.start(); r.emit("end_of_track", position = 60_000)
        assertTrue(r.loads.isEmpty()); assertTrue(r.fades.isEmpty())
        assertEquals(Player.STATE_ENDED, r.player.playbackState)
        assertFalse(r.player.playWhenReady)
        assertEquals(false, r.active.last())
    }

    @Test fun offlineOrdinaryAdvanceStillLoadsTheNextTrack() {
        val r = Rig(count = 2)
        r.start(); r.emit("end_of_track", position = 60_000); r.finishFade()
        assertEquals(Load(r.tracks.map { it.uri }, 1, true, 0), r.loads.single())
    }

    @Test fun adoptedConnectQueueKeepsItsOwnEndOfTrackAdvance() {
        val r = Rig(count = 2, repeat = Player.REPEAT_MODE_ALL)
        r.start(); r.adopted = true
        r.emit("end_of_track", position = 60_000)
        assertTrue(r.loads.isEmpty()); assertTrue(r.fades.isEmpty())
        assertEquals(0, r.queue.currentIndex)
    }

    @Test fun pauseDuringAnAlreadyDispatchedLoadStillReachesTheNativePlayer() {
        val r = Rig(count = 2)
        r.start(); r.select(1); r.finishFade()
        assertTrue(r.loads.single().playing)
        val pausesBefore = r.pauseCommands
        r.player.pause()
        assertEquals(pausesBefore + 1, r.pauseCommands)
        assertFalse(r.nativePlaying)
        r.emit("paused", index = 1)
        assertFalse(r.player.playWhenReady)
        assertEquals(1, r.queue.currentIndex)
        assertEquals(1, r.loads.size)
    }

    @Test fun pauseDuringFadeWinsBeforeTheEngineAcknowledgesThePause() {
        val r = Rig(count = 2)
        r.start(); r.select(1)
        r.player.pause() // Do not supply a paused event: the old reported state still says playing.
        assertTrue(r.player.playWhenReady)
        r.finishFade()
        assertEquals(Load(r.tracks.map { it.uri }, 1, false, 0), r.loads.single())
        assertFalse(r.nativePlaying)
        assertEquals(0, r.fadeIns)
    }

    @Test fun pauseBetweenFadeCompletionAndLooperDispatchAlsoWins() {
        val r = Rig(count = 2)
        r.start(); r.select(1)
        r.fades.removeAt(0).invoke() // withDevice posted the load, but it has not run.
        r.player.pause()
        idle()
        assertFalse(r.loads.single().playing)
        assertEquals(1, r.loads.single().index)
        assertFalse(r.nativePlaying)
    }

    @Test fun playAfterSelectingWhilePausedIsReadAtTheEndOfTheFade() {
        val r = Rig(count = 2)
        r.select(1); r.player.play(); r.finishFade()
        assertEquals(Load(r.tracks.map { it.uri }, 1, true, 0), r.loads.single())
        assertTrue(r.nativePlaying)
    }

    @Test fun pauseThenAConfirmedNewPlayDuringFadeUsesTheLatestPlay() {
        val r = Rig(count = 2)
        r.start(); r.select(1); r.player.pause(); r.emit("paused", index = 0)
        r.player.play(); r.finishFade()
        assertTrue(r.loads.single().playing)
        assertEquals(1, r.loads.single().index)
    }

    @Test fun playingEventAlreadyQueuedBeforePauseCannotAuthorizeThePendingLoad() {
        val r = Rig(count = 2)
        r.start(); r.select(1)
        r.player.onEvent("playing", r.tracks[0].uri, 400)
        r.player.pause()
        idle(); r.finishFade()
        assertFalse(r.loads.single().playing)
        assertEquals(1, r.loads.single().index)
    }

    @Test fun connectPauseDuringFadeAlsoKeepsTheSelectedTrackPaused() {
        val r = Rig(count = 2)
        r.start(); r.select(1); r.emit("paused", index = 0)
        r.finishFade()
        assertFalse(r.loads.single().playing)
        assertEquals(1, r.loads.single().index)
    }

    @Test fun transientFocusLossDuringFadeKeepsTheIncomingTrackPausedThenResumesOnce() {
        val r = Rig(count = 2)
        r.start(); r.select(1)
        val playsBefore = r.playCommands
        r.focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        r.emit("paused", index = 0) // Acknowledgement must preserve the focus resume ticket.
        r.finishFade()
        assertFalse(r.loads.single().playing)
        assertFalse(r.nativePlaying)
        r.emit("paused", index = 1) // The newly loaded track is paused as requested too.
        r.focus(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(playsBefore + 1, r.playCommands)
        assertTrue(r.nativePlaying)
        r.focus(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(playsBefore + 1, r.playCommands)
    }

    @Test fun latePlayingWhileFocusIsLostCannotReauthorizeTheFadeLoad() {
        val r = Rig(count = 2)
        r.start(); r.select(1); r.focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        r.emit("playing", index = 0)
        r.finishFade()
        assertFalse(r.loads.single().playing)
        assertFalse(r.nativePlaying)
    }

    @Test fun connectPauseBeforeAnInterruptionCannotResumeAtFocusGain() {
        val r = Rig()
        r.start(); r.emit("paused", position = 12_000)
        val playsBefore = r.playCommands
        r.focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        r.focus(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(playsBefore, r.playCommands)
        assertFalse(r.player.playWhenReady)
        assertEquals(12_000L, r.player.currentPosition)
        assertTrue(r.loads.isEmpty())
    }

    @Test fun connectPauseAlreadyReceivedBeforeTheFocusCallbackCannotCreateAResume() {
        val r = Rig()
        r.start()
        val playsBefore = r.playCommands
        r.player.onEvent("paused", r.tracks[0].uri, 12_000) // Received, not yet applied on the looper.
        r.focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        r.focus(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(playsBefore, r.playCommands)
        assertFalse(r.player.playWhenReady)
        assertEquals(12_000L, r.player.currentPosition)
    }

    @Test fun laterConnectPlayReacquiresFocusReleasedByItsEarlierPause() {
        val r = Rig()
        r.start(); assertTrue(r.holdsFocus())
        r.emit("paused"); assertFalse(r.holdsFocus())
        r.emit("playing")
        assertTrue(r.holdsFocus())
        assertTrue(r.player.playWhenReady)
        assertTrue(r.loads.isEmpty())
    }

    @Test fun userPauseBeforeItsNativeAcknowledgementPreventsFocusResume() {
        val r = Rig()
        r.start(); r.player.pause()
        val playsBefore = r.playCommands
        assertTrue(r.player.playWhenReady)
        r.focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        r.focus(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(playsBefore, r.playCommands)
        assertFalse(r.nativePlaying)
    }

    @Test fun stopAndClearRevokeDeferredLoadsInsteadOfRecreatingPlayback() {
        for (clear in listOf(false, true)) {
            val r = Rig(count = 2)
            r.start(); r.select(1)
            if (clear) r.player.clearForRestart() else r.player.stop()
            r.finishFade()
            assertTrue(r.loads.isEmpty())
            assertEquals(Player.STATE_IDLE, r.player.playbackState)
        }
    }

    @Test fun newerQueueSelectionRevokesTheEarlierFadeCallback() {
        val r = Rig(count = 3)
        r.start(); r.select(1); r.select(2); r.player.pause()
        r.finishFade()
        assertTrue(r.loads.isEmpty())
        r.finishFade()
        assertEquals(Load(r.tracks.map { it.uri }, 2, false, 0), r.loads.single())
    }

    @Test fun aPendingLoadUsesTheLatestFifoBlockInsteadOfOverwritingItAfterTheFade() {
        val r = Rig(count = 3)
        r.start(); r.select(1)
        val manual = r.tracks[0].copy(uri = "spotify:track:manual", queued = true)
        val album = listOf("x", "y").map { r.tracks[0].copy(uri = "spotify:track:$it", queued = true) }
        r.queue.insertNext(listOf(manual))
        r.queue.insertNext(album)
        r.finishFade()
        assertEquals(r.queue.items.map { it.uri }, r.loads.single().uris)
        assertEquals(listOf(r.tracks[0].uri, r.tracks[1].uri, manual.uri,
            album[0].uri, album[1].uri, r.tracks[2].uri), r.loads.single().uris)
        assertEquals(r.queue.currentIndex, r.loads.single().index)
    }

    @Test fun editsAndShuffleDuringTheFadeKeepTheSameCurrentOccurrenceInTheDeliveredQueue() {
        val r = Rig(count = 4)
        r.start(); r.select(1)
        val selected = r.queue.items[r.queue.currentIndex]
        r.queue.remove(3, 4)
        r.queue.move(0, 1, r.queue.items.size)
        r.queue.setShuffled(true)
        r.finishFade()
        assertEquals(r.queue.items.map { it.uri }, r.loads.single().uris)
        assertSame(selected, r.queue.items[r.loads.single().index])
        assertEquals(0, r.loads.single().position)
    }
}
