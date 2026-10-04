package dev.pampa.fluidify.wear.downloads

import dev.pampa.fluidify.wear.protocol.ContextPage
import dev.pampa.fluidify.wear.protocol.LibraryItem
import dev.pampa.fluidify.wear.protocol.LibraryKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CompleteTrackListTest {
    private fun page(total: Int, vararg ids: String) = ContextPage(
        uri = "spotify:playlist:p", title = "Playlist", total = total,
        tracks = ids.map { LibraryItem("spotify:track:$it", it, kind = LibraryKind.TRACK) },
    )
    @Test fun interruptedSecondPageDoesNotReplaceOfflineTracks() = runBlocking {
        assertNull(CompleteTrackList.read(100, 2) { offset, _ -> if (offset == 0) page(3, "a", "b") else null })
    }
    @Test fun completeListKeepsDuplicatesAndEmptyListIsAValidEdit() = runBlocking {
        assertEquals(listOf("spotify:track:a", "spotify:track:a", "spotify:track:b"),
            CompleteTrackList.read(100, 2) { offset, _ -> if (offset == 0) page(3, "a", "a") else page(3, "b") }!!.second)
        assertEquals(emptyList<String>(), CompleteTrackList.read(100, 2) { _, _ -> page(0) }!!.second)
    }
    @Test fun truncatedOrChangingPlaylistIsNotComplete() = runBlocking {
        assertNull(CompleteTrackList.read(2, 2) { _, _ -> page(3, "a", "b") })
        assertNull(CompleteTrackList.read(100, 2) { offset, _ -> if (offset == 0) page(3, "a", "b") else page(4, "c") })
        assertNull(CompleteTrackList.read(100, 2) { _, _ -> page(3) })
    }
}
