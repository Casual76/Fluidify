package dev.pampa.fluidify.wear.library

import dev.pampa.fluidify.wear.protocol.LibraryItem
import dev.pampa.fluidify.wear.protocol.LibraryKind
import dev.pampa.fluidify.wear.ui.browse.canTake
import org.junit.Assert.*
import org.junit.Test

class EditablePlaylistsTest {
    @Test fun onlyConfirmedEditableSpotifyPlaylistsAreOffered() {
        val playlist = LibraryItem("spotify:playlist:a", "A", kind = LibraryKind.PLAYLIST, editable = true)
        assertTrue(canTake(playlist))
        assertFalse(canTake(playlist.copy(editable = false)))
        assertFalse(canTake(playlist.copy(editable = null)))
        assertFalse(canTake(playlist.copy(kind = LibraryKind.ALBUM)))
        assertFalse(canTake(playlist.copy(uri = "local:playlist:a")))
    }
}
