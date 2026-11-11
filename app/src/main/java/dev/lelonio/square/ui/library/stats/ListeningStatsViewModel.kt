// SPDX-License-Identifier: GPL-3.0-only
package dev.lelonio.square.ui.library.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lelonio.square.data.HistorySession
import java.time.Clock
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class StatsRequest(val session: HistorySession, val period: ListeningMonth, val revision: Long = 0)

sealed interface StatsContent {
    data object NoAccount : StatsContent
    data object Loading : StatsContent
    data object Error : StatsContent
    data class Ready(val stats: ListeningStats) : StatsContent
}

data class ListeningStatsState(val request: StatsRequest, val content: StatsContent)

/** No retained data is displayed across account generations, month/zone changes or manual reloads. */
class ListeningStatsViewModel(
    private val source: ListeningStatsSource,
    private val clock: Clock = Clock.systemDefaultZone(),
    initialPeriod: ListeningMonth = ListeningMonth(YearMonth.now(clock), clock.zone),
) : ViewModel() {
    private data class Selection(val period: ListeningMonth, val revision: Long = 0)
    private val selection = MutableStateFlow(Selection(initialPeriod))
    val session = source.session
    private val mutable = MutableStateFlow(pending(request()))
    val state = mutable.asStateFlow()

    /** Synchronous fence, also checked at render time, before the session collector can run. */
    fun visibleState(): ListeningStatsState {
        val current = request()
        return mutable.value.takeIf { it.request == current } ?: pending(current)
    }

    init {
        viewModelScope.launch {
            combine(source.session, selection) { session, selected ->
                StatsRequest(session, selected.period, selected.revision)
            }.collectLatest { requested ->
                mutable.value = pending(requested)
                val access = requested.session.access ?: return@collectLatest
                try {
                    val summary = source.load(access, requested.period)
                    check(summary.period == requested.period) { "history_period_changed" }
                    if (request() == requested) mutable.value = ListeningStatsState(requested, StatsContent.Ready(summary))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // No exception/provider text or account identifiers reach the page.
                    if (request() == requested) mutable.value = ListeningStatsState(requested, StatsContent.Error)
                }
            }
        }
    }

    fun previousMonth() = selectMonth(selection.value.period.month.minusMonths(1))
    fun nextMonth() = selectMonth(selection.value.period.month.plusMonths(1))

    fun selectMonth(month: YearMonth) {
        val selected = selection.value
        val current = YearMonth.now(clock.withZone(selected.period.zone))
        if (month > current || month == selected.period.month) return
        selection.value = selected.copy(period = ListeningMonth(month, selected.period.zone))
        mutable.value = pending(request())
    }

    /** Called on foreground/resume: recompute calendar boundaries if the user's zone changed. */
    fun updateZone(zone: ZoneId) {
        val selected = selection.value
        if (zone == selected.period.zone) return
        val month = minOf(selected.period.month, YearMonth.now(clock.withZone(zone)))
        selection.value = selected.copy(period = ListeningMonth(month, zone))
        mutable.value = pending(request())
    }

    fun reload() {
        selection.value = selection.value.let { it.copy(revision = it.revision + 1) }
        mutable.value = pending(request())
    }

    /** Settings may have imported/deleted rows while this destination was stopped. Local reads only. */
    fun onForeground(zone: ZoneId) {
        if (zone != selection.value.period.zone) updateZone(zone) else reload()
    }

    private fun request() = selection.value.let { StatsRequest(source.session.value, it.period, it.revision) }
    private fun pending(request: StatsRequest) = ListeningStatsState(request,
        if (request.session.access == null) StatsContent.NoAccount else StatsContent.Loading)
}
