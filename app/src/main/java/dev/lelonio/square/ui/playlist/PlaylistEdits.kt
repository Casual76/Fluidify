package dev.lelonio.square.ui.playlist

import dev.lelonio.square.data.AccountLocalDataCoordinator
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.data.SpotifyNetworkPolicy
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.ui.MainViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PlaylistEditFeedback(
    val id: Long,
    val operation: Operation,
    val name: String,
    val reason: Reason,
) {
    enum class Operation { CREATE, RENAME, DELETE, REMOVE }
    enum class Reason { UNCONFIRMED, UNAVAILABLE, SELECTION_CHANGED }
}

internal interface PlaylistEditService {
    suspend fun create(name: String): CatalogPlaylist
    suspend fun rename(uri: String, name: String)
    suspend fun delete(uri: String)
    suspend fun remove(uri: String, trackUri: String, index: Int)
}

/**
 * Main-thread consumer of playlist write receipts. Data changes only on confirmation: there is no
 * saved list to roll back over another edit. Writes to one playlist are FIFO; other playlists and
 * independent creates can finish in either order. A failed/ambiguous write is never replayed.
 */
internal class PlaylistEdits(
    private val scope: CoroutineScope,
    private val accounts: AccountLocalDataCoordinator,
    private val library: MutableStateFlow<MainViewModel.UiState>,
    detail: MutableStateFlow<MainViewModel.PlaylistState>,
    private val service: (AccountLocalDataCoordinator.Access) -> PlaylistEditService,
    private val invalidate: (String, AccountLocalDataCoordinator.Access) -> Unit,
    private val stopDetailRead: (String) -> Unit,
    private val reloadDetail: (String) -> Unit,
    private val onlineAllowed: () -> Boolean = { true },
    private val logFailure: (Exception) -> Unit = {},
    private val networkService: ((AccountLocalDataCoordinator.Access, SpotifyNetworkPolicy.Permit) -> PlaylistEditService)? = null,
    private val currentDetail: () -> MutableStateFlow<MainViewModel.PlaylistState> = { detail },
) {
    private val detail get() = currentDetail()
    private val mutableFeedback = MutableStateFlow<List<PlaylistEditFeedback>>(emptyList())
    val feedback = mutableFeedback.asStateFlow()
    private var session = accounts.capture()
    private var presentation = 0L
    private var detailGeneration = 0L
    private var sequence = 0L
    private var revision = 0L
    private val latest = mutableMapOf<String, Long>()
    private val locks = mutableMapOf<String, Mutex>()

    // Only changed fields, never an old library snapshot. Retained for reads that began before a
    // receipt; reads begun afterwards can observe subsequent changes made outside this app.
    private data class Change(
        val revision: Long,
        val created: CatalogPlaylist? = null,
        val name: String? = null,
        val deleted: Boolean = false,
    )
    private val changes = mutableMapOf<String, Change>()

    init {
        scope.launch { accounts.access.collect { syncAccount() } }
    }

    private fun syncAccount() {
        val current = accounts.capture()
        if (current === session) return
        session = current
        changes.clear()
        latest.clear()
        revision++
        destinationChanged()
    }

    fun destinationChanged() {
        presentation++
        mutableFeedback.value = emptyList()
    }

    fun detailOpened() { detailGeneration++; destinationChanged() }
    fun dismiss(id: Long) { mutableFeedback.value = mutableFeedback.value.filterNot { it.id == id } }
    fun readVersion(): Long { syncAccount(); return revision }

    fun mergeLibraryRead(rows: List<CatalogPlaylist>, startedAt: Long): List<CatalogPlaylist> {
        syncAccount()
        var result = rows
        changes.forEach { (uri, change) ->
            if (change.revision <= startedAt) return@forEach
            result = when {
                change.deleted -> result.filterNot { it.uri == uri }
                else -> {
                    val withCreated = if (change.created != null && result.none { it.uri == uri }) {
                        listOf(change.created) + result
                    } else result
                    withCreated.map { if (it.uri == uri && change.name != null) it.copy(name = change.name) else it }
                }
            }
        }
        return result
    }

    fun mergeDetailRead(page: MainViewModel.PlaylistState, startedAt: Long): MainViewModel.PlaylistState {
        syncAccount()
        val change = changes[page.uri]?.takeIf { it.revision > startedAt }
        val ready = library.value as? MainViewModel.UiState.Ready
        val row = ready?.playlists?.firstOrNull { it.uri == page.uri }
        val saved = if (ready != null && !ready.offline && page.uri?.startsWith("spotify:playlist:") == true) {
            row != null
        } else if (change?.deleted == true) false else page.saved
        return page.copy(name = row?.name ?: change?.name ?: page.name, saved = saved)
    }

    /** A later explicit read may observe an external edit or resolve an ambiguous write. */
    fun publishLibraryRead(ready: MainViewModel.UiState.Ready, startedAt: Long) {
        library.value = ready.copy(playlists = mergeLibraryRead(ready.playlists, startedAt))
        detail.value = mergeDetailRead(detail.value, startedAt)
    }

    fun create(name: String): Job {
        val trimmed = name.trim()
        return write(PlaylistEditFeedback.Operation.CREATE, null, trimmed, valid = trimmed.isNotEmpty(),
            action = { it.create(trimmed) },
        ) { created ->
            changes[created.uri] = Change(++revision, created = created, name = created.name)
            updateLibrary { listOf(created) + it.filterNot { row -> row.uri == created.uri } }
        }
    }

    fun rename(uri: String, name: String): Job {
        val trimmed = name.trim()
        return write(PlaylistEditFeedback.Operation.RENAME, uri, label(uri),
            valid = trimmed.isNotEmpty() && uri.startsWith("spotify:playlist:"),
            action = { it.rename(uri, trimmed) },
        ) {
            val old = changes[uri]
            changes[uri] = Change(++revision, created = old?.created?.copy(name = trimmed), name = trimmed,
                deleted = old?.deleted == true)
            updateLibrary { rows -> rows.map { if (it.uri == uri) it.copy(name = trimmed) else it } }
            if (detail.value.uri == uri) detail.value = detail.value.copy(name = trimmed)
        }
    }

    fun delete(uri: String): Job = write(PlaylistEditFeedback.Operation.DELETE, uri, label(uri),
        valid = uri.startsWith("spotify:playlist:"),
        action = { it.delete(uri) },
    ) {
        changes[uri] = Change(++revision, name = changes[uri]?.name, deleted = true)
        updateLibrary { rows -> rows.filterNot { it.uri == uri } }
        if (detail.value.uri == uri) {
            stopDetailRead(uri)
            detail.value = detail.value.copy(saved = false, loading = false, loadingMore = false)
        }
    }

    fun removalAction(page: MainViewModel.PlaylistState, track: CatalogTrack, index: Int): () -> Job {
        val generation = detailGeneration
        val access = accounts.capture()
        return { remove(track, index, page, generation, access) }
    }

    fun remove(track: CatalogTrack, index: Int): Job = removalAction(detail.value, track, index).invoke()

    private fun remove(track: CatalogTrack, index: Int, page: MainViewModel.PlaylistState,
        generation: Long, access: AccountLocalDataCoordinator.Access?): Job {
        val uri = page.uri.orEmpty()
        // Legacy callers may omit the index only when there is exactly one possible occurrence.
        val at = if (index >= 0) index else page.tracks.indices.singleOrNull { page.tracks[it].uri == track.uri } ?: -1
        return write(PlaylistEditFeedback.Operation.REMOVE, uri, track.name,
            valid = uri.startsWith("spotify:playlist:"),
            prepare = {
                if (access == null || !accounts.isOnlineCurrent(access)) throw CancellationException("Replaced account")
                if (generation != detailGeneration || detail.value.uri != uri ||
                    detail.value.tracks !== page.tracks || page.loading || page.loadingMore ||
                    at < 0 || page.tracks.getOrNull(at)?.uri != track.uri) throw SelectionChanged()
                // No pre-write reader may put its old rows back after the receipt.
                stopDetailRead(uri)
            },
            action = { it.remove(uri, track.uri, at) },
        ) {
            if (generation == detailGeneration && detail.value.uri == uri) {
                if (detail.value.tracks === page.tracks) {
                    detail.value = detail.value.copy(
                        tracks = page.tracks.filterIndexed { position, _ -> position != at },
                        loading = false, loadingMore = false,
                    )
                } else reloadDetail(uri) // a newer content reader owns the rows now
            }
        }
    }

    private fun updateLibrary(change: (List<CatalogPlaylist>) -> List<CatalogPlaylist>) {
        val current = library.value as? MainViewModel.UiState.Ready ?: return
        library.value = current.copy(playlists = change(current.playlists))
    }

    private fun label(uri: String): String =
        (library.value as? MainViewModel.UiState.Ready)?.playlists?.firstOrNull { it.uri == uri }?.name
            ?: detail.value.takeIf { it.uri == uri }?.name.orEmpty()

    private class SelectionChanged : Exception()

    private fun <T> write(
        operation: PlaylistEditFeedback.Operation,
        uri: String?,
        name: String,
        valid: Boolean = true,
        prepare: () -> Unit = {},
        action: suspend (PlaylistEditService) -> T,
        commit: (T) -> Unit,
    ): Job {
        syncAccount()
        val network = runCatching { SpotifyNetworkPolicy.capture() }.getOrNull()
        val access = session?.takeIf { accounts.isOnlineCurrent(it) }
        val id = ++sequence
        val key = uri ?: "create:$id"
        val shownAt = presentation
        val startedAt = revision
        val target = if (access != null && network != null) {
            networkService?.invoke(access, network) ?: service(access)
        } else null // original backend and original lease, never a later source
        val previous = latest.put(key, id)
        mutableFeedback.value = mutableFeedback.value.filterNot { it.id == previous }
        val lock = if (uri == null) Mutex() else locks.getOrPut(key) { Mutex() }
        return scope.launch {
            if (!valid) return@launch
            fun report(reason: PlaylistEditFeedback.Reason) {
                if (shownAt != presentation || latest[key] != id) return
                mutableFeedback.value += PlaylistEditFeedback(id, operation, name, reason)
            }
            if (access == null || network == null || !onlineAllowed()) {
                report(PlaylistEditFeedback.Reason.UNAVAILABLE)
                return@launch
            }
            lock.withLock {
                var dispatched = false
                try {
                    currentCoroutineContext().ensureActive()
                    network.check()
                    accounts.requireOnline(access)
                    if (!onlineAllowed()) throw CancellationException("Offline source")
                    if (changes[uri]?.let { it.deleted && it.revision > startedAt } == true) throw SelectionChanged()
                    prepare()
                    if (uri != null) invalidate(uri, access)
                    dispatched = true
                    val result = action(checkNotNull(target))
                    network.check()
                    currentCoroutineContext().ensureActive()
                    accounts.requireOnline(access)
                    if (!onlineAllowed()) throw CancellationException("Offline source")
                    accounts.use(access) { network.check(); commit(result) }
                } catch (_: SpotifyNetworkPolicy.Offline) {
                    // Revocation cannot confirm a dispatched write. Keep its uncertain outcome
                    // visible to the same account/destination, without replaying or committing it.
                    if (accounts.isOnlineCurrent(access)) {
                        report(if (dispatched) PlaylistEditFeedback.Reason.UNCONFIRMED
                            else PlaylistEditFeedback.Reason.UNAVAILABLE)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    currentCoroutineContext().ensureActive()
                    if (accounts.isOnlineCurrent(access) && (dispatched || onlineAllowed())) {
                        if (failure !is SelectionChanged) logFailure(failure)
                        report(if (failure is SelectionChanged) PlaylistEditFeedback.Reason.SELECTION_CHANGED
                            else PlaylistEditFeedback.Reason.UNCONFIRMED)
                    }
                } finally {
                    // Even a timeout/cancellation can have reached Spotify. Invalidate, never retry.
                    if (dispatched && uri != null && accounts.isCurrent(access)) invalidate(uri, access)
                }
            }
        }
    }
}
