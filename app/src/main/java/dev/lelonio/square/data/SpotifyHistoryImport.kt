// SPDX-License-Identifier: GPL-3.0-only
// Bounded before-pagination adapted from Square 8598e13347b9c20153898f2481083c3ffcd24fb2.
package dev.lelonio.square.data

import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.util.UUID

/** User initiated only. A successful empty response is different from a failed page. */
class SpotifyHistoryImport(private val history: ListeningRepository, private val api: SpotifyApi,
    private val credentialFence: () -> Unit = {},
) {
    companion object {
        /** Production acquisition pins one Web API credential, never the native session fallback. */
        suspend fun authenticated(history: ListeningRepository, account: dev.lelonio.square.auth.WebApiAccount,
            access: HistoryAccess): SpotifyHistoryImport {
            val generation = account.tokens.generation
            fun fence() {
                if (history.session.value.access != access || account.tokens.generation != generation)
                    throw CancellationException("History source authorization changed")
            }
            fence()
            val token = account.tokens.validAccessToken()
            fence()
            return SpotifyHistoryImport(history, dev.lelonio.square.bridge.bridgeSpotifyApi(token, ::fence), ::fence)
        }
    }
    suspend fun refresh(access: HistoryAccess, requestedFrom: Long? = null, until: Long = System.currentTimeMillis()): HistoryCoverage {
        var before = until
        var pages = 0
        var oldest: Long? = null
        var newest: Long? = null
        var more = true
        var error: String? = null
        var malformed = false
        fun fence() {
            check(history.session.value.access == access) { "History account disconnected or changed" }
            credentialFence()
        }
        try {
            fence()
            // The Web API session is independently configurable; never file its data under the engine's other account.
            check(api.me().id == access.account.id) { "Spotify account mismatch" }
            fence()
            while (pages < 5 && more) {
                fence()
                val page = api.recentlyPlayed(limit = 50, before = before)
                fence()
                pages++
                val plays = page.items.mapNotNull { play ->
                    val at = play.playedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                    if (at == null) { malformed = true; null } else play.track.toCatalogTrack() to at
                }
                val selected = plays.filter { (_, at) -> at < until && (requestedFrom == null || at >= requestedFrom) }
                history.importPage(access, selected)
                selected.forEach { (_, at) -> oldest = minOf(oldest ?: at, at); newest = maxOf(newest ?: at, at) }
                val next = plays.minOfOrNull { it.second }
                more = page.next != null && (requestedFrom == null || next == null || next > requestedFrom)
                if (more && (next == null || next >= before)) { malformed = true; break }
                if (next != null) before = next
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            // No response body/token/error text stored. HTTP codes remain useful and safe.
            error = if (failure is retrofit2.HttpException) "http_${failure.code()}" else failure.javaClass.simpleName
        }
        val result = HistoryCoverage(access.account.service, access.account.id, UUID.randomUUID().toString(),
            requestedFrom, until, oldest, newest, System.currentTimeMillis(), pages,
            truncated = more, missingPages = malformed || error != null,
            status = when { error != null -> "error"; malformed -> "partial"; oldest == null -> "empty"; else -> "observed" }, error = error)
        history.saveCoverage(access, result)
        return result
    }
}
