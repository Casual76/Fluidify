package dev.lelonio.square.ui.requests

import dev.lelonio.square.backend.*
import dev.lelonio.square.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Prepared only; NOT EXECUTED by the requests writer. */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchRequestCoordinatorTest {
    private val track = CatalogTrack("t", "Caff\u00e8", "Artist")

    @Test fun setupThenLoadingThenErrorBelongOnlyToNewQuery() = runTest {
        var state = SearchRequestCoordinator.Snapshot()
        val pipe = SearchRequestCoordinator(this, { SearchResults() }, { state = it }, 10)
        pipe.submit("A", false) { SearchOutcome.NeedsSetup }
        advanceUntilIdle()
        assertTrue(state.needsSetup)
        pipe.submit("B", false) { SearchOutcome.Failure(SearchFailureKind.RESPONSE, IllegalArgumentException("bad payload")) }
        assertTrue(state.loading)
        assertFalse(state.needsSetup)
        assertNull(state.error)
        advanceUntilIdle()
        assertEquals("B", state.query)
        assertFalse(state.needsSetup)
        assertNotNull(state.error)
    }

    @Test fun onlineToOfflineRejectsLateNonCooperativeResponse() = runTest {
        var state = SearchRequestCoordinator.Snapshot()
        val gate = CompletableDeferred<Unit>()
        val pipe = SearchRequestCoordinator(this, { SearchResults(tracks = listOf(track)) }, { state = it }, 0)
        pipe.submit("A", false) {
            withContext(NonCancellable) { gate.await() }
            SearchOutcome.NeedsSetup
        }
        runCurrent()
        pipe.submit("caffe", true) { error("Offline must not call network") }
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("caffe", state.query)
        assertEquals(SearchResultScope.DOWNLOADS, state.resultScope)
        assertEquals(listOf(track), state.results.tracks)
        assertFalse(state.needsSetup)
    }

    @Test fun fallbackOnlyForNetworkAndRemovalDoesNotRetry() = runTest {
        var state = SearchRequestCoordinator.Snapshot()
        val completed = linkedSetOf("t")
        val metadata = mapOf("t" to track, "pending" to track.copy(uri = "pending"))
        var requests = 0
        val pipe = SearchRequestCoordinator(this,
            { SearchResults(tracks = findDownloadedTracks(it, completed, metadata::get)) }, { state = it }, 0)
        pipe.submit("caffe", false) {
            requests++
            searchFailure(java.net.UnknownHostException("dns"))
        }
        advanceUntilIdle()
        assertEquals(listOf(track), state.results.tracks)
        assertEquals(SearchResultScope.DOWNLOADS_NETWORK_FALLBACK, state.resultScope)
        completed.clear()
        pipe.refreshLocal()
        assertTrue(state.results.tracks.isEmpty())
        assertEquals(1, requests)
        for (kind in listOf(SearchFailureKind.AUTH, SearchFailureKind.RESPONSE, SearchFailureKind.OTHER)) {
            pipe.submit("caffe", false) { SearchOutcome.Failure(kind, IllegalStateException("failed")) }
            advanceUntilIdle()
            assertEquals(SearchResultScope.ONLINE, state.resultScope)
            assertNotNull(state.error)
        }
    }

    @Test fun oldAQueryCannotPublishOverNewAAndInvalidationBlocksAccountResponse() = runTest {
        var state = SearchRequestCoordinator.Snapshot()
        val gate = CompletableDeferred<Unit>()
        val pipe = SearchRequestCoordinator(this, { SearchResults() }, { state = it }, 0)
        pipe.submit("A", false) {
            withContext(NonCancellable) { gate.await() }
            SearchOutcome.NeedsSetup
        }
        runCurrent()
        pipe.submit("B", true) { error("unused") }
        pipe.submit("A", false) { SearchOutcome.Success(SearchResults(tracks = listOf(track))) }
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(track), state.results.tracks)
        assertFalse(state.needsSetup)
        val accountGate = CompletableDeferred<Unit>()
        pipe.submit("old account", false) {
            withContext(NonCancellable) { accountGate.await() }
            SearchOutcome.NeedsSetup
        }
        runCurrent()
        pipe.invalidate()
        val snapshot = state
        accountGate.complete(Unit)
        advanceUntilIdle()
        pipe.refreshLocal() // account cleanup/index emissions must not resurrect the query
        assertEquals(snapshot, state)
    }

    @Test fun emptyQueryKeepsHistoryContractAndCancellationIsNotFallback() = runTest {
        var state = SearchRequestCoordinator.Snapshot()
        var locals = 0
        val pipe = SearchRequestCoordinator(this, { locals++; SearchResults() }, { state = it }, 0)
        pipe.submit("A", false) { throw CancellationException("cancel") }
        advanceUntilIdle()
        assertEquals(0, locals)
        pipe.submit("", true) { error("empty query must not search") }
        assertFalse(state.loading)
        assertTrue(state.results.tracks.isEmpty())
        assertEquals(0, locals)
    }
}
