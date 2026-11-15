@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.lelonio.square.playback

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.test.core.app.ApplicationProvider
import dev.lelonio.square.SquareApplication
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.data.PlaybackStore
import dev.lelonio.square.data.SavedPlayback
import dev.lelonio.square.ui.EXTRA_QUEUE_CYCLE_ANCHOR
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Exercises the service's actual save/restore functions; no onCreate, engine, file I/O or playback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = I05PlaybackPersistenceRegressionTest.Host::class)
class I05PlaybackPersistenceRegressionTest {
    class Host : Application(), CoreHost { override val qualityIsAutomatic = false }
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val players = mutableListOf<Player>()
    private val store get() = PlaybackStore(context)

    private fun timeline(): ExoPlayer {
        val raw = ExoPlayer.Builder(context).build().also { players += it }
        // This regression measures loaded timeline/persistence, without opening dummy content.
        return object : ExoPlayer by raw { override fun prepare() = Unit }
    }

    private fun service(player: Player, local: Boolean, parkedTracks: Int): PlaybackService {
        val app = SquareApplication()
        ReflectionHelpers.callInstanceMethod<Any>(app, "attachBaseContext",
            ReflectionHelpers.ClassParameter.from(Context::class.java, context))
        val queue = PlayQueue()
        queue.replace((0 until parkedTracks).map {
            PlayQueue.Track("spotify:track:$it", "Spotify $it", "Artist", durationMs = 120_000, artworkUri = null)
        }, 0)
        ReflectionHelpers.setField(app.spotifyBackend, "queue", queue)
        val parked = LibrespotPlayer(context, Looper.getMainLooper(), queue,
            onSpeedAndPitch = { _, _ -> }, fadeOutThen = { it() }, fadeIn = {},
            onPlaybackActive = {}, onDuckReverb = {},
            loadQueueCommand = { _, _, _, _, _, _, _ -> }, refreshRemoteState = {},
            playCommand = {}, pauseCommand = {}, queueIsAdopted = { false })
        parked.shutdownEngineOnRelease = false
        players += parked
        return PlaybackService().also {
            ReflectionHelpers.setField(it, "container", app)
            ReflectionHelpers.setField(it, "playbackStore", store)
            ReflectionHelpers.setField(it, "player", player)
            ReflectionHelpers.setField(it, "parkedSpotify", parked)
            val kind = PlaybackService::class.java.declaredClasses.first { nested -> nested.simpleName == "PlayerKind" }
                .enumConstants!!.first { value -> value.toString() == (if (local) "LOCAL" else "VIDEO") }
            ReflectionHelpers.setField(it, "playerKind", kind)
        }
    }

    private fun call(service: PlaybackService, method: String) {
        PlaybackService::class.java.getDeclaredMethod(method).apply { isAccessible = true }.invoke(service)
    }

    private fun localItems() = listOf("local:track:7", "local:track:7", "local:track:9").mapIndexed { i, uri ->
        MediaItem.Builder().setMediaId(uri).setUri(uri).setMediaMetadata(
            MediaMetadata.Builder().setTitle("Local $i").setArtist("Local artist")
                .setDurationMs(120_000).setExtras(Bundle().apply { putBoolean(EXTRA_QUEUE_CYCLE_ANCHOR, i == 1) })
                .build(),
        ).build()
    }

    @After fun cleanup() {
        players.asReversed().forEach { it.release() }
        store.clear()
    }

    @Test fun localQueueAndPositionSurviveWithEmptyOrPopulatedParkedSpotify() {
        for (parkedTracks in listOf(0, 4)) {
            store.save(SavedPlayback(listOf(CatalogTrack("spotify:track:old", "Old", "Artist")),
                index = 0, positionMs = 9_000))
            val local = timeline()
            local.setMediaItems(localItems(), 1, 37_456)
            local.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(intArrayOf(2, 0, 1), 0L))
            local.shuffleModeEnabled = true
            local.repeatMode = Player.REPEAT_MODE_ALL
            val beforeOrder = i05TimelineOrder(local)!!
            call(service(local, local = true, parkedTracks = parkedTracks), "savePlayback")
            val saved = store.load()!!
            assertEquals(localItems().map { it.mediaId }, saved.tracks.map { it.uri })
            assertEquals(beforeOrder, saved.shuffleOrder)
            assertEquals(beforeOrder.indexOf(1), saved.index)
            assertEquals(beforeOrder.indexOf(1), saved.cycleAnchorIndex)
            assertEquals(37_456L, saved.positionMs)
            assertEquals(Player.REPEAT_MODE_ALL, saved.repeatMode)

            val restored = timeline()
            call(service(restored, local = true, parkedTracks = parkedTracks), "restoreTimeline")
            assertEquals(1, restored.currentMediaItemIndex)
            assertEquals("Local 1", restored.currentMediaItem!!.mediaMetadata.title.toString())
            assertEquals(37_456L, restored.currentPosition)
            assertEquals(beforeOrder, i05TimelineOrder(restored))
            assertTrue(restored.getMediaItemAt(1).mediaMetadata.extras!!.getBoolean(EXTRA_QUEUE_CYCLE_ANCHOR))
            assertFalse(restored.playWhenReady)
        }
    }

    @Test fun videoStillSavesTheWholeParkedSpotifyQueueAtTheVideoPosition() {
        val video = timeline()
        video.setMediaItems(listOf(MediaItem.Builder().setMediaId("spotify:track:0").setUri("file:///video.mpd").build()),
            0, 25_000)
        call(service(video, local = false, parkedTracks = 3), "savePlayback")
        val saved = store.load()!!
        assertEquals(listOf("spotify:track:0", "spotify:track:1", "spotify:track:2"), saved.tracks.map { it.uri })
        assertEquals(25_000L, saved.positionMs)
    }

    @Test fun oldJsonWithoutCycleAnchorStillRestoresAtItsLocalPosition() {
        context.getSharedPreferences("square_playback", Context.MODE_PRIVATE).edit().putString("state",
            """{"tracks":[{"uri":"local:track:7","name":"Old local","artist":"Artist"}],"index":0,"positionMs":12345}""").apply()
        assertEquals(0, store.load()!!.cycleAnchorIndex)
        val restored = timeline()
        call(service(restored, local = true, parkedTracks = 0), "restoreTimeline")
        assertEquals("local:track:7", restored.currentMediaItem!!.mediaId)
        assertEquals(12_345L, restored.currentPosition)
        assertFalse(restored.playWhenReady)
    }

    @Test fun sameValueCommandsRemainExplicitAndTransportDoesNotRevokeVolume() {
        val local = timeline()
        local.setMediaItems(localItems(), 0, 0)
        var transport = 0
        var volume = 0
        val ingress = I05PlaybackIntentPlayer(local, { transport++ }, { volume++ })
        ingress.volume = ingress.volume
        assertEquals(1, volume)
        assertEquals(0, transport)
        ingress.pause() // already paused: Media3 emits no state change
        ingress.seekTo(500)
        assertEquals(2, transport)
        assertEquals(1, volume)
    }
}
