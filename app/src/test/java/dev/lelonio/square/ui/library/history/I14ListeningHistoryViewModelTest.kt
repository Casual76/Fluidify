// SPDX-License-Identifier: GPL-3.0-only
package dev.lelonio.square.ui.library.history

import androidx.lifecycle.viewModelScope
import dev.lelonio.square.data.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class I14ListeningHistoryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val a = HistoryAccess(HistoryAccount("a"), 1)
    private val models = mutableListOf<ListeningHistoryViewModel>()
    private data class Call(val access: HistoryAccess, val query: String, val after: HistoryArchiveCursor?, val revision: Long) {
        val result = CompletableDeferred<HistoryArchivePage>()
    }
    private class Source : ListeningHistorySource {
        override val session = MutableStateFlow(HistorySession())
        override val changes = MutableStateFlow(0L)
        val calls = mutableListOf<Call>()
        override suspend fun page(access: HistoryAccess, query: String, after: HistoryArchiveCursor?, revision: Long): HistoryArchivePage {
            val call = Call(access, query, after, revision).also { calls += it }
            return withContext(NonCancellable) { call.result.await() }
        }
    }
    private fun entry(id: String) = HistoryArchiveEntry(ListeningEvent(id,
        CatalogTrack("spotify:track:$id", id, "Artist"), 1000), 1000)
    private fun page(id: String, next: HistoryArchiveCursor? = null) = HistoryArchivePage(listOf(entry(id)), next)
    private fun model(source: Source) = ListeningHistoryViewModel(source).also { models += it }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { models.forEach { it.viewModelScope.cancel() }; Dispatchers.resetMain() }

    @Test fun playbackSelectionRejectsRowsFromAReplacedQueryRevisionOrAccount() = runTest(dispatcher) {
        val source = object : ListeningHistorySource {
            override val session = MutableStateFlow(HistorySession(a))
            override val changes = MutableStateFlow(0L)
            override suspend fun page(access: HistoryAccess, query: String, after: HistoryArchiveCursor?, revision: Long) =
                page(access.account.id)
        }
        val model = ListeningHistoryViewModel(source).also { models += it }
        runCurrent()
        var shown = model.visibleState()
        assertEquals("spotify:track:a", model.trackForPlayback(shown.request, "a")?.uri)
        assertNull(model.trackForPlayback(shown.request, "missing"))
        model.search("new query")
        assertNull(model.trackForPlayback(shown.request, "a"))
        runCurrent(); advanceTimeBy(250); runCurrent()
        shown = model.visibleState()
        source.changes.value += 1
        assertNull(model.trackForPlayback(shown.request, "a"))
        runCurrent(); advanceTimeBy(250); runCurrent()
        shown = model.visibleState()
        source.session.value = HistorySession(HistoryAccess(HistoryAccount("b"), 2))
        assertNull(model.trackForPlayback(shown.request, "a"))
        runCurrent(); advanceTimeBy(250); runCurrent()
        shown = model.visibleState()
        assertEquals("spotify:track:b", model.trackForPlayback(shown.request, "b")?.uri)
        source.session.value = HistorySession()
        assertNull(model.trackForPlayback(shown.request, "b"))
        runCurrent()
    }

    @Test fun entireArchiveSearchRequestReplacesPageAndLateOldQueryCannotPublish() = runTest(dispatcher) {
        val source = Source().apply { session.value = HistorySession(a) }
        val model = model(source)
        runCurrent()
        val old = source.calls.single()
        model.search("outside page")
        assertTrue(model.visibleState().entries.isEmpty())
        runCurrent(); advanceTimeBy(250); runCurrent()
        val current = source.calls.last()
        assertEquals("outside page", current.query)
        assertNull(current.after)
        current.result.complete(page("matched")); runCurrent()
        old.result.complete(page("late")); runCurrent()
        assertEquals(listOf("matched"), model.visibleState().entries.map { it.event.id })
    }

    @Test fun loadMoreIsSingleFlightAndLatePageCannotReturnAfterDeleteOrAccountChange() = runTest(dispatcher) {
        val source = Source().apply { session.value = HistorySession(a) }
        val model = model(source)
        runCurrent()
        source.calls.last().result.complete(page("first", HistoryArchiveCursor(1000, "first", 0)))
        runCurrent()
        model.loadMore(); model.loadMore(); runCurrent()
        assertEquals(2, source.calls.size)
        val old = source.calls.last()
        source.session.value = HistorySession(a.copy(generation = 2)) // deletion revokes generation
        assertTrue(model.visibleState().entries.isEmpty())
        source.changes.value = 1
        runCurrent()
        source.calls.last().result.complete(HistoryArchivePage(emptyList(), null)); runCurrent()
        old.result.complete(page("deleted")); runCurrent()
        assertTrue(model.visibleState().entries.isEmpty())
        source.session.value = HistorySession(HistoryAccess(HistoryAccount("b"), 3))
        assertEquals(ListeningHistoryPhase.LOADING, model.visibleState().phase)
        runCurrent()
        source.calls.last().result.complete(page("b")); runCurrent()
        assertEquals(listOf("b"), model.visibleState().entries.map { it.event.id })
        source.session.value = HistorySession()
        assertEquals(ListeningHistoryPhase.NO_ACCOUNT, model.visibleState().phase)
        assertTrue(model.visibleState().entries.isEmpty())
        runCurrent()
    }

    @Test fun newArchiveRevisionAndRecordingGenerationHideOldRowsBeforeCollectorAndRetryRetainsPage() = runTest(dispatcher) {
        val source = Source().apply { session.value = HistorySession(a) }
        val model = model(source)
        runCurrent()
        val cursor = HistoryArchiveCursor(1000, "first", 0)
        source.calls.last().result.complete(page("first", cursor)); runCurrent()
        model.loadMore(); runCurrent()
        source.calls.last().result.completeExceptionally(IllegalStateException("private error")); runCurrent()
        assertTrue(model.visibleState().moreFailed)
        assertEquals("first", model.visibleState().entries.single().event.id)
        model.loadMore(); runCurrent()
        assertEquals(cursor, source.calls.last().after)
        source.calls.last().result.complete(page("second")); runCurrent()
        assertEquals(listOf("first", "second"), model.visibleState().entries.map { it.event.id })
        assertFalse(model.visibleState().moreFailed)
        source.changes.value = 1
        assertTrue(model.visibleState().entries.isEmpty())
        runCurrent()
        val old = source.calls.last()
        source.session.value = HistorySession(a.copy(generation = 2), false)
        assertTrue(model.visibleState().entries.isEmpty())
        runCurrent()
        old.result.complete(page("stale")); runCurrent()
        assertEquals(ListeningHistoryPhase.LOADING, model.visibleState().phase)
        source.calls.last().result.complete(page("retained")); runCurrent()
        assertEquals("retained", model.visibleState().entries.single().event.id)
    }

    @Test fun noAccountDoesNotReadAndFirstPageFailureHasExplicitRetry() = runTest(dispatcher) {
        val source = Source()
        val model = model(source)
        runCurrent()
        assertTrue(source.calls.isEmpty())
        source.session.value = HistorySession(a); runCurrent()
        source.calls.last().result.completeExceptionally(IllegalStateException("private")); runCurrent()
        assertEquals(ListeningHistoryPhase.ERROR, model.visibleState().phase)
        model.reload(); runCurrent()
        source.calls.last().result.complete(page("recovered")); runCurrent()
        assertEquals("recovered", model.visibleState().entries.single().event.id)
    }
}
