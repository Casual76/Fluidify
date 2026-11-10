// SPDX-License-Identifier: GPL-3.0-only
package dev.lelonio.square.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ListeningRepositoryTest {
    private lateinit var db: ListeningDatabase
    private lateinit var history: ListeningRepository
    private val song = CatalogTrack("spotify:track:one", "One", "Artist", durationMs = 60_000)
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("listening-controls", 0).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, ListeningDatabase::class.java).build()
        history = ListeningRepository(context, db)
        history.connect(HistoryAccount("a"))
    }
    @After fun close() { db.close() }
    private fun access() = history.session.value.access!!
    private fun local(id: String = "local:one", from: Long = 1_000) = ListeningEvent(id, song, from, from + 60_000,
        60_000, true, intervals = listOf(MeasuredInterval(from, from + 60_000)))

    @Test fun importBeforeCheckpointReconcilesAndStaleCheckpointCannotRemoveMinutes() = runBlocking {
        val a = access()
        history.importPage(a, listOf(song to 2_000L))
        history.record(a, local())
        history.record(a, local().copy(listenedMs = 10_000))
        history.importPage(a, listOf(song to 2_000L, song to 2_000L))
        val records = history.records(a, 0, 100_000).records
        assertEquals(1, records.size)
        assertEquals(60_000L, records.single().listenedMs)
        assertEquals(2_000L, records.single().spotifyAt)
        assertEquals(60_000L, history.aggregate(a, 0, 100_000).measuredLocalMs)
    }
    @Test fun repeatedImportMatchesEachLocalOccurrenceOnlyOnceAndUnknownDurationStaysNull() = runBlocking {
        val a = access()
        history.record(a, local())
        history.record(a, local("local:two", 61_000))
        history.importPage(a, listOf(song to 2_000L, song to 62_000L, song to 200_000L))
        val page = history.records(a, 0, 300_000)
        assertEquals(3, page.records.size)
        assertNull(page.records.first().listenedMs)
        assertEquals(3L, history.aggregate(a, 0, 300_000).countedPlays)
        assertEquals(1L, history.aggregate(a, 0, 300_000).importedOnlyPlays)
        assertNull(history.aggregate(a, 0, 300_000).importedDurationMs)
    }
    @Test fun intervalsSplitAtExactTimeBoundaryAndCheckpointsAreIdempotent() = runBlocking {
        val a = access()
        val boundary = Instant.parse("2026-10-01T00:00:00Z").toEpochMilli()
        val e = local(from = boundary - 30_000)
        history.record(a, e); history.record(a, e)
        assertEquals(30_000L, history.aggregate(a, boundary - 60_000, boundary).measuredLocalMs)
        assertEquals(30_000L, history.aggregate(a, boundary, boundary + 60_000).measuredLocalMs)
        history.importPage(a, listOf(song to boundary))
        assertEquals(0L, history.aggregate(a, boundary - 60_000, boundary).countedPlays)
        assertEquals(1L, history.aggregate(a, boundary, boundary + 60_000).countedPlays)
    }
    @Test fun logoutSwitchOffAndDeleteRevokeStaleWritersButRetainOtherAccounts() = runBlocking {
        val a = access()
        history.record(a, local())
        history.disconnect()
        assertTrue(runCatching { history.records(a, 0, 100_000) }.isFailure)
        history.connect(HistoryAccount("b"))
        val b = access()
        assertTrue(history.records(b, 0, 100_000).records.isEmpty())
        assertTrue(runCatching { history.record(a, local("local:stale")) }.isFailure)
        history.record(b, local())
        history.connect(HistoryAccount("a"))
        assertEquals(1, history.records(access(), 0, 100_000).records.size)
        history.setRecording(false)
        history.record(access(), local("local:off"))
        assertEquals(1, history.records(access(), 0, 100_000).records.size)
        val deleting = access()
        history.deleteAccount(deleting)
        assertTrue(runCatching { history.importPage(deleting, listOf(song to 5_000L)) }.isFailure)
        assertTrue(history.records(access(), 0, 100_000).records.isEmpty())
        history.connect(HistoryAccount("b"))
        assertEquals(1, history.records(access(), 0, 100_000).records.size)
    }
    @Test fun reopeningRepositoryKeepsCheckpointWithoutCountingProcessDowntime() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "history-restart-${java.util.UUID.randomUUID()}.db"
        val firstDb = Room.databaseBuilder(context, ListeningDatabase::class.java, name).build()
        try {
            val first = ListeningRepository(context, firstDb)
            first.connect(HistoryAccount("a"))
            first.record(first.session.value.access!!, local())
        } finally { firstDb.close() }
        val secondDb = Room.databaseBuilder(context, ListeningDatabase::class.java, name).build()
        try {
            val reopened = ListeningRepository(context, secondDb)
            reopened.restoreAccount()
            val a = reopened.session.value.access!!
            assertEquals(60_000L, reopened.aggregate(a, 0, 1_000_000).measuredLocalMs)
            val next = ListeningSampler().sample(song, true, 500_000, 500_000)!!
            reopened.record(a, next)
            assertEquals(60_000L, reopened.aggregate(a, 0, 1_000_000).measuredLocalMs)
        } finally { secondDb.close(); context.deleteDatabase(name) }
    }
    @Test fun paginationIsStableAndHalfOpen() = runBlocking {
        val a = access()
        history.importPage(a, listOf(song to 1_000L, song to 2_000L, song to 3_000L))
        val first = history.records(a, 1_000, 3_000, 1)
        val second = history.records(a, 1_000, 3_000, 1, first.nextOffset!!)
        assertEquals(2_000L, first.records.single().spotifyAt)
        assertEquals(1_000L, second.records.single().spotifyAt)
        assertNull(second.nextOffset)
    }
    @Test fun bridgeSnapshotRetainsUnknownDurationAndRejectsRevokedAccess() = runBlocking {
        val a = access()
        history.importPage(a, listOf(song to 1000L, song to 2000L))
        val snapshot = history.bridgeSnapshot(a, 0, 10000)
        assertEquals(2, snapshot.records.size)
        assertTrue(snapshot.records.all { it.listenedMs == null })
        history.importPage(a, listOf(song to 3000L))
        assertEquals(2, snapshot.records.size)
        history.disconnect()
        try { history.bridgeSnapshot(a, 0, 10000); fail("revoked") } catch (_: IllegalStateException) { }
        history.connect(HistoryAccount("b"))
        assertTrue(history.bridgeSnapshot(access(), 0, 10000).records.isEmpty())
    }
    @Test fun equalDurationStaleCheckpointCannotRegressEndOrQualification() = runBlocking {
        val a = access()
        history.record(a, local())
        history.record(a, local().copy(endedAt = 2000, qualified = false, intervals = emptyList()))
        val event = history.records(a, 0, 100000).records.single()
        assertEquals(61000L, event.endedAt)
        assertTrue(event.qualified)
        assertEquals(60000L, history.aggregate(a, 0, 100000).measuredLocalMs)
    }
}
