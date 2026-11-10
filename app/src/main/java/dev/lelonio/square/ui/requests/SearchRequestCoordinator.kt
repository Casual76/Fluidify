package dev.lelonio.square.ui.requests

import dev.lelonio.square.backend.SearchOutcome
import dev.lelonio.square.backend.SearchFailureKind
import dev.lelonio.square.backend.searchFailure
import dev.lelonio.square.data.SearchResults
import dev.lelonio.square.data.SearchResultScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The actual VM pipeline; injected readers make races reproducible without Android. */
class SearchRequestCoordinator(
    private val scope: CoroutineScope,
    private val local: (String) -> SearchResults,
    private val publish: (Snapshot) -> Unit,
    private val debounceMs: Long,
) {
    data class Snapshot(
        val query: String = "", val loading: Boolean = false,
        val results: SearchResults = SearchResults(), val error: Throwable? = null,
        val needsSetup: Boolean = false,
        val resultScope: SearchResultScope = SearchResultScope.ONLINE,
    )
    private val ownership = RequestOwnership()
    private var job: Job? = null
    private var state = Snapshot()
    fun invalidate() {
        ownership.invalidate()
        job?.cancel()
        state = Snapshot() // index emissions after logout cannot resurrect the old query
    }
    private fun emit(value: Snapshot) { state = value; publish(value) }

    fun submit(query: String, offline: Boolean, online: suspend () -> SearchOutcome): Job? {
        invalidate()
        val ticket = ownership.begin()
        val pending = Snapshot(query = query, loading = query.isNotBlank(),
            resultScope = if (offline) SearchResultScope.DOWNLOADS else SearchResultScope.ONLINE)
        emit(pending) // clears setup/error immediately, including the debounce period
        if (query.isBlank()) return null
        if (offline) {
            emit(pending.copy(loading = false, results = local(query)))
            return null
        }
        return scope.launch {
            delay(debounceMs)
            val outcome = try { online() } catch (error: Throwable) { searchFailure(error) }
            currentCoroutineContext().ensureActive()
            ticket.ensureCurrent()
            when (outcome) {
                is SearchOutcome.Success -> emit(pending.copy(loading = false, results = outcome.results))
                SearchOutcome.NeedsSetup -> emit(pending.copy(loading = false, needsSetup = true))
                is SearchOutcome.Failure -> if (outcome.kind == SearchFailureKind.NETWORK) {
                    emit(pending.copy(loading = false, results = local(query), resultScope = SearchResultScope.DOWNLOADS_NETWORK_FALLBACK))
                } else emit(pending.copy(loading = false, error = outcome.cause))
            }
        }.also { job = it }
    }

    /** Index changes update only a visible local result, never retry the network. */
    fun refreshLocal() {
        if (state.query.isNotBlank() && !state.loading && state.resultScope != SearchResultScope.ONLINE) {
            emit(state.copy(results = local(state.query)))
        }
    }
}
