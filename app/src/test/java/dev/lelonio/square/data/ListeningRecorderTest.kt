// SPDX-License-Identifier: GPL-3.0-only
package dev.lelonio.square.data

import android.app.Application
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.lelonio.square.playback.ListeningRecorder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ListeningRecorderTest {
    @Test fun serviceCallbacksSeparateSameUriRepeatsAndExcludeRemoteAudio() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("listening-controls", 0).edit().clear().commit()
        val db = Room.inMemoryDatabaseBuilder(context, ListeningDatabase::class.java).build()
        val history = ListeningRepository(context, db)
        history.connect(HistoryAccount("recorder"))
        var remote = false
        val listeners = mutableListOf<Player.Listener>()
        val item = MediaItem.Builder().setMediaId("spotify:track:one")
            .setMediaMetadata(MediaMetadata.Builder().setTitle("One").setArtist("Artist").build()).build()
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "getCurrentMediaItem" -> item
                "getDuration" -> 2_000L
                "isPlaying" -> true
                "getPlaybackState" -> Player.STATE_READY
                "addListener" -> { listeners.add(args!![0] as Player.Listener); null }
                "removeListener" -> { listeners.remove(args!![0] as Player.Listener); null }
                else -> error("Unexpected player call ${method.name}")
            }
        } as Player
        val recorder = ListeningRecorder(history, this, { player }, { testScheduler.currentTime + 1_000 },
            { testScheduler.currentTime }, { remote })
        try {
            runCurrent()
            advanceTimeBy(2_000); runCurrent()
            listeners.toList().forEach { it.onMediaItemTransition(item, Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) }
            advanceTimeBy(2_000); runCurrent()
            remote = true
            listeners.toList().forEach { it.onIsPlayingChanged(true) }
            advanceTimeBy(10_000); runCurrent()
            recorder.stop(); runCurrent(); recorder.awaitCheckpoints()
            // Repository reads serialize with asynchronous checkpoint writes.
            val access = history.session.value.access!!
            val records = history.records(access, 0, 100_000).records
            assertEquals(2, records.size)
            assertNotEquals(records[0].id, records[1].id)
            assertEquals(4_000L, history.aggregate(access, 0, 100_000).measuredLocalMs)
            assertEquals(2L, history.aggregate(access, 0, 100_000).localCountedPlays)
        } finally { recorder.stop(); runCurrent(); recorder.awaitCheckpoints(); db.close() }
    }
}
