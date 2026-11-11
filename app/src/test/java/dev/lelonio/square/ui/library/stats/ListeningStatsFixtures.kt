// SPDX-License-Identifier: GPL-3.0-only
package dev.lelonio.square.ui.library.stats

import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.data.HistoryAccess
import dev.lelonio.square.data.HistoryAccount
import dev.lelonio.square.data.HistoryCoverage
import dev.lelonio.square.data.HistorySession
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** Synthetic design fixtures, test source only. Never written to an account repository. */
internal object ListeningStatsFixtures {
    val period = ListeningMonth(YearMonth.of(2026, 11), ZoneId.of("Europe/Rome"))
    val request = StatsRequest(HistorySession(HistoryAccess(HistoryAccount("demo-fictional-account"), 1)), period)
    val now = Instant.parse("2026-11-08T12:00:00Z").toEpochMilli()
    val summary = ListeningStats(
        period, plays = 103, localPlays = 61, spotifyOnlyPlays = 42, measuredLocalMs = 14_520_000,
        tracks = listOf(
            StatsTrack(CatalogTrack("demo:one", "DEMO · Luci lungo la strada del ritorno", "DEMO · Aurora e le Onde"), 31, 18, 13),
            StatsTrack(CatalogTrack("demo:two", "DEMO · Orbite", "DEMO · Vetro Blu"), 25, 15, 10),
            StatsTrack(CatalogTrack("demo:three", "DEMO · Un altro novembre", "DEMO · Mira"), 21, 12, 9),
            StatsTrack(CatalogTrack("demo:four", "DEMO · Senza fretta", "DEMO · Lune"), 16, 10, 6),
            StatsTrack(CatalogTrack("demo:five", "DEMO · Fino al mare", "DEMO · Nodi"), 10, 6, 4),
        ),
        artists = listOf("DEMO · Aurora e le Onde" to 31L, "DEMO · Vetro Blu" to 25L,
            "DEMO · Mira" to 21L, "DEMO · Lune" to 16L, "DEMO · Nodi" to 10L),
        recentReceipts = listOf(
            HistoryCoverage("spotify", "demo-fictional-account", "demo-limited", null, now,
                now - 86_400_000, now - 3_600_000, now, 5, true, false, "observed", null),
            HistoryCoverage("spotify", "demo-fictional-account", "demo-error", null, now - 86_400_000,
                null, null, now - 86_400_000, 0, false, true, "error", "demo_error"),
        ),
        loadedAt = now,
    )
    val ready = ListeningStatsState(request, StatsContent.Ready(summary))
    fun empty() = ready.copy(content = StatsContent.Ready(summary.copy(plays = 0, localPlays = 0,
        spotifyOnlyPlays = 0, measuredLocalMs = 0, tracks = emptyList(), artists = emptyList())))
    fun noAccount() = ListeningStatsState(request.copy(session = HistorySession()), StatsContent.NoAccount)
}
