package dev.lelonio.square.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A queue and where playback had got to in it. */
@Serializable
data class SavedPlayback(
    /** In the queue's *original* order, before any shuffle. */
    val tracks: List<CatalogTrack>,
    /**
     * The shuffle permutation in force, or null when not shuffled.
     *
     * Saved as a permutation rather than a boolean because re-enabling shuffle
     * on restore would draw a new random order, losing the queue the user had.
     */
    val shuffleOrder: List<Int>? = null,
    /** Index into the *current* (possibly shuffled) order. */
    val index: Int,
    val positionMs: Long,
    val repeatMode: Int = 0,
    /** The playlist or album the queue came from, when it came from one. */
    val contextUri: String? = null,
    /** Whether the queue is that context in its own order. */
    val contextOrdered: Boolean = false,
    /** What the player shows as the source: "Playlist · Estate 2025". */
    val contextLabel: String = "",
    val listeningOccurrenceId: String? = null,
    val historyContinuity: String? = null,
)

/** Continuity requires the explicit playback token and the still-valid history fence. */
fun SavedPlayback.restoredListeningOccurrence(continuity: String?): String? =
    listeningOccurrenceId?.takeIf { historyContinuity != null && historyContinuity == continuity }

/** Snapshot authorization plus a live validator reused by the service and producer integration tests. */
internal fun SavedPlayback.listeningRestoreAuthorization(history: ListeningRepository): Pair<String?, () -> Boolean> {
    val access = history.session.value.access
    val token = restoredListeningOccurrence(history.playbackContinuity())
    return token to {
        access != null && token != null && history.session.value.access == access &&
            restoredListeningOccurrence(history.playbackContinuity()) == token
    }
}

/**
 * Persists the playing queue so a restart resumes where the user left off.
 *
 * Deliberately never records that playback was *running*: an app that starts
 * playing music by itself when opened is worse than one that forgets. The
 * restored session is always paused, ready at the right position.
 */
class PlaybackStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }

    fun save(state: SavedPlayback) {
        if (state.tracks.isEmpty()) {
            clear()
            return
        }
        // This is the playback state, not a preview. Dropping the tail changes
        // both the selected occurrence and what comes next after a restart.
        prefs.edit()
            .putString(KEY_STATE, json.encodeToString(SavedPlayback.serializer(), state))
            .apply()
    }

    fun load(): SavedPlayback? {
        val raw = prefs.getString(KEY_STATE, null) ?: return null
        return runCatching { json.decodeFromString(SavedPlayback.serializer(), raw) }
            .getOrNull()
            ?.takeIf { it.tracks.isNotEmpty() }
    }

    fun clear() = prefs.edit().remove(KEY_STATE).apply()

    private companion object {
        const val FILE_NAME = "square_playback"
        const val KEY_STATE = "state"
    }
}
