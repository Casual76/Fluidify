// SPDX-License-Identifier: GPL-3.0-only
package dev.lelonio.square.data

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.lelonio.square.playback.ListeningRecorder
import dev.lelonio.square.playback.PlaybackOccurrence
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Fake engine uses the production native-event mapper and real Media3 state/listener inference. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ListeningMedia3OccurrenceTest {
    private class Producer : SimpleBasePlayer(Looper.getMainLooper()) {
        val occurrence = PlaybackOccurrence()
        val uri = "spotify:track:one"
        var index = 0
        var position = 0L
        var ready = true
        init { occurrence.select(0, uri) }
        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAll(Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA).build())
            .setPlaylist((0..1).map { MediaItemData.Builder("$it $uri")
                .setMediaItem(MediaItem.Builder().setMediaId(uri).build()).setDurationUs(60_000_000).build() })
            .setCurrentMediaItemIndex(index).setContentPositionMs(position)
            .setPlaybackState(if (ready) Player.STATE_READY else Player.STATE_BUFFERING)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build()
        fun event(type: String) {
            if (type == "loading") ready = false
            if (type == "playing") { ready = true; position = 0 }
            occurrence.nativeEvent(type, uri, index, false, notify = false)
            invalidateState()
            occurrence.notifyListeners()
        }
    }

    @Test fun automaticMedia3DuplicateUriCallbackDoesNotDoubleCountNativeBoundary() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("listening-controls", 0).edit().clear().commit()
        val db = Room.inMemoryDatabaseBuilder(context, ListeningDatabase::class.java).build()
        val history = ListeningRepository(context, db).apply { connect(HistoryAccount("media3")) }
        val producer = Producer()
        var automaticTransitions = 0
        producer.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { automaticTransitions++ }
        })
        val recorder = ListeningRecorder(history, this, { producer }, { currentTime + 1_000 },
            { currentTime }, { false }, { producer.occurrence })
        try {
            runCurrent(); advanceTimeBy(31_000); runCurrent()
            producer.position = 59_750
            producer.event("position")
            producer.event("end_of_track")
            producer.event("loading")
            producer.event("playing") // repeat-one: same UID, index and URI
            advanceTimeBy(31_000); runCurrent()
            producer.event("end_of_track")
            producer.index = 1
            producer.event("playing") // Media3 itself emits transition for the next duplicate UID
            advanceTimeBy(31_000); runCurrent()
            recorder.stop(); runCurrent(); recorder.awaitCheckpoints()
            val rows = history.records(history.session.value.access!!, 0, 200_000).records
            assertTrue(automaticTransitions > 0)
            assertEquals(3, rows.size)
            assertTrue(rows.all { it.qualified })
            assertEquals(93_000L, rows.sumOf { it.listenedMs!! })
        } finally { recorder.stop(); runCurrent(); recorder.awaitCheckpoints(); db.close() }
    }
}
