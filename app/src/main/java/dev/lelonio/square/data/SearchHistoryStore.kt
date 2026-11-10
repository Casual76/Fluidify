package dev.lelonio.square.data

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Search plays are local to the verified Spotify account; unowned legacy history is never read. */
class SearchHistoryStore internal constructor(
    context: Context,
    private val accounts: AccountLocalDataCoordinator,
    private val ioDispatcher: CoroutineDispatcher,
) {
    constructor(context: Context, accounts: AccountLocalDataCoordinator) : this(context, accounts, Dispatchers.IO)
    private val prefs = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(CatalogTrack.serializer())
    private val mutable = MutableStateFlow<List<CatalogTrack>>(emptyList())
    val tracks: StateFlow<List<CatalogTrack>> = mutable.asStateFlow()

    init {
        accounts.observe { access ->
            val restored = access?.let { load(it.namespace) }.orEmpty()
            mutable.value = if (access != null && accounts.isCurrent(access)) restored else emptyList()
        }
    }

    suspend fun record(access: AccountLocalDataCoordinator.Access, track: CatalogTrack) =
        mutate(access) { (listOf(track) + it.filterNot { old -> old.uri == track.uri }).take(LIMIT) }

    suspend fun remove(access: AccountLocalDataCoordinator.Access, uri: String) =
        mutate(access) { it.filterNot { track -> track.uri == uri } }

    suspend fun clear(access: AccountLocalDataCoordinator.Access) = mutate(access) { emptyList() }

    private suspend fun mutate(access: AccountLocalDataCoordinator.Access,
        transform: (List<CatalogTrack>) -> List<CatalogTrack>) = withContext(ioDispatcher) {
        accounts.use(access) {
            val next = transform(load(access.namespace))
            check(prefs.edit().putString(key(access.namespace), json.encodeToString(serializer, next)).commit()) {
                "Could not persist account search history"
            }
            accounts.use(access) { mutable.value = next }
        }
    }

    private fun load(namespace: String): List<CatalogTrack> {
        val raw = prefs.getString(key(namespace), null) ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    private fun key(namespace: String) = "tracks:v2:$namespace"
    private companion object {
        const val FILE_NAME = "square_search_history"
        const val LIMIT = 20
        // Old "tracks" remains on disk, unassigned. It proves no account ownership.
    }
}
