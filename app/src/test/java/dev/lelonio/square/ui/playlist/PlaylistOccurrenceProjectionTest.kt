package dev.lelonio.square.ui.playlist

import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.ui.library.TrackSort
import dev.lelonio.square.ui.library.playlistVisibleOccurrences
import org.junit.Assert.assertEquals
import org.junit.Test

/** Prepared only. Exercises the projection actually used by PlaylistScreen's row-menu binding. */
class PlaylistOccurrenceProjectionTest {
    private val duplicate = CatalogTrack("spotify:track:t", "Caffè", "Artist")
    private val other = CatalogTrack("spotify:track:u", "Other", "Artist")

    @Test fun reversedFilteredEqualDuplicatesStillPointAtTheirOwnSourcePositions() {
        val rows = playlistVisibleOccurrences(listOf(duplicate, other, duplicate), "caffe", TrackSort.TITLE, true)
        assertEquals(listOf(2, 0), rows.map { it.index })
        assertEquals(listOf(duplicate, duplicate), rows.map { it.value })
    }

    @Test fun datesBelongToOccurrencesAndUnknownDatesStayLastInBothDirections() {
        val old = duplicate.copy(addedAt = "2020-01-01T00:00:00Z")
        val recent = duplicate.copy(addedAt = "2026-01-01T00:00:00Z")
        val tracks = listOf(duplicate, old, recent, duplicate.copy(addedAt = "invalid"))
        assertEquals(listOf(2, 1, 0, 3), playlistVisibleOccurrences(tracks, "", TrackSort.ADDED, false).map { it.index })
        assertEquals(listOf(1, 2, 0, 3), playlistVisibleOccurrences(tracks, "", TrackSort.ADDED, true).map { it.index })
    }

    @Test fun equalAddedDatesKeepOriginalOccurrenceOrder() {
        val dated = duplicate.copy(addedAt = "2026-01-01T00:00:00Z")
        for (descending in listOf(false, true)) {
            assertEquals(listOf(0, 2, 1),
                playlistVisibleOccurrences(listOf(dated, other, dated), "", TrackSort.ADDED, descending).map { it.index })
        }
    }

    @Test fun originalOrderAndPlaybackProjectionKeepAllOccurrences() {
        val tracks = listOf(duplicate, other, duplicate)
        assertEquals(tracks, playlistVisibleOccurrences(tracks, "", TrackSort.ORIGINAL, false).map { it.value })
        assertEquals(listOf(0, 1, 2), playlistVisibleOccurrences(tracks, "", TrackSort.ORIGINAL, false).map { it.index })
    }
}
