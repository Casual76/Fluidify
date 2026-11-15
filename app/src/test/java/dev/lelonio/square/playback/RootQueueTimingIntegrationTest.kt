@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.lelonio.square.playback

import android.app.Application
import android.os.Looper
import androidx.media3.common.Player
import dev.lelonio.square.data.SpotifyNetworkPolicy
import dev.lelonio.square.ui.EXTRA_QUEUE_CYCLE_ANCHOR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Producer -> timeline extras -> actual-order duration, with native dispatch replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = RootQueueTimingIntegrationTest.Host::class)
@LooperMode(LooperMode.Mode.PAUSED)
class RootQueueTimingIntegrationTest {
    class Host : Application(), CoreHost { override val qualityIsAutomatic = false }

    @Test fun restoredShuffledDuplicateOccurrenceKeepsItsCycleBoundary() {
        SpotifyNetworkPolicy.setManual(true)
        val queue = PlayQueue()
        val player = LibrespotPlayer(RuntimeEnvironment.getApplication(), Looper.getMainLooper(), queue,
            onSpeedAndPitch = { _, _ -> }, fadeOutThen = { it() }, fadeIn = {},
            onPlaybackActive = {}, onDuckReverb = {},
            loadQueueCommand = { _, _, _, _, _, _, _ -> }, refreshRemoteState = {},
            playCommand = {}, pauseCommand = {}, queueIsAdopted = { false },
        ).apply { shutdownEngineOnRelease = false }
        try {
            val tracks = listOf(60_000L, 90_000L, 60_000L, 120_000L).mapIndexed { index, length ->
                PlayQueue.Track(if (index == 0 || index == 2) "spotify:track:duplicate" else "spotify:track:$index",
                    "Song $index", "Artist", durationMs = length, artworkUri = null)
            }
            player.restore(tracks, listOf(3, 1, 0, 2), 3, 20_000, Player.REPEAT_MODE_ALL,
                "spotify:playlist:one", false, "Playlist", handOverNow = false, cycleAnchorIndex = 2)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(2, queue.cycleAnchorIndex)
            assertEquals(listOf(2), (0 until player.mediaItemCount).filter {
                player.getMediaItemAt(it).mediaMetadata.extras?.getBoolean(EXTRA_QUEUE_CYCLE_ANCHOR) == true
            })
            val timing = PlaybackQueueTiming.capture(player)
            assertNotNull(timing)
            // Current duplicate has 40s left, followed by 120s and 90s until the anchor duplicate.
            assertEquals(250_000L, timing!!.remaining(20_000)!!.milliseconds)
            assertEquals(310_000L, timing.withCurrentDuration(120_000).remaining(20_000)!!.milliseconds)
            player.repeatMode = Player.REPEAT_MODE_ONE
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(40_000L, PlaybackQueueTiming.capture(player)!!.remaining(20_000)!!.milliseconds)
            player.repeatMode = Player.REPEAT_MODE_OFF
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(40_000L, PlaybackQueueTiming.capture(player)!!.remaining(20_000)!!.milliseconds)
            // Invalid saved permutations are refused without turning on a new random shuffle.
            player.restore(tracks, listOf(0, 0, 1, 2), 0, 0, Player.REPEAT_MODE_OFF,
                null, false, "", handOverNow = false)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(player.shuffleModeEnabled)
            assertEquals(tracks.map { it.uri }, (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId })
            assertEquals(330_000L, PlaybackQueueTiming.capture(player)!!.remaining(0)!!.milliseconds)
        } finally {
            player.release()
            shadowOf(Looper.getMainLooper()).idle()
            SpotifyNetworkPolicy.setManual(false)
        }
    }
}
