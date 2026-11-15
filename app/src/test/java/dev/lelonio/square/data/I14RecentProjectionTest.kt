// SPDX-License-Identifier: GPL-3.0-only
package dev.lelonio.square.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class I14RecentProjectionTest {
    private lateinit var context: Context
    private lateinit var db: ListeningDatabase
    private lateinit var history: ListeningRepository
    private lateinit var recent: RecentStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val song = CatalogTrack("spotify:track:one", "One", "Artist")
    private fun access() = history.session.value.access!!
    private fun event(id: String, at: Long = 1000, track: CatalogTrack = song) =
        ListeningEvent(id, track, at, at + 1000, 1000, false)

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("listening-controls", 0).edit().clear().commit()
        context.getSharedPreferences("square_recent", 0).edit().putString("tracks", "legacy-unowned").commit()
        db = Room.inMemoryDatabaseBuilder(context, ListeningDatabase::class.java).build()
        history = ListeningRepository(context, db).apply { connect(HistoryAccount("a")) }
        recent = RecentStore(context, history, scope)
    }
    @After fun close() { recent.close(); scope.cancel(); db.close() }
    private suspend fun awaitTracks(uris: List<String>) {
        withTimeout(5000) { recent.tracks.first { it.map(CatalogTrack::uri) == uris } }
    }

    @Test fun uiHookCannotWriteASecondShelfOrRepopulateAfterRecordingOffAndDelete() = runBlocking {
        history.record(access(), event("local:one"))
        awaitTracks(listOf(song.uri))
        val old = access()
        history.setRecording(false)
        val off = access()
        recent.record(song.copy(uri = "spotify:track:uiOnly"))
        history.record(off, event("local:off", 2000))
        history.deleteAccount(off)
        assertTrue(recent.tracks.value.isEmpty()) // synchronous, before collector can react
        recent.record(song)
        assertTrue(runCatching { history.record(old, event("local:late")) }.isFailure)
        assertTrue(runCatching { history.importPage(off, listOf(song to 3000L)) }.isFailure)
        assertTrue(history.archive(access()).entries.isEmpty())
        awaitTracks(emptyList())
        assertFalse(context.getSharedPreferences("square_recent", 0).contains("tracks"))
    }

    @Test fun projectionSwitchesAccountsRetainsArchiveAndDeduplicatesOnlyHome() = runBlocking {
        history.record(access(), event("local:one"))
        history.record(access(), event("local:repeat", 2000))
        awaitTracks(listOf(song.uri))
        assertEquals(2, history.archive(access()).entries.size)
        history.connect(HistoryAccount("b"))
        assertTrue(recent.tracks.value.isEmpty())
        val other = song.copy(uri = "spotify:track:b")
        history.record(access(), event("local:b", 3000, other))
        awaitTracks(listOf(other.uri))
        history.connect(HistoryAccount("a"))
        assertTrue(recent.tracks.value.isEmpty())
        awaitTracks(listOf(song.uri))
        assertEquals(2, history.archive(access()).entries.size)
        history.disconnect()
        assertTrue(recent.tracks.value.isEmpty())
    }

    @Test fun staleProjectionCannotPublishAfterAccountGenerationOrArchiveRevisionChanges() = runBlocking {
        history.record(access(), event("local:one"))
        val a = access()
        val revision = history.archiveChanges.value
        val captured = history.recentArchiveTracks(a, revision)
        history.deleteAccount(a)
        history.publishIfCurrent(a, revision) { fail("late projection published $captured") }
        assertTrue(recent.tracks.value.isEmpty())
        history.setRecording(true)
        history.record(access(), event("local:new", 5000))
        awaitTracks(listOf(song.uri))
        assertEquals(listOf("local:new"), history.archive(access()).entries.map { it.event.id })
    }

    @Test fun offPreferenceSurvivesRestartAndExistingUiCallbackStillCannotRecord() = runBlocking {
        history.setRecording(false)
        val reopened = ListeningRepository(context, db).apply { restoreAccount() }
        val second = RecentStore(context, reopened, scope)
        try {
            assertFalse(reopened.session.value.recording)
            second.record(song)
            reopened.record(reopened.session.value.access!!, event("local:off"))
            assertTrue(reopened.archive(reopened.session.value.access!!).entries.isEmpty())
            assertTrue(second.tracks.value.isEmpty())
        } finally { second.close() }
    }
}
