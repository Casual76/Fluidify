// SPDX-License-Identifier: GPL-3.0-only
package dev.lelonio.square.ui.library.stats

import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.data.HistoryAccess
import dev.lelonio.square.data.HistoryAggregate
import dev.lelonio.square.data.HistoryCoverage
import dev.lelonio.square.data.HistorySession
import dev.lelonio.square.data.ListeningRepository
import java.time.Clock
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Calendar boundaries in the listener's zone, including DST; always [from, until). */
data class ListeningMonth(val month: YearMonth, val zone: ZoneId) {
    val from: Long = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val until: Long = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
}

data class StatsTrack(val track: CatalogTrack, val plays: Long, val localPlays: Long, val spotifyOnlyPlays: Long)

data class ListeningStats(
    val period: ListeningMonth,
    val plays: Long,
    val localPlays: Long,
    val spotifyOnlyPlays: Long,
    val measuredLocalMs: Long,
    val tracks: List<StatsTrack>,
    val artists: List<Pair<String, Long>>,
    /** Latest account receipts, NOT a continuous coverage range or a full receipt audit. */
    val recentReceipts: List<HistoryCoverage>,
    val loadedAt: Long,
) {
    // Short/unqualified local listening still has measured duration and must not look empty.
    val hasActivity: Boolean get() = plays > 0 || measuredLocalMs > 0
}

/** Deliberately read-only. No importer, account connection, player or network API is available here. */
interface ListeningStatsSource {
    val session: StateFlow<HistorySession>
    suspend fun load(access: HistoryAccess, period: ListeningMonth): ListeningStats
}

class RepositoryListeningStatsSource(
    private val repository: ListeningRepository,
    private val clock: Clock = Clock.systemUTC(),
) : ListeningStatsSource {
    override val session: StateFlow<HistorySession> = repository.session

    override suspend fun load(access: HistoryAccess, period: ListeningMonth): ListeningStats {
        val result = repository.aggregate(access, period.from, period.until)
        // A disconnect can happen after Room releases its repository lock.
        check(session.value.access == access) { "history_access_changed" }
        return withContext(Dispatchers.Default) {
            projectListeningStats(result, access, period, clock.millis()).also {
                check(session.value.access == access) { "history_access_changed" }
            }
        }
    }
}

/** Project the repository aggregate; never recount recent songs, records or reconciled observations. */
internal fun projectListeningStats(
    aggregate: HistoryAggregate,
    access: HistoryAccess,
    period: ListeningMonth,
    loadedAt: Long,
): ListeningStats {
    require(aggregate.account == access.account && aggregate.from == period.from && aggregate.until == period.until)
    val json = Json { ignoreUnknownKeys = true }
    return ListeningStats(
        period = period,
        plays = aggregate.countedPlays,
        localPlays = aggregate.localCountedPlays,
        spotifyOnlyPlays = aggregate.importedOnlyPlays,
        measuredLocalMs = aggregate.measuredLocalMs,
        tracks = aggregate.tracks.sortedWith(compareByDescending<dev.lelonio.square.data.TrackHistoryCount> { it.plays }
            .thenBy { it.song }).take(5).map {
            StatsTrack(json.decodeFromString<CatalogTrack>(it.trackJson), it.plays, it.localPlays, it.importedPlays)
        },
        artists = aggregate.artists.sortedWith(compareByDescending<Pair<String, Long>> { it.second }
            .thenBy { it.first }).take(5),
        recentReceipts = aggregate.coverage.filter {
            it.account == access.account.id && it.service == access.account.service
        }.sortedWith(compareByDescending<HistoryCoverage> { it.importedAt }.thenByDescending { it.id }).take(3),
        loadedAt = loadedAt,
    )
}
