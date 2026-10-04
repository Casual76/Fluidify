package dev.lelonio.square.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A playlist, album or Liked Songs that something was played from. */
@Serializable
data class RecentContext(
    val uri: String,
    val name: String,
    val artworkUrl: String? = null,
    val playedAtEpochMs: Long = 0,
)

/**
 * What the listener played from lately, newest first, on this phone or from the watch.
 *
 * The phone already remembers which playlists were *opened* (PlaylistOrderStore), which is how its
 * own library is sorted. "What was I listening to" is a different list — a playlist started from
 * the watch, a car or a notification never gets opened — and it is the one the watch's Home leads
 * with, as Spotify's own Home does. Kept on the device, like the pins: Spotify's own record of it
 * needs the listener's Web API app, which most do not have.
 */
class RecentContextsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(RecentContext.serializer())
    private val _contexts = MutableStateFlow(load())

    val contexts: StateFlow<List<RecentContext>> = _contexts.asStateFlow()

    /** Something started playing from [uri]. Ignored for what is not a place to come back to. */
    fun record(uri: String, name: String, artworkUrl: String?, nowMs: Long = System.currentTimeMillis()) {
        if (!isRememberable(uri) || name.isBlank()) return
        val current = _contexts.value
        val previous = current.firstOrNull { it.uri == uri }
        // Already the newest, and nothing new to say about it: not written again. A playlist has no
        // cover to add here, and was rewritten (and serialised on the main thread) on every song.
        if (current.firstOrNull()?.uri == uri && previous?.name == name && (artworkUrl == null || previous.artworkUrl != null)) return
        val entry = RecentContext(uri, name, artworkUrl ?: previous?.artworkUrl, nowMs)
        val updated = (listOf(entry) + current.filterNot { it.uri == uri }).take(LIMIT)
        _contexts.value = updated
        prefs.edit().putString(KEY, json.encodeToString(serializer, updated)).apply()
    }

    /** Forgets everything: the account it belonged to signed out. */
    fun clear() {
        _contexts.value = emptyList()
        prefs.edit().remove(KEY).apply()
    }

    private fun load(): List<RecentContext> =
        prefs.getString(KEY, null)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    companion object {
        private const val FILE_NAME = "square_recent_contexts"
        private const val KEY = "contexts"
        private const val LIMIT = 30

        /** Playlists, albums, Liked Songs; not a single track, a station or a search. */
        fun isRememberable(uri: String): Boolean =
            uri.startsWith("spotify:playlist:") || uri.startsWith("spotify:album:") || uri.endsWith(":collection")
    }
}
