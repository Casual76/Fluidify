// SPDX-License-Identifier: GPL-3.0-only
package dev.lelonio.square.ui.library.stats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.lelonio.square.data.ListeningRepository
import java.time.YearMonth
import java.time.ZoneId

/** Parent supplies its existing repository; opening this route never connects/restores an account. */
@Composable
fun ListeningStatsRoute(repository: ListeningRepository, onBack: () -> Unit, onSettings: () -> Unit) {
    val source = remember(repository) { RepositoryListeningStatsSource(repository) }
    val model: ListeningStatsViewModel = viewModel(factory = remember(source) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ListeningStatsViewModel(source) as T
        }
    })
    val emitted by model.state.collectAsState()
    val session by model.session.collectAsState()
    // Observe both flows; consult their current values through the synchronous account/month fence.
    val visible = remember(emitted, session) { model.visibleState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) model.onForeground(ZoneId.systemDefault())
        }
        lifecycle.addObserver(observer)
        model.updateZone(ZoneId.systemDefault())
        onDispose { lifecycle.removeObserver(observer) }
    }
    ListeningStatsPage(
        state = visible,
        currentMonth = YearMonth.now(visible.request.period.zone),
        onPrevious = model::previousMonth,
        onNext = model::nextMonth,
        onReload = model::reload,
        onBack = onBack,
        onSettings = onSettings,
    )
}
