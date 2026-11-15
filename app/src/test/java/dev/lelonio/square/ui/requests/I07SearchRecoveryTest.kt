package dev.lelonio.square.ui.requests

import dev.lelonio.square.backend.SearchOutcome
import dev.lelonio.square.backend.searchFailure
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.data.SearchResultScope
import dev.lelonio.square.data.SearchResults
import java.net.UnknownHostException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** PREPARED ONLY; no provider, JVM or test runner invoked by I07. */
@OptIn(ExperimentalCoroutinesApi::class)
class I07SearchRecoveryTest {
    @Test fun sameQueryRecoversFromFallbackAndDownloadRefreshDoesNotRetry() = runTest {
        var state = SearchRequestCoordinator.Snapshot()
        val track = CatalogTrack("t", "Song", "Artist")
        var requests = 0
        val pipe = SearchRequestCoordinator(this, { SearchResults() }, { state = it }, 0)
        pipe.submit("Song", false) { requests++; searchFailure(UnknownHostException("offline")) }
        advanceUntilIdle()
        assertTrue(state.retryAvailable)
        assertEquals(SearchResultScope.DOWNLOADS_NETWORK_FALLBACK, state.resultScope)
        pipe.refreshLocal(); assertEquals(1, requests)
        pipe.retry(false) { requests++; SearchOutcome.Success(SearchResults(tracks = listOf(track))) }
        advanceUntilIdle()
        assertEquals("Song", state.query)
        assertEquals(2, requests)
        assertEquals(SearchResultScope.ONLINE, state.resultScope)
        assertFalse(state.retryAvailable)
        assertEquals(listOf(track), state.results.tracks)
    }

    @Test fun manualDuringDebounceAndExplicitRetryCannotCallOnlineAndLateResponseCannotPublish() = runTest {
        var manual = false
        var state = SearchRequestCoordinator.Snapshot()
        var requests = 0
        val pipe = SearchRequestCoordinator(this, { SearchResults() }, { state = it }, 10,
            onlineAllowed = { !manual })
        pipe.submit("same", false) { requests++; SearchOutcome.NeedsSetup }
        manual = true
        advanceUntilIdle()
        assertEquals(0, requests)
        assertEquals(SearchResultScope.DOWNLOADS, state.resultScope)
        pipe.retry(true) { error("Manual retry cannot dispatch") }
        advanceUntilIdle(); assertFalse(state.retryAvailable)
        manual = false
        val gate = CompletableDeferred<Unit>()
        pipe.retry(false) {
            requests++
            withContext(NonCancellable) { gate.await() }
            SearchOutcome.NeedsSetup
        }
        advanceTimeBy(10); runCurrent()
        manual = true
        pipe.submit("same", true) { error("No network") }
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(1, requests)
        assertFalse(state.needsSetup)
        assertEquals(SearchResultScope.DOWNLOADS, state.resultScope)
    }
}
