// SPDX-License-Identifier: GPL-3.0-only
package dev.lelonio.square.ui.library.stats

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.lelonio.square.data.CatalogArtist
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.data.HistoryAccount
import dev.lelonio.square.data.HistoryCoverage
import dev.lelonio.square.data.ListeningDatabase
import dev.lelonio.square.data.ListeningEvent
import dev.lelonio.square.data.ListeningRepository
import dev.lelonio.square.data.MeasuredInterval
import java.time.Clock
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Room -> repository.aggregate -> stats adapter, with explicitly synthetic local records. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ListeningStatsRepositoryTest {
    private lateinit var db: ListeningDatabase
    private lateinit var repository: ListeningRepository
    private lateinit var source: RepositoryListeningStatsSource
    private val period = ListeningMonth(YearMonth.of(2026, 3), ZoneId.of("Europe/Rome"))
    private val track = CatalogTrack("spotify:track:demo-one", "DEMO One", "DEMO A & B", durationMs = 60_000,
        artists = listOf(CatalogArtist("DEMO A"), CatalogArtist("DEMO B")))

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("listening-controls", 0).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, ListeningDatabase::class.java).build()
        repository = ListeningRepository(context, db)
        repository.connect(HistoryAccount("demo-a"))
        source = RepositoryListeningStatsSource(repository, Clock.fixed(Instant.parse("2026-04-02T12:00:00Z"), ZoneId.of("UTC")))
    }
    @After fun close() { db.close() }
    private fun access() = repository.session.value.access!!
    private fun event(id: String, from: Long, duration: Long = 60_000, qualified: Boolean = true) =
        ListeningEvent(id, track, from, from + duration, duration, qualified,
            intervals = listOf(MeasuredInterval(from, from + duration)))

    @Test fun importBeforeLocalFlushCountsOnceAndNeverAddsNominalSpotifyDuration() = runBlocking {
        val a = access()
        val at = period.from + 3_600_000
        repository.importPage(a, listOf(track to at, track to (at + 300_000)))
        repository.record(a, event("demo-reconciled", at))
        repository.record(a, event("demo-reconciled", at)) // same checkpoint, not another listen/interval
        val result = source.load(a, period)
        assertEquals(2L, result.plays)
        assertEquals(1L, result.localPlays)
        assertEquals(1L, result.spotifyOnlyPlays)
        assertEquals(60_000L, result.measuredLocalMs)
        assertEquals(2L, result.tracks.single().plays)
        assertEquals(listOf("DEMO A" to 2L, "DEMO B" to 2L), result.artists)
    }

    @Test fun monthlyCountsAreHalfOpenWhileMeasuredIntervalsSplitAcrossMonthBoundary() = runBlocking {
        val a = access()
        repository.record(a, event("demo-boundary", period.from - 30_000)
            .copy(track = track.copy(uri = "spotify:track:demo-boundary-only")))
        repository.importPage(a, listOf(track to period.from, track to period.until))
        val result = source.load(a, period)
        assertEquals(1L, result.plays)
        assertEquals(30_000L, result.measuredLocalMs)
        assertEquals(1L, result.spotifyOnlyPlays)
        assertEquals(Instant.parse("2026-02-28T23:00:00Z").toEpochMilli(), period.from)
        assertEquals(Instant.parse("2026-03-31T22:00:00Z").toEpochMilli(), period.until)
        assertEquals(31L * 86_400_000 - 3_600_000, period.until - period.from)
    }

    @Test fun importedOnlyMonthHasUnknownDurationAndShortLocalActivityIsNotEmpty() = runBlocking {
        val a = access()
        repository.importPage(a, listOf(track to (period.from + 600_000)))
        assertEquals(0L, source.load(a, period).measuredLocalMs)
        repository.deleteAccount(a)
        val renewed = access()
        repository.record(renewed, event("demo-short", period.from + 120_000, duration = 12_000, qualified = false))
        val short = source.load(renewed, period)
        assertEquals(0L, short.plays)
        assertEquals(12_000L, short.measuredLocalMs)
        assertTrue(short.hasActivity)
        assertTrue(short.tracks.isEmpty())
    }

    @Test fun accountSwitchAndDeletionRevokeOldAccessAndKeepAccountsSeparate() = runBlocking {
        val a = access()
        repository.record(a, event("demo-a", period.from + 120_000))
        repository.connect(HistoryAccount("demo-b"))
        assertFalse(source.load(access(), period).hasActivity)
        try { source.load(a, period); fail("old access must fail") } catch (_: IllegalStateException) { }
        repository.connect(HistoryAccount("demo-a"))
        assertEquals(1L, source.load(access(), period).plays)
        val beforeDelete = access()
        repository.deleteAccount(beforeDelete)
        try { source.load(beforeDelete, period); fail("deleted generation must fail") } catch (_: IllegalStateException) { }
        assertFalse(source.load(access(), period).hasActivity)
    }

    @Test fun latestReceiptsKeepTheirTrueRangesAndFailureFlagsAcrossMonths() = runBlocking {
        val a = access()
        repeat(4) { index ->
            repository.saveCoverage(a, HistoryCoverage("spotify", a.account.id, "demo-$index", null, period.from,
                period.from - 3_600_000, period.from - 1_000, period.until + index, 1,
                truncated = index == 3, missingPages = index == 2, status = "partial", error = null))
        }
        val result = source.load(a, period)
        assertEquals(listOf("demo-3", "demo-2", "demo-1"), result.recentReceipts.map { it.id })
        assertTrue(result.recentReceipts.first().truncated)
        assertTrue(result.recentReceipts[1].missingPages)
        assertTrue(result.recentReceipts.all { it.observedUntil!! < period.from && !it.complete })
        assertFalse(result.hasActivity) // receipts are not listens and are not monthly coverage
    }

    @Test fun tiedTrackRanksRemainDeterministicAndAutumnMonthHasAnExtraHour() = runBlocking {
        val a = access()
        repository.importPage(a, listOf(track.copy(uri = "spotify:track:z") to (period.from + 100_000),
            track.copy(uri = "spotify:track:a") to (period.from + 200_000)))
        assertEquals(listOf("spotify:track:a", "spotify:track:z"), source.load(a, period).tracks.map { it.track.uri })
        val october = ListeningMonth(YearMonth.of(2026, 10), period.zone)
        assertEquals(31L * 86_400_000 + 3_600_000, october.until - october.from)
    }
}
