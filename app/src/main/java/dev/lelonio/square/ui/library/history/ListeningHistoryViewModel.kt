// SPDX-License-Identifier: GPL-3.0-only
package dev.lelonio.square.ui.library.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lelonio.square.data.HistoryAccess
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.data.HistoryArchiveCursor
import dev.lelonio.square.data.HistoryArchiveEntry
import dev.lelonio.square.data.HistoryArchivePage
import dev.lelonio.square.data.HistorySession
import dev.lelonio.square.data.ListeningRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

interface ListeningHistorySource {
    val session: StateFlow<HistorySession>
    val changes: StateFlow<Long>
    suspend fun page(access: HistoryAccess, query: String, after: HistoryArchiveCursor?, revision: Long): HistoryArchivePage
}

internal class RepositoryListeningHistorySource(private val repository: ListeningRepository) : ListeningHistorySource {
    override val session = repository.session
    override val changes = repository.archiveChanges
    override suspend fun page(access: HistoryAccess, query: String, after: HistoryArchiveCursor?, revision: Long) =
        repository.archive(access, query, after = after, revision = revision)
}

data class ListeningHistoryRequest(val session: HistorySession, val query: String, val revision: Long, val reload: Long)
enum class ListeningHistoryPhase { NO_ACCOUNT, LOADING, READY, ERROR }
data class ListeningHistoryState(
    val request: ListeningHistoryRequest,
    val phase: ListeningHistoryPhase,
    val entries: List<HistoryArchiveEntry> = emptyList(),
    val next: HistoryArchiveCursor? = null,
    val loadingMore: Boolean = false,
    val moreFailed: Boolean = false,
)

/** Every result and rendered snapshot is fenced by account generation, archive revision and query. */
class ListeningHistoryViewModel(private val source: ListeningHistorySource) : ViewModel() {
    private data class Selection(val query: String = "", val reload: Long = 0)
    private val selection = MutableStateFlow(Selection())
    val session = source.session
    val changes = source.changes
    private val mutable = MutableStateFlow(pending(request()))
    val state = mutable.asStateFlow()
    private var firstPage: Job? = null
    private var more: Job? = null

    init {
        viewModelScope.launch {
            combine(source.session, source.changes, selection) { session, revision, selected ->
                ListeningHistoryRequest(session, selected.query, revision, selected.reload)
            }.collect { requested ->
                firstPage?.cancel()
                more?.cancel()
                mutable.value = pending(requested)
                val access = requested.session.access ?: return@collect
                // Do not join a cancelled provider: even a late non-cooperative result is fenced.
                firstPage = launch {
                    try {
                        if (requested.query.isNotBlank()) delay(250)
                        val page = source.page(access, requested.query, null, requested.revision)
                        if (request() == requested) mutable.value = ListeningHistoryState(
                            requested, ListeningHistoryPhase.READY, page.entries, page.next,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        if (request() == requested) mutable.value = ListeningHistoryState(requested, ListeningHistoryPhase.ERROR)
                    }
                }
            }
        }
    }

    /** Called before rendering too: no stale frame while a session collector is still queued. */
    fun visibleState(): ListeningHistoryState = mutable.value.takeIf { it.request == request() } ?: pending(request())

    /** A row from an old query, account or deleted archive cannot become a new playback request. */
    fun trackForPlayback(expected: ListeningHistoryRequest, eventId: String): CatalogTrack? {
        val shown = visibleState()
        if (shown.phase != ListeningHistoryPhase.READY || shown.request != expected) return null
        return shown.entries.firstOrNull { it.event.id == eventId }?.event?.track
    }

    fun search(query: String) {
        if (query == selection.value.query) return
        selection.value = selection.value.copy(query = query)
        mutable.value = pending(request())
    }

    fun reload() {
        selection.value = selection.value.let { it.copy(reload = it.reload + 1) }
        mutable.value = pending(request())
    }

    fun loadMore() {
        val current = visibleState()
        val access = current.request.session.access ?: return
        val cursor = current.next ?: return
        if (current.phase != ListeningHistoryPhase.READY || current.loadingMore) return
        mutable.value = current.copy(loadingMore = true, moreFailed = false)
        more = viewModelScope.launch {
            try {
                val page = source.page(access, current.request.query, cursor, current.request.revision)
                if (request() == current.request) mutable.value = current.copy(
                    entries = (current.entries + page.entries).distinctBy { it.event.id }, next = page.next,
                    loadingMore = false, moreFailed = false,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (request() == current.request) mutable.value = current.copy(loadingMore = false, moreFailed = true)
            }
        }
    }

    private fun request() = selection.value.let {
        ListeningHistoryRequest(source.session.value, it.query, source.changes.value, it.reload)
    }
    private fun pending(request: ListeningHistoryRequest) = ListeningHistoryState(request,
        if (request.session.access == null) ListeningHistoryPhase.NO_ACCOUNT else ListeningHistoryPhase.LOADING)
}
