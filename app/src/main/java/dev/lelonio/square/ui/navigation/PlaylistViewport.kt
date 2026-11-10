package dev.lelonio.square.ui.navigation

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable

/** Only two integers enter saved state. A loading row must not clamp a restored track position. */
private class PlaylistViewport(private var pending: List<Int>? = null) {
    val list = LazyListState()
    suspend fun restoreWhenReady() {
        val position = pending ?: return
        list.scrollToItem(position[0], position[1])
        pending = null
    }
    companion object {
        val Saver = listSaver<PlaylistViewport, Int>(
            save = { it.pending ?: listOf(it.list.firstVisibleItemIndex, it.list.firstVisibleItemScrollOffset) },
            restore = { PlaylistViewport(it) },
        )
    }
}

@Composable
internal fun rememberPlaylistListState(uri: String?, contentReady: Boolean): LazyListState {
    val viewport = rememberSaveable(uri, saver = PlaylistViewport.Saver) { PlaylistViewport() }
    LaunchedEffect(viewport, contentReady) {
        if (contentReady) viewport.restoreWhenReady()
    }
    return viewport.list
}
