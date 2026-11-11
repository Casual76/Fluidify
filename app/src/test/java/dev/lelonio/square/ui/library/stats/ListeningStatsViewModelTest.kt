// SPDX-License-Identifier: GPL-3.0-only
package dev.lelonio.square.ui.library.stats

import dev.lelonio.square.data.HistoryAccess
import androidx.lifecycle.viewModelScope
import dev.lelonio.square.data.HistoryAccount
import dev.lelonio.square.data.HistorySession
import java.time.Clock
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListeningStatsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val a = HistoryAccess(HistoryAccount("demo-a"), 1)
    private val period = ListeningMonth(YearMonth.of(2026, 11), ZoneId.of("Europe/Rome"))
    private val clock = Clock.fixed(Instant.parse("2026-11-08T12:00:00Z"), period.zone)
    private val models = mutableListOf<ListeningStatsViewModel>()
    private class Pending(val access: HistoryAccess, val period: ListeningMonth) {
        val answer = CompletableDeferred<ListeningStats>()
    }
    private class Source : ListeningStatsSource {
        override val session = MutableStateFlow(HistorySession())
        val pending = mutableListOf<Pending>()
        override suspend fun load(access: HistoryAccess, period: ListeningMonth): ListeningStats {
            val call = Pending(access, period).also { pending += it }
            // Simulate a database/source that finishes after cancellation; the result fence must hold.
            return withContext(NonCancellable) { call.answer.await() }
        }
    }
    private fun summary(period: ListeningMonth) = ListeningStats(period, 7, 7, 0, 60_000,
        emptyList(), emptyList(), emptyList(), clock.millis())
    private fun model(source: Source) = ListeningStatsViewModel(source, clock, period).also { models += it }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() {
        models.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test fun absentAccountDoesNotQueryAndOldMonthResultCannotFlashAfterSelection() = runTest(dispatcher) {
        val source = Source()
        val model = model(source)
        runCurrent()
        assertEquals(StatsContent.NoAccount, model.visibleState().content)
        assertTrue(source.pending.isEmpty())
        source.session.value = HistorySession(a)
        runCurrent()
        val old = source.pending.single()
        model.previousMonth()
        assertEquals(period.month.minusMonths(1), model.visibleState().request.period.month)
        assertEquals(StatsContent.Loading, model.visibleState().content)
        old.answer.complete(summary(old.period))
        runCurrent()
        assertEquals(StatsContent.Loading, model.visibleState().content)
        val current = source.pending.last()
        current.answer.complete(summary(current.period))
        runCurrent()
        assertEquals(current.period, (model.visibleState().content as StatsContent.Ready).stats.period)
    }

    @Test fun logoutHidesReadyDataSynchronouslyAndOldGenerationCannotPublish() = runTest(dispatcher) {
        val source = Source().apply { session.value = HistorySession(a) }
        val model = model(source)
        runCurrent()
        source.pending.last().answer.complete(summary(period))
        runCurrent()
        assertTrue(model.visibleState().content is StatsContent.Ready)
        source.session.value = HistorySession()
        assertEquals(StatsContent.NoAccount, model.visibleState().content) // collector has not run
        runCurrent()
        source.session.value = HistorySession(a.copy(generation = 2), recording = false)
        runCurrent()
        val old = source.pending.last()
        source.session.value = HistorySession(HistoryAccess(HistoryAccount("demo-b"), 3))
        assertEquals(StatsContent.Loading, model.visibleState().content)
        old.answer.complete(summary(period))
        runCurrent()
        assertEquals("demo-b", model.visibleState().request.session.access!!.account.id)
        assertEquals(StatsContent.Loading, model.visibleState().content)
        source.pending.last().answer.complete(summary(period))
        runCurrent()
        assertTrue(model.visibleState().content is StatsContent.Ready)
    }

    @Test fun failureIsNotAnEmptyMonthAndRetryOnlyReadsTheCurrentAccount() = runTest(dispatcher) {
        val source = Source().apply { session.value = HistorySession(a) }
        val model = model(source)
        runCurrent()
        source.pending.last().answer.completeExceptionally(IllegalStateException("private error details"))
        runCurrent()
        assertEquals(StatsContent.Error, model.visibleState().content)
        model.reload()
        runCurrent()
        assertEquals(2, source.pending.size)
        assertEquals(a, source.pending.last().access)
        source.pending.last().answer.complete(summary(period))
        runCurrent()
        assertTrue(model.visibleState().content is StatsContent.Ready)
        model.nextMonth()
        assertEquals(period, model.visibleState().request.period) // future month disallowed
    }

    @Test fun changedZoneInvalidatesThePendingQueryAndUsesNewCalendarBoundaries() = runTest(dispatcher) {
        val source = Source().apply { session.value = HistorySession(a) }
        val model = model(source)
        runCurrent()
        val old = source.pending.single()
        model.updateZone(ZoneId.of("America/Los_Angeles"))
        assertEquals(StatsContent.Loading, model.visibleState().content)
        old.answer.complete(summary(period))
        runCurrent()
        val current = source.pending.last()
        assertNotEquals(period.from, current.period.from)
        assertEquals(ZoneId.of("America/Los_Angeles"), current.period.zone)
        current.answer.complete(summary(current.period))
        runCurrent()
        assertTrue(model.visibleState().content is StatsContent.Ready)
    }

    @Test fun returningFromSettingsRereadsOnlyLocalDataWithTheCurrentAccess() = runTest(dispatcher) {
        val source = Source().apply { session.value = HistorySession(a) }
        val model = model(source)
        runCurrent()
        source.pending.last().answer.complete(summary(period))
        runCurrent()
        model.onForeground(period.zone)
        assertEquals(StatsContent.Loading, model.visibleState().content)
        runCurrent()
        assertEquals(2, source.pending.size)
        assertEquals(a, source.pending.last().access)
        source.pending.last().answer.complete(summary(period).copy(plays = 8))
        runCurrent()
        assertEquals(8L, (model.visibleState().content as StatsContent.Ready).stats.plays)
    }
}
