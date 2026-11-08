// SPDX-License-Identifier: GPL-3.0-only
package dev.lelonio.square.data

import android.app.Application
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.lelonio.square.playback.ListeningRecorder
import dev.lelonio.square.playback.PlaybackOccurrence
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ListeningOccurrenceRestoreTest {
    private val track = CatalogTrack("spotify:track:one", "One", "Artist", durationMs = 60_000)
    private fun player(): Player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
        when (method.name) {
            "getCurrentMediaItem" -> MediaItem.Builder().setMediaId(track.uri).build()
            "getCurrentMediaItemIndex" -> 0
            "getDuration" -> 60_000L
            "isPlaying" -> true
            "getPlaybackState" -> Player.STATE_READY
            "addListener", "removeListener" -> null
            else -> error(method.name)
        }
    } as Player

    @Test fun explicitSavedPlaybackResumesTwentyPlusTwentyWithoutDowntime() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("listening-controls", 0).edit().clear().commit()
        val db = Room.inMemoryDatabaseBuilder(context, ListeningDatabase::class.java).build()
        val first = ListeningRepository(context, db)
        first.connect(HistoryAccount("a"))
        val source = PlaybackOccurrence().apply { select(0, track.uri) }
        val p = player()
        var wallOffset = 1_000L
        fun recorder(history: ListeningRepository, producer: PlaybackOccurrence) = ListeningRecorder(
            history, this, { p }, { currentTime + wallOffset }, { currentTime }, { false }, { producer })
        val before = recorder(first, source)
        runCurrent(); advanceTimeBy(20_000); runCurrent()
        val saved = SavedPlayback(listOf(track), index = 0, positionMs = 20_000,
            listeningOccurrenceId = source.id, historyContinuity = first.playbackContinuity())
        val store = PlaybackStore(context)
        store.save(saved)
        before.stop(); runCurrent(); before.awaitCheckpoints()
        val original = first.records(first.session.value.access!!, 0, 100_000).records.single()
        assertEquals(20_000L, original.listenedMs)
        // Repository and recorder recreation; the explicit queue handoff is the proof of continuity.
        wallOffset += 3_600_000
        val after = ListeningRepository(context, db).apply { restoreAccount() }
        val loaded = store.load()!!
        val restored = PlaybackOccurrence().apply {
            select(0, track.uri, loaded.restoredListeningOccurrence(after.playbackContinuity()))
        }
        val resumed = recorder(after, restored)
        runCurrent()
        // Complete IO checkpoint lookup before driving the virtual clock.
        resumed.awaitRestore()
        runCurrent(); advanceTimeBy(20_000); runCurrent()
        resumed.stop(); runCurrent(); resumed.awaitCheckpoints()
        try {
            val access = after.session.value.access!!
            val row = after.records(access, 0, 10_000_000).records.single()
            assertEquals(original.id, row.id)
            assertEquals(40_000L, row.listenedMs)
            assertTrue(row.qualified)
            assertEquals(40_000L, after.aggregate(access, 0, 10_000_000).measuredLocalMs)
            assertEquals(0L, after.aggregate(access, 21_001, 3_600_000).measuredLocalMs)
            // A new actual selection of exactly the same URI has another identity.
            restored.select(0, track.uri)
            assertNotEquals(loaded.listeningOccurrenceId, restored.id)
        } finally { db.close() }
    }

    @Test fun revocationAccountRecordingAndDeletionFenceSavedPlaybackButKeepHistory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("listening-controls", 0).edit().clear().commit()
        val db = Room.inMemoryDatabaseBuilder(context, ListeningDatabase::class.java).build()
        val history = ListeningRepository(context, db)
        try {
            history.connect(HistoryAccount("a"))
            val access = history.session.value.access!!
            history.record(access, ListeningEvent("local:token", track, 1_000, 21_000, 20_000))
            fun saved() = SavedPlayback(listOf(track), index = 0, positionMs = 20_000,
                listeningOccurrenceId = "token", historyContinuity = history.playbackContinuity())
            val initial = saved()
            history.disconnect(); history.connect(HistoryAccount("a"))
            assertNull(initial.restoredListeningOccurrence(history.playbackContinuity()))
            assertEquals(1, history.records(history.session.value.access!!, 0, 100_000).records.size)
            val sameAccount = saved()
            history.connect(HistoryAccount("b"))
            assertNull(sameAccount.restoredListeningOccurrence(history.playbackContinuity()))
            history.connect(HistoryAccount("a"))
            val recording = saved()
            history.setRecording(false); history.setRecording(true)
            assertNull(recording.restoredListeningOccurrence(history.playbackContinuity()))
            val deletion = saved()
            history.deleteAccount(history.session.value.access!!)
            assertNull(deletion.restoredListeningOccurrence(history.playbackContinuity()))
        } finally { db.close() }
    }

    @Test fun oldPlaybackJsonRetainsQueueWithoutInventingOccurrence() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString<SavedPlayback>("""{"tracks":[{"uri":"spotify:track:one","name":"One","artist":"Artist"}],"index":0,"positionMs":20000}""")
        assertEquals(track.uri, old.tracks.single().uri)
        assertNull(old.restoredListeningOccurrence("fence"))
        PlaybackStore(context).apply { save(old); assertEquals(old, load()) }
    }

    @Test fun missingOrMismatchingExactCheckpointStartsIndependentIdentity() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (mismatching in listOf(false, true)) {
            context.getSharedPreferences("listening-controls", 0).edit().clear().commit()
            val db = Room.inMemoryDatabaseBuilder(context, ListeningDatabase::class.java).build()
            val history = ListeningRepository(context, db).apply { connect(HistoryAccount("a")) }
            val access = history.session.value.access!!
            if (mismatching) history.record(access, ListeningEvent("local:old",
                track.copy(uri = "spotify:track:other"), 1_000, 21_000, 20_000))
            val source = PlaybackOccurrence().apply { select(0, track.uri, "old") }
            val p = player()
            val recorder = ListeningRecorder(history, this, { p }, { currentTime + 100_000 },
                { currentTime }, { false }, { source })
            try {
                runCurrent(); recorder.awaitRestore(); runCurrent()
                assertNotEquals("old", source.id)
                assertFalse(source.restored)
                advanceTimeBy(1_000); runCurrent()
                recorder.stop(); runCurrent(); recorder.awaitCheckpoints()
                val rows = history.records(access, 0, 10_000_000).records
                val fresh = rows.single { it.track.uri == track.uri }
                assertNotEquals("local:old", fresh.id)
                assertEquals(1_000L, fresh.listenedMs)
                assertFalse(fresh.qualified)
                if (mismatching) assertEquals(20_000L, rows.single { it.id == "local:old" }.listenedMs)
            } finally { recorder.stop(); runCurrent(); recorder.awaitCheckpoints(); db.close() }
        }
    }
}
