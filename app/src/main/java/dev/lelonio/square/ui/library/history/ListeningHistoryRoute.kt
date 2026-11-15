// SPDX-License-Identifier: GPL-3.0-only
package dev.lelonio.square.ui.library.history

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.lelonio.square.data.ListeningRepository
import dev.lelonio.square.data.CatalogTrack

/** Local reads only; account authority and imports remain with the existing application container. */
@Composable
fun ListeningHistoryRoute(
    repository: ListeningRepository,
    onBack: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
    onPlayTrack: ((CatalogTrack) -> Unit)? = null,
) {
    val source = remember(repository) { RepositoryListeningHistorySource(repository) }
    val model: ListeningHistoryViewModel = viewModel(factory = remember(source) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ListeningHistoryViewModel(source) as T
        }
    })
    // Subscribe to every input of the synchronous visibility fence.
    val emitted by model.state.collectAsState()
    val session by model.session.collectAsState()
    val changes by model.changes.collectAsState()
    val visible = remember(emitted, session, changes) { model.visibleState() }
    ListeningHistoryPage(visible, model::search, model::loadMore, model::reload, onBack, contentPadding,
        onPlay = onPlayTrack?.let { play -> { entry ->
            model.trackForPlayback(visible.request, entry.event.id)?.let(play)
        } },
    )
}
