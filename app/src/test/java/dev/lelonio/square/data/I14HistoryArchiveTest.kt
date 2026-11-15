// SPDX-License-Identifier: GPL-3.0-only
package dev.lelonio.square.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class I14HistoryArchiveTest {
    private lateinit var context: Context
    private lateinit var db: ListeningDatabase
    private lateinit var history: ListeningRepository
    private val song = CatalogTrack("spotify:track:one", "One", "Artist", durationMs = 60000)
    private fun access() = history.session.value.access!!
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("listening-controls", 0).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, ListeningDatabase::class.java).build()
        history = ListeningRepository(context, db)
        history.connect(HistoryAccount("a"))
    }
    @After fun close() { db.close() }
    private fun local(id: String, at: Long, track: CatalogTrack = song) =
        ListeningEvent(id, track, at, at + 1000, 1000, false,
            intervals = listOf(MeasuredInterval(at, at + 1000)))

    @Test fun titleMatchBeyondTwentyThousandRowsAndFirstVisiblePageIsFound() = runBlocking {
        // A real v1 Room table, not filtering a supplied first-page list. No bridge snapshot cap.
        val encoded = Json.encodeToString(CatalogTrack.serializer(), song)
        withContext(Dispatchers.IO) {
            db.runInTransaction {
                repeat(20050) { n -> db.history().put(ListenRow("spotify", "a", "local:$n", song.uri,
                    encoded, 100000L + n, 101000L + n, 100000L + n, 1000L, false, null)) }
            }
        }
        val old = song.copy(uri = "spotify:track:old", name = "Hidden needle", artist = "Beyond visible artist")
        history.importPage(access(), listOf(old to 1L))
        assertTrue(history.archive(access()).entries.none { it.event.track.uri == old.uri })
        val result = history.archive(access(), "NEEDLE")
        assertEquals(listOf(old.uri), result.entries.map { it.event.track.uri })
        assertNull(result.entries.single().event.listenedMs)
        assertNull(result.next)
        assertEquals(listOf(old.uri), history.archive(access(), "BEYOND VISIBLE ARTIST").entries.map { it.event.track.uri })
    }

    @Test fun paginationIncludesEveryOccurrenceShortPlayAndImportedTimestampInStableOrder() = runBlocking {
        val a = access()
        history.record(a, local("local:z", 1000))
        history.record(a, local("local:a", 1000))
        history.importPage(a, listOf(song.copy(uri = "spotify:track:import") to 2000L))
        val ids = mutableListOf<String>()
        var cursor: HistoryArchiveCursor? = null
        do {
            val page = history.archive(a, limit = 1, after = cursor)
            ids += page.entries.map { it.event.id }
            cursor = page.next
        } while (cursor != null)
        assertEquals(listOf("spotify:2000:spotify:track:import", "local:z", "local:a"), ids)
        assertEquals(3, ids.toSet().size)
        assertFalse(history.archive(a).entries.last().event.qualified)
    }

    @Test fun searchesOnlyDecodedTitleAndEveryCreditedArtistWithLiteralMetacharacters() = runBlocking {
        val a = access()
        val track = song.copy(name = "100% _ live \"take\"", artist = "Lead",
            artists = listOf(CatalogArtist("Guest beyond display")), album = "albumOnlyNeedle",
            artworkUrl = "https://example.invalid/artworkOnlyNeedle", uri = "spotify:track:uriOnlyNeedle")
        history.record(a, local("local:escaped", 1000, track))
        assertEquals(1, history.archive(a, "% _ live").entries.size)
        assertEquals(1, history.archive(a, "Guest beyond").entries.size)
        listOf("albumOnlyNeedle", "artworkOnlyNeedle", "uriOnlyNeedle", "trackJson", "durationMs", "artistUri").forEach {
            assertTrue("must not match JSON/other fields: $it", history.archive(a, it).entries.isEmpty())
        }
    }

    @Test fun unicodeCaseAndCanonicalEquivalenceDoNotDependOnTurkishDeviceLocale() = runBlocking {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val names = listOf("Beyonc\u00e9", "I\u0307STANBUL", "Stra\u00dfe", "\u6771\u4eac", "\u0915\u093f", "\u03a3\u03af\u03b3\u03bc\u03b1\u03c2")
            names.forEachIndexed { i, name -> history.record(access(), local("local:$i", 1000L + i, song.copy(name = name))) }
            listOf("BEYONCE", "istanbul", "STRASSE", "\u6771\u4eac", "\u0915\u093f", "\u03c3\u03af\u03b3\u03bc\u03b1\u03c3").forEach {
                assertEquals(it, 1, history.archive(access(), it).entries.size)
            }
            assertTrue(history.archive(access(), "\u0915\u0940").entries.isEmpty()) // preserve Indic vowel distinctions
        } finally { Locale.setDefault(original) }
    }

    @Test fun revokedGenerationAndChangedArchiveCannotContinueAnOldCursor() = runBlocking {
        val a = access()
        history.record(a, local("local:one", 1000))
        history.record(a, local("local:two", 2000))
        val cursor = history.archive(a, limit = 1).next!!
        history.record(a, local("local:three", 3000))
        assertTrue(runCatching { history.archive(a, after = cursor) }.isFailure)
        history.setRecording(false)
        assertTrue(runCatching { history.archive(a) }.isFailure)
        val off = access()
        history.record(off, local("local:blocked", 4000))
        assertEquals(3, history.archive(off).entries.size)
        history.deleteAccount(off)
        assertTrue(history.archive(access()).entries.isEmpty())
        assertTrue(runCatching { history.record(off, local("local:late", 5000)) }.isFailure)
        assertTrue(runCatching { history.importPage(off, listOf(song to 6000L)) }.isFailure)
        history.connect(HistoryAccount("b"))
        history.record(access(), local("local:b", 7000))
        history.connect(HistoryAccount("a"))
        assertFalse(history.session.value.recording)
        assertTrue(history.archive(access()).entries.isEmpty())
        history.connect(HistoryAccount("b"))
        assertEquals("local:b", history.archive(access()).entries.single().event.id)
    }

    @Test fun cancellationDuringSecondSqlBatchCannotReturnAnOrdinaryEmptySuccess() = runBlocking {
        val enteredSecondBatch = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val scanDb = Room.inMemoryDatabaseBuilder(context, ListeningDatabase::class.java)
            .setQueryCallback(object : androidx.room.RoomDatabase.QueryCallback {
                override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                    if (sqlQuery.contains("playedAt<=", ignoreCase = true)) {
                        enteredSecondBatch.complete(Unit)
                        release.await(5, TimeUnit.SECONDS)
                    }
                }
            }, Executor { it.run() }).build()
        var succeeded = false
        try {
            val encoded = Json.encodeToString(CatalogTrack.serializer(), song)
            withContext(Dispatchers.IO) {
                scanDb.runInTransaction {
                    repeat(512) { n -> scanDb.history().put(ListenRow("spotify", "a", "local:$n", song.uri,
                        encoded, n.toLong(), n.toLong(), n.toLong(), 1L, false, null)) }
                }
            }
            val reader = ListeningRepository(context, scanDb).apply { connect(HistoryAccount("a")) }
            val job = launch(Dispatchers.Default) {
                try { reader.archive(reader.session.value.access!!, "absent"); succeeded = true }
                catch (_: CancellationException) { }
            }
            try {
                withTimeout(5000) { enteredSecondBatch.await() }
                job.cancel()
            } finally { release.countDown(); job.cancelAndJoin() }
            assertFalse(succeeded)
        } finally { release.countDown(); scanDb.close() }
    }

    @Test fun legacyUnownedShelfIsRemovedBeforeHomeExistsWithoutFabricatingHistory() = runBlocking {
        val prefs = context.getSharedPreferences("square_recent", 0)
        prefs.edit().putString("tracks", "unowned legacy shelf").commit()
        val reopened = ListeningRepository(context, db).apply { restoreAccount() }
        assertFalse(prefs.contains("tracks"))
        assertTrue(reopened.archive(reopened.session.value.access!!).entries.isEmpty())
    }

    @Test fun versionOneDiskRowsImportsIntervalsAndReceiptsSurviveReopen() = runBlocking {
        val name = "i14-history-${java.util.UUID.randomUUID()}.db"
        val first = Room.databaseBuilder(context, ListeningDatabase::class.java, name).build()
        val receipt = HistoryCoverage("spotify", "a", "receipt", null, 50000, 40000, 40000,
            50000, 1, false, false, "ok", null)
        try {
            val writer = ListeningRepository(context, first).apply { connect(HistoryAccount("a")) }
            val a = writer.session.value.access!!
            writer.record(a, local("local:original", 1000))
            writer.importPage(a, listOf(song to 40000L))
            writer.saveCoverage(a, receipt)
        } finally { first.close() }
        val reopened = Room.databaseBuilder(context, ListeningDatabase::class.java, name).build()
        try {
            val reader = ListeningRepository(context, reopened).apply { restoreAccount() }
            val a = reader.session.value.access!!
            assertEquals(setOf("local:original", "spotify:40000:${song.uri}"), reader.archive(a).entries.map { it.event.id }.toSet())
            assertEquals(receipt, reader.coverage(a).single())
            assertEquals(1000L, reader.aggregate(a, 0, 100000).measuredLocalMs)
            assertNull(reader.archive(a).entries.first().event.listenedMs)
        } finally { reopened.close(); context.deleteDatabase(name) }
    }
}
