package dev.lelonio.square.ui.library

import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.data.LocalLibrary
import org.junit.Assert.*
import org.junit.Test

/** Producer regressions prepared, NOT EXECUTED. */
class I08CollectionProjectionTest {
    private val local = CatalogPlaylist(LocalLibrary.CONTEXT_URI, "Local files")
    private val liked = CatalogPlaylist("spotify:user:a:collection", "Liked Songs")

    @Test fun likedIsSelectableWithNoEditablePlaylistAndForeignOrLocalRowsAreExcluded() {
        val rows = listOf(liked, local, liked,
            CatalogPlaylist("spotify:user:b:collection", "Other account"),
            CatalogPlaylist("spotify:playlist:unknown", "Unknown"),
            CatalogPlaylist("spotify:playlist:followed", "Followed", editable = false),
            CatalogPlaylist("spotify:playlist:own", "Own", editable = true))
        assertEquals(listOf(liked.uri, "spotify:playlist:own"), editableCollectionDestinations(rows, "a").map { it.uri })
        assertEquals(listOf(liked), editableCollectionDestinations(listOf(liked), "a"))
        assertTrue(editableCollectionDestinations(listOf(liked), null).isEmpty())
    }

    @Test fun offlineLocalShelfDoesNotRequireASpotifyDownloadAndToggleRetainsArchives() {
        assertEquals(listOf(local), projectLibraryCollections(emptyList(), emptyList(), local))
        assertTrue(projectLibraryCollections(emptyList(), emptyList(), null).isEmpty())
        val archive = CatalogPlaylist("downloads:legacy:old", "Archive")
        val download = CatalogPlaylist("downloads:tracks", "Downloaded")
        val enabled = projectLibraryCollections(listOf(local, download, archive), listOf(archive), local)
        assertEquals(listOf(local, download, archive), enabled)
        val disabled = projectLibraryCollections(enabled, listOf(archive), null)
        assertEquals(listOf(download, archive), disabled)
    }
}
