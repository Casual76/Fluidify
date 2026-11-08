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
import java.lang.reflect.Proxy
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SpotifyHistoryImportTest {
    private lateinit var db: ListeningDatabase
    private lateinit var history: ListeningRepository
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ListeningDatabase::class.java).build()
        history = ListeningRepository(context, db)
        history.connect(HistoryAccount("import-account"))
    }
    @After fun close() { db.close() }
    private fun api(owner: String = "import-account", fetch: (Long?) -> PageDto<PlayHistoryDto>): SpotifyApi =
        Proxy.newProxyInstance(SpotifyApi::class.java.classLoader, arrayOf(SpotifyApi::class.java)) { _, method, args ->
            when (method.name) {
                "me" -> UserDto(owner)
                "recentlyPlayed" -> { assertEquals(50, args!![0]); fetch(args[1] as Long?) }
                else -> error("Unexpected API call ${method.name}")
            }
        } as SpotifyApi
    private fun play(at: Long) = PlayHistoryDto(TrackDto(uri = "spotify:track:song", name = "Song"), Instant.ofEpochMilli(at).toString())
    @Test fun beforePaginationIsBoundedAtFivePagesAndImportsAreIdempotent() = runBlocking {
        val cursors = mutableListOf<Long?>()
        val api = api { before ->
            cursors += before
            PageDto(listOf(play(before!! - 1_000)), next = "more")
        }
        val access = history.session.value.access!!
        val importer = SpotifyHistoryImport(history, api)
        val coverage = importer.refresh(access, until = 100_000)
        importer.refresh(access, until = 100_000)
        assertEquals(listOf(100_000L, 99_000L, 98_000L, 97_000L, 96_000L), cursors.take(5))
        assertEquals(5, coverage.pages)
        assertTrue(coverage.truncated)
        assertFalse(coverage.complete)
        assertEquals(5, history.records(access, 0, 100_000).records.size)
        assertTrue(history.records(access, 0, 100_000).records.all { it.listenedMs == null })
    }
    @Test fun errorOnSecondPagePreservesFirstAndDiffersFromEmpty() = runBlocking {
        var count = 0
        val access = history.session.value.access!!
        val coverage = SpotifyHistoryImport(history, api {
            if (count++ == 0) PageDto(listOf(play(90_000)), next = "more") else throw IllegalStateException("private response")
        }).refresh(access, until = 100_000)
        assertEquals("error", coverage.status)
        assertEquals(1, coverage.pages)
        assertTrue(coverage.missingPages)
        assertEquals(1, history.records(access, 0, 100_000).records.size)
        assertEquals("IllegalStateException", coverage.error)
        val empty = SpotifyHistoryImport(history, api { PageDto(emptyList()) }).refresh(access, until = 100_000)
        assertEquals("empty", empty.status)
        assertFalse(empty.missingPages)
        assertFalse(empty.complete)
    }
    @Test fun accountMismatchDoesNotFetchOrImportAndMalformedTimestampIsPartial() = runBlocking {
        val access = history.session.value.access!!
        val mismatch = SpotifyHistoryImport(history, api("other") { error("must not fetch") }).refresh(access)
        assertEquals("error", mismatch.status)
        assertEquals(0, mismatch.pages)
        val partial = SpotifyHistoryImport(history, api {
            PageDto(listOf(PlayHistoryDto(TrackDto(uri = "spotify:track:song", name = "Song"), "bad")))
        }).refresh(access)
        assertEquals("partial", partial.status)
        assertTrue(partial.missingPages)
        assertTrue(history.records(access, 0, Long.MAX_VALUE).records.isEmpty())
    }
    @Test fun oauthReplacementDuringPageCannotPersistForeignRows() = runBlocking {
        val access = history.session.value.access!!
        var generation = 1
        val source = api { generation++; PageDto(listOf(play(90000))) }
        val importer = SpotifyHistoryImport(history, source) {
            if (generation != 1) throw kotlinx.coroutines.CancellationException("revoked")
        }
        try { importer.refresh(access, until = 100000); fail() }
        catch (_: kotlinx.coroutines.CancellationException) { }
        assertTrue(history.records(access, 0, 100000).records.isEmpty())
    }
}
