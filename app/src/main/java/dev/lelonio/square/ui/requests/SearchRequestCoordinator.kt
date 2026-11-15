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
    private val onlineAllowed: () -> Boolean = { true },
) {
    data class Snapshot(
        val query: String = "", val loading: Boolean = false,
        val results: SearchResults = SearchResults(), val error: Throwable? = null,
        val needsSetup: Boolean = false,
        val resultScope: SearchResultScope = SearchResultScope.ONLINE,
        val retryAvailable: Boolean = false,
    )
    private val ownership = RequestOwnership()
    private var job: Job? = null
    private var state = Snapshot()
    private var currentPublication: ((Snapshot) -> Unit)? = null
    fun invalidate() {
        ownership.invalidate()
        job?.cancel()
        currentPublication = null
        state = Snapshot() // index emissions after logout cannot resurrect the old query
    }
    private fun emit(value: Snapshot) { state = value; publish(value) }

    fun submit(query: String, offline: Boolean, online: suspend () -> SearchOutcome): Job? =
        submitWithAdmission(query, offline, { it() }, online)

    fun submitWithAdmission(
        query: String, offline: Boolean, admission: (() -> Unit) -> Unit,
        online: suspend () -> SearchOutcome,
    ): Job? {
        invalidate()
        val ticket = ownership.begin()
        fun emit(value: Snapshot) {
            try {
                admission {
                    ticket.ensureCurrent()
                    this@SearchRequestCoordinator.emit(value)
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Account revocation may race the last check without cancelling on the main looper yet.
            }
        }
        currentPublication = ::emit
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
            currentCoroutineContext().ensureActive()
            ticket.ensureCurrent()
            // Recheck at dispatch: MANUAL may have changed during the debounce.
            if (!onlineAllowed()) {
                emit(pending.copy(loading = false, results = local(query), resultScope = SearchResultScope.DOWNLOADS))
                return@launch
            }
            val outcome = try { online() } catch (error: Throwable) { searchFailure(error) }
            currentCoroutineContext().ensureActive()
            ticket.ensureCurrent()
            if (!onlineAllowed()) {
                emit(pending.copy(loading = false, results = local(query), resultScope = SearchResultScope.DOWNLOADS))
                return@launch
            }
            when (outcome) {
                is SearchOutcome.Success -> emit(pending.copy(loading = false, results = outcome.results))
                SearchOutcome.NeedsSetup -> emit(pending.copy(loading = false, needsSetup = true))
                is SearchOutcome.Failure -> if (outcome.kind == SearchFailureKind.NETWORK) {
                    emit(pending.copy(loading = false, results = local(query), resultScope = SearchResultScope.DOWNLOADS_NETWORK_FALLBACK,
                        retryAvailable = true))
                } else emit(pending.copy(loading = false, error = outcome.cause, retryAvailable = true))
            }
        }.also { job = it }
    }

    /** A new invocation for the same text. The caller supplies today's policy and backend. */
    fun retry(offline: Boolean, online: suspend () -> SearchOutcome): Job? {
        return retryWithAdmission(offline, { it() }, online)
    }

    fun retryWithAdmission(
        offline: Boolean, admission: (() -> Unit) -> Unit, online: suspend () -> SearchOutcome,
    ): Job? {
        if (state.loading || state.query.isBlank()) return null
        return submitWithAdmission(state.query, offline, admission, online)
    }

    /** Index changes update only a visible local result, never retry the network. */
    fun refreshLocal() {
        if (state.query.isNotBlank() && !state.loading && state.resultScope != SearchResultScope.ONLINE) {
            currentPublication?.invoke(state.copy(results = local(state.query)))
        }
    }
}
