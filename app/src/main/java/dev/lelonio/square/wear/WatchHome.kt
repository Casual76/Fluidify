package dev.lelonio.square.wear

import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.data.RecentContext

/** One row of the watch's Home before it becomes a protocol item. */
data class HomeEntry(
    val uri: String,
    val name: String,
    val artworkUrl: String?,
    val pinned: Boolean = false,
)

/**
 * The order of the watch's Home, as the listener asked for it: what they pinned on top, in the
 * order they pinned it; then what they played lately, newest first; then what they opened lately
 * on the phone; then the rest of their playlists. No row twice.
 *
 * Pure, so the order can be tested without a phone.
 */
object WatchHome {

    fun top(
        pinned: List<String>,
        recent: List<RecentContext>,
        opened: List<String>,
        playlists: List<CatalogPlaylist>,
        limit: Int,
    ): List<HomeEntry> {
        val byUri = playlists.associateBy { it.uri }
        val recentByUri = recent.associateBy { it.uri }
        val rows = LinkedHashMap<String, HomeEntry>()
        fun add(uri: String, isPinned: Boolean = false) {
            if (rows.size >= limit || uri in rows || uri == Dj) return
            val playlist = byUri[uri]
            val played = recentByUri[uri]
            val name = playlist?.name ?: played?.name ?: return
            rows[uri] = HomeEntry(uri, name, playlist?.artworkUrl ?: played?.artworkUrl, isPinned)
        }
        pinned.forEach { add(it, isPinned = true) }
        recent.forEach { add(it.uri) }
        opened.forEach { add(it) }
        playlists.forEach { add(it.uri) }
        return rows.values.toList()
    }

    /**
     * Whether [uri] is one of the playlists Spotify makes for this listener alone — Discover Weekly,
     * Release Radar, the Daily Mixes, On Repeat, Repeat Rewind. Their ids share a prefix that the
     * editorial playlists (Today's Top Hits and the like) do not.
     */
    fun isMadeForYou(uri: String): Boolean {
        if (!uri.startsWith(PLAYLIST) || uri == Dj) return false
        val id = uri.removePrefix(PLAYLIST)
        return PersonalPrefixes.any { id.startsWith(it) }
    }

    /**
     * The "made for you" shelf: from Spotify's own home shelves first (which are already in the
     * order Spotify wants them), then from the listener's saved playlists, then from what an earlier
     * search found. Discover Weekly and Release Radar lead when they are there.
     */
    fun madeForYou(fromHome: List<HomeEntry>, fromLibrary: List<CatalogPlaylist>, remembered: List<HomeEntry>, limit: Int): List<HomeEntry> {
        val rows = LinkedHashMap<String, HomeEntry>()
        (fromHome + fromLibrary.map { HomeEntry(it.uri, it.name, it.artworkUrl) } + remembered)
            .filter { isMadeForYou(it.uri) }
            .forEach { entry -> if (entry.uri !in rows) rows[entry.uri] = entry }
        return rows.values.sortedBy { if (it.uri.removePrefix(PLAYLIST).startsWith(WEEKLY_PREFIX)) 0 else 1 }.take(limit)
    }

    private const val PLAYLIST = "spotify:playlist:"

    /** Spotify's DJ: a playlist only in name, and one the watch cannot play. */
    private const val Dj = "spotify:playlist:37i9dQZF1EYkqdzj48dyYq"

    /** Discover Weekly and Release Radar. */
    private const val WEEKLY_PREFIX = "37i9dQZEVX"
    private val PersonalPrefixes = listOf(WEEKLY_PREFIX, "37i9dQZF1E")
}
