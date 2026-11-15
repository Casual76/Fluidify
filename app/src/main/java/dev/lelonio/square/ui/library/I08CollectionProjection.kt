package dev.lelonio.square.ui.library

import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.data.LocalLibrary

/** No transport and no new consent: these are destinations already confirmed for this account. */
internal fun editableCollectionDestinations(rows: List<CatalogPlaylist>, accountId: String?): List<CatalogPlaylist> {
    val liked = accountId?.let { "spotify:user:$it:collection" }
    return rows.filter { row ->
        row.uri == liked || (row.uri.startsWith("spotify:playlist:") && row.editable == true)
    }.distinctBy { it.uri }
}

/** The local shelf belongs in both online and offline libraries, even with no Spotify downloads. */
internal fun projectLibraryCollections(
    rows: List<CatalogPlaylist>,
    archived: List<CatalogPlaylist>,
    localShelf: CatalogPlaylist?,
): List<CatalogPlaylist> = listOfNotNull(localShelf) + rows.filterNot {
    it.uri == LocalLibrary.CONTEXT_URI || it.uri.startsWith("downloads:legacy:")
} + archived
