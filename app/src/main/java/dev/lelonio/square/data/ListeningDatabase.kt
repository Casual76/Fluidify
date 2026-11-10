// SPDX-License-Identifier: GPL-3.0-only
package dev.lelonio.square.data

import androidx.room.*

@Entity(tableName = "listens", primaryKeys = ["service", "account", "id"], indices = [
    Index(value = ["service", "account", "startedAt"]),
    Index(value = ["service", "account", "song", "spotifyAt"], unique = true),
    Index(value = ["service", "account", "playedAt"]),
])
data class ListenRow(
    val service: String, val account: String, val id: String, val song: String,
    val trackJson: String, val startedAt: Long, val endedAt: Long,
    val playedAt: Long, val measuredMs: Long?, val qualified: Boolean, val spotifyAt: Long?,
)
@Entity(tableName = "measured_intervals", primaryKeys = ["service", "account", "eventId", "fromAt", "untilAt"],
    indices = [Index(value = ["service", "account", "untilAt"])])
data class IntervalRow(val service: String, val account: String, val eventId: String, val fromAt: Long, val untilAt: Long)

/** Each attempt is retained, including partial/failed attempts. Never asserts monthly completeness. */
@Entity(tableName = "history_coverage", primaryKeys = ["service", "account", "id"],
    indices = [Index(value = ["service", "account", "importedAt"])])
data class HistoryCoverage(
    val service: String, val account: String, val id: String,
    val requestedFrom: Long?, val requestedUntil: Long, val observedFrom: Long?, val observedUntil: Long?,
    val importedAt: Long, val pages: Int, val truncated: Boolean, val missingPages: Boolean,
    val status: String, val error: String?, val source: String = "spotify_recently_played",
    val precision: String = "reported_play_timestamp_duration_unknown", val complete: Boolean = false,
)
data class TrackHistoryCount(val song: String, val trackJson: String, val plays: Long, val localPlays: Long, val importedPlays: Long)

@Dao
interface ListeningDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun put(row: ListenRow)
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun intervals(rows: List<IntervalRow>)
    @Insert fun coverage(row: HistoryCoverage)
    @Query("SELECT * FROM listens WHERE service=:s AND account=:a AND id=:id") fun event(s: String, a: String, id: String): ListenRow?
    @Query("SELECT * FROM listens WHERE service=:s AND account=:a AND song=:song AND spotifyAt=:at LIMIT 1")
    fun imported(s: String, a: String, song: String, at: Long): ListenRow?
    @Query("SELECT * FROM listens WHERE service=:s AND account=:a AND song=:song AND measuredMs IS NOT NULL AND spotifyAt IS NULL AND startedAt<=:upper AND endedAt>=:lower ORDER BY ABS(startedAt-:at), id LIMIT 1")
    fun localMatch(s: String, a: String, song: String, at: Long, lower: Long, upper: Long): ListenRow?
    @Query("SELECT * FROM listens WHERE service=:s AND account=:a AND song=:song AND measuredMs IS NULL AND spotifyAt BETWEEN :lower AND :upper ORDER BY ABS(spotifyAt-:at), id LIMIT 1")
    fun remoteMatch(s: String, a: String, song: String, at: Long, lower: Long, upper: Long): ListenRow?
    @Query("DELETE FROM listens WHERE service=:s AND account=:a AND id=:id") fun remove(s: String, a: String, id: String)
    @Query("SELECT * FROM listens WHERE service=:s AND account=:a AND playedAt>=:from AND playedAt<:until ORDER BY playedAt DESC,id DESC LIMIT :limit OFFSET :offset")
    fun page(s: String, a: String, from: Long, until: Long, limit: Int, offset: Int): List<ListenRow>
    @Query("SELECT song, MIN(trackJson) AS trackJson, COUNT(*) AS plays, SUM(CASE WHEN measuredMs IS NOT NULL THEN 1 ELSE 0 END) AS localPlays, SUM(CASE WHEN measuredMs IS NULL THEN 1 ELSE 0 END) AS importedPlays FROM listens WHERE service=:s AND account=:a AND qualified=1 AND playedAt>=:from AND playedAt<:until GROUP BY song ORDER BY plays DESC,song ASC")
    fun counts(s: String, a: String, from: Long, until: Long): List<TrackHistoryCount>
    @Query("SELECT COALESCE(SUM(MIN(untilAt,:until)-MAX(fromAt,:from)),0) FROM measured_intervals WHERE service=:s AND account=:a AND untilAt>:from AND fromAt<:until")
    fun measured(s: String, a: String, from: Long, until: Long): Long
    @Query("SELECT * FROM history_coverage WHERE service=:s AND account=:a ORDER BY importedAt DESC,id DESC LIMIT :limit OFFSET :offset")
    fun coveragePage(s: String, a: String, limit: Int, offset: Int): List<HistoryCoverage>
    @Query("DELETE FROM listens WHERE service=:s AND account=:a") fun deleteEvents(s: String, a: String)
    @Query("DELETE FROM measured_intervals WHERE service=:s AND account=:a") fun deleteIntervals(s: String, a: String)
    @Query("DELETE FROM history_coverage WHERE service=:s AND account=:a") fun deleteCoverage(s: String, a: String)
}

@Database(entities = [ListenRow::class, IntervalRow::class, HistoryCoverage::class], version = 1, exportSchema = true)
abstract class ListeningDatabase : RoomDatabase() { abstract fun history(): ListeningDao }
