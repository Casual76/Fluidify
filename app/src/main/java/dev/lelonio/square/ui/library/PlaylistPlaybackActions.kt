package dev.lelonio.square.ui.library

import dev.lelonio.square.data.CatalogTrack

/** The playlist page's playback connection, shared by its rows and primary control. */
internal class PlaylistPlaybackActions(
    private val isCurrentContext: Boolean,
    private val start: (List<CatalogTrack>, Int, Boolean) -> Unit,
    private val togglePlayback: () -> Unit,
) {
    fun select(tracks: List<CatalogTrack>, index: Int, asContext: Boolean) {
        start(tracks, index, asContext)
    }

    fun primary(tracks: List<CatalogTrack>, asContext: Boolean) {
        if (isCurrentContext) togglePlayback() else select(tracks, 0, asContext)
    }
}
