// SPDX-License-Identifier: GPL-3.0-only
// Reconciliation adapted from Lelonio/Square 8598e13347b9c20153898f2481083c3ffcd24fb2.
package dev.lelonio.square.data

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

data class HistoryAccess(val account: HistoryAccount, val generation: Long)
data class HistorySession(val access: HistoryAccess? = null, val recording: Boolean = true)
data class HistoryPage(val account: HistoryAccount, val from: Long, val until: Long, val records: List<ListeningEvent>, val nextOffset: Int?, val coverage: List<HistoryCoverage>)
data class HistoryAggregate(val account: HistoryAccount, val from: Long, val until: Long,
    val countedPlays: Long, val localCountedPlays: Long, val importedOnlyPlays: Long,
    val measuredLocalMs: Long, val importedDurationMs: Long? = null,
    val tracks: List<TrackHistoryCount>, val artists: List<Pair<String, Long>>,
    val coverage: List<HistoryCoverage>, val complete: Boolean = false,
    val durationPrecision: String = "observed_local_intervals_only")

/** Indexed, account-gated repository. Never uses RecentStore as historical evidence. */
class ListeningRepository(context: Context, private val db: ListeningDatabase = Room.databaseBuilder(
    context.applicationContext, ListeningDatabase::class.java, "listening-history.db",
).build()) {
    private val dao = db.history()
    private val prefs = context.getSharedPreferences("listening-controls", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private var generation = 0L
    private val mutable = MutableStateFlow(HistorySession())
    val session = mutable.asStateFlow()

    /** Persisted revocation fence; never a credential or a URI-based continuity heuristic. */
    @Synchronized fun playbackContinuity(): String? =
        if (session.value.access != null && session.value.recording) prefs.getString("playback-continuity", null) else null

    private fun rotateContinuity() {
        check(prefs.edit().putString("playback-continuity", java.util.UUID.randomUUID().toString()).commit())
    }

    suspend fun resumeOccurrence(access: HistoryAccess, id: String): ListeningEvent? = read(access) {
        if (!session.value.recording) null else dao.event(access.account.service, access.account.id, id)
            ?.takeIf { it.measuredMs != null }?.event()
    }

    @Synchronized fun connect(account: HistoryAccount) {
        if (mutable.value.access?.account == account) return
        if (prefs.getString("connected-account", null) != account.id || prefs.getString("playback-continuity", null) == null) rotateContinuity()
        prefs.edit().putString("connected-account", account.id).apply()
        mutable.value = HistorySession(HistoryAccess(account, ++generation), prefs.getBoolean("record:${account.id}", true))
    }
    /** Immediate revocation also invalidates pending imports and queued checkpoints; retains disk data. */
    @Synchronized fun restoreAccount() {
        prefs.getString("connected-account", null)?.let { connect(HistoryAccount(it)) }
    }
    @Synchronized fun disconnect() {
        rotateContinuity()
        prefs.edit().remove("connected-account").apply()
        ++generation; mutable.value = HistorySession()
    }
    @Synchronized fun setRecording(enabled: Boolean) {
        val account = mutable.value.access?.account ?: return
        rotateContinuity()
        prefs.edit().putBoolean("record:${account.id}", enabled).apply()
        mutable.value = HistorySession(HistoryAccess(account, ++generation), enabled)
    }
    private fun valid(access: HistoryAccess) = session.value.access == access
    private suspend fun <T> read(access: HistoryAccess, block: () -> T): T = withContext(Dispatchers.IO) {
        lock.withLock { synchronized(this@ListeningRepository) {
            check(valid(access)) { "History account disconnected or changed" }
            block()
        } }
    }
    suspend fun record(access: HistoryAccess, event: ListeningEvent) = read(access) {
        if (!session.value.recording || event.listenedMs == null) return@read
        db.runInTransaction {
            val a = access.account
            val old = dao.event(a.service, a.id, event.id)
            if ((old?.measuredMs ?: -1) > event.listenedMs) return@runInTransaction
            val remote = if (old?.spotifyAt == null) dao.remoteMatch(a.service, a.id, event.track.uri,
                event.startedAt, event.startedAt - 15_000, event.endedAt + 15_000) else null
            val at = old?.spotifyAt ?: remote?.spotifyAt
            remote?.let { dao.remove(a.service, a.id, it.id) }
            dao.put(event.row(a, at).let { row -> row.copy(endedAt = maxOf(row.endedAt, old?.endedAt ?: row.endedAt),
                qualified = row.qualified || old?.qualified == true) })
            dao.intervals(event.intervals.map { IntervalRow(a.service, a.id, event.id, it.from, it.until) })
        }
    }
    suspend fun importPage(access: HistoryAccess, plays: List<Pair<CatalogTrack, Long>>) = read(access) {
        db.runInTransaction {
            val a = access.account
            plays.forEach { (track, at) ->
                if (dao.imported(a.service, a.id, track.uri, at) == null) {
                    val local = dao.localMatch(a.service, a.id, track.uri, at, at - 15_000, at + 15_000)
                    dao.put(local?.copy(spotifyAt = at, playedAt = at, qualified = true)
                        ?: ListeningEvent("spotify:$at:${track.uri}", track, at, qualified = true, spotifyAt = at).row(a, at))
                }
            }
        }
    }
    suspend fun saveCoverage(access: HistoryAccess, coverage: HistoryCoverage) = read(access) {
        require(coverage.account == access.account.id && coverage.service == access.account.service)
        dao.coverage(coverage)
    }
    suspend fun records(access: HistoryAccess, from: Long, until: Long, limit: Int = 100, offset: Int = 0): HistoryPage = read(access) {
        require(from < until && limit in 1..500 && offset >= 0)
        val a = access.account
        db.runInTransaction<HistoryPage> {
            val rows = dao.page(a.service, a.id, from, until, limit + 1, offset)
            HistoryPage(a, from, until, rows.take(limit).map { it.event() },
                if (rows.size > limit) offset + limit else null, dao.coveragePage(a.service, a.id, 20, 0))
        }
    }
    /** Bridge snapshot: one account-gated transaction; refuses silent source truncation. */
    suspend fun bridgeSnapshot(access: HistoryAccess, from: Long, until: Long): HistoryPage = read(access) {
        require(from < until)
        val a = access.account
        db.runInTransaction<HistoryPage> {
            val rows = dao.page(a.service, a.id, from, until, 20_001, 0)
            check(rows.size <= 20_000) { "snapshot_limit" }
            HistoryPage(a, from, until, rows.map { it.event() }, null, dao.coveragePage(a.service, a.id, 20, 0))
        }
    }
    suspend fun bridgeCoverage(access: HistoryAccess): List<HistoryCoverage> = read(access) {
        val a = access.account
        val rows = dao.coveragePage(a.service, a.id, 20_001, 0)
        check(rows.size <= 20_000) { "snapshot_limit" }
        rows
    }
    suspend fun coverage(access: HistoryAccess, limit: Int = 100, offset: Int = 0) = read(access) {
        require(limit in 1..500 && offset >= 0)
        dao.coveragePage(access.account.service, access.account.id, limit, offset)
    }
    suspend fun aggregate(access: HistoryAccess, from: Long, until: Long): HistoryAggregate = read(access) {
        require(from < until)
        val a = access.account
        db.runInTransaction<HistoryAggregate> {
            val tracks = dao.counts(a.service, a.id, from, until)
            val artists = linkedMapOf<String, Long>()
            tracks.forEach { t ->
                val track = json.decodeFromString<CatalogTrack>(t.trackJson)
                track.artists.map { it.name }.ifEmpty { listOf(track.artist) }.filter { it.isNotBlank() }.distinct()
                    .forEach { artists[it] = (artists[it] ?: 0) + t.plays }
            }
            HistoryAggregate(a, from, until, tracks.sumOf { it.plays }, tracks.sumOf { it.localPlays },
                tracks.sumOf { it.importedPlays }, dao.measured(a.service, a.id, from, until),
                tracks = tracks, artists = artists.toList().sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first }),
                coverage = dao.coveragePage(a.service, a.id, 20, 0))
        }
    }
    suspend fun deleteAccount(access: HistoryAccess) = read(access) {
        val a = access.account
        rotateContinuity()
        db.runInTransaction { dao.deleteEvents(a.service, a.id); dao.deleteIntervals(a.service, a.id); dao.deleteCoverage(a.service, a.id) }
        mutable.value = session.value.copy(access = HistoryAccess(a, ++generation))
    }
    private fun ListeningEvent.row(a: HistoryAccount, at: Long?) = ListenRow(a.service, a.id, id, track.uri,
        json.encodeToString(CatalogTrack.serializer(), track), startedAt, endedAt, at ?: startedAt,
        listenedMs, qualified || at != null, at)
    private fun ListenRow.event() = ListeningEvent(id, json.decodeFromString<CatalogTrack>(trackJson), startedAt,
        endedAt, measuredMs, qualified, spotifyAt)
}
