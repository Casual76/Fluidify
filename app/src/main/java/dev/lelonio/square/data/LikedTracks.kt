package dev.lelonio.square.data

import dev.lelonio.square.nativecore.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Which tracks are in Liked Songs, for the whole process.
 *
 * It used to live inside the main screen's view model, which was fine while the
 * screen was the only thing with a heart. The car and the watch have one too,
 * and they ask when no screen exists: the car's heart went through the Web API,
 * which refuses that write (see native/src/collection.rs), and the watch would
 * have had nothing to ask at all. So the set lives here, the screen fills it the
 * way it always has (from the list it reads, batching what it cannot see), and
 * everyone else reads the same answer and writes through the same access-point
 * call the screen uses.
 */
class LikedTracks(private val gateway: Gateway) {

    /** Known to be saved. A track missing from it is "not that anyone has seen", not "no". */
    val known: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet())

    val state: StateFlow<Set<String>> get() = known.asStateFlow()

    /** Asked about already, so a track is asked once per run. */
    private val asked = mutableSetOf<String>()

    /**
     * Asked about and found not saved. Only the negative answers are kept here: a positive one
     * goes into [known], which the screen also edits, so an unlike on the phone cannot leave a
     * stale "yes" behind.
     */
    private val notSaved = mutableSetOf<String>()

    /**
     * Whether [uri] is saved: from what is known, or by asking once.
     *
     * Null when it cannot be told (not a Spotify track, the gateway not
     * answering), which a caller should draw as neither filled nor empty.
     */
    suspend fun isLiked(uri: String): Boolean? {
        if (!uri.startsWith("spotify:track:")) return null
        if (uri in known.value) return true
        if (synchronized(notSaved) { uri in notSaved }) return false
        if (!synchronized(asked) { asked.add(uri) }) return null
        val answer = runCatching { gateway.inLibrary(listOf(uri)) }.getOrNull()?.firstOrNull()
        if (answer == null) {
            synchronized(asked) { asked.remove(uri) }
            return null
        }
        if (answer) known.value = known.value + uri else synchronized(notSaved) { notSaved += uri }
        return answer
    }

    /** Saves or unsaves [uri] in Liked Songs, through the access point. */
    suspend fun set(uri: String, liked: Boolean) {
        withContext(Dispatchers.IO) { NativeBridge.setLiked(uri, liked) }
        synchronized(notSaved) { if (liked) notSaved -= uri else notSaved += uri }
        known.value = if (liked) known.value + uri else known.value - uri
    }
}
