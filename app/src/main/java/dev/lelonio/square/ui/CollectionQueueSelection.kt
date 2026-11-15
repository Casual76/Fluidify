package dev.lelonio.square.ui

import dev.lelonio.square.data.CatalogTrack

/** Queue the complete source order, independent of the page's search and visual sort. */
internal fun MainViewModel.PlaylistState.collectionQueueTracks(): List<CatalogTrack>? = tracks.takeIf {
    !loading && !loadingMore && error == null && !needsPermission && it.isNotEmpty() &&
        kind in setOf(MainViewModel.DetailKind.ALBUM, MainViewModel.DetailKind.PLAYLIST) &&
        uri?.let { source -> source.startsWith("spotify:album:") || source.startsWith("spotify:playlist:") ||
            source.endsWith(":collection") } == true
}?.toList()
