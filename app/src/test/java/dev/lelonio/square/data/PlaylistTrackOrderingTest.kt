package dev.lelonio.square.data

import org.junit.Assert.*
import org.junit.Test

/** Prepared for central validation; NOT EXECUTED by playlist-ui writer. */
class PlaylistTrackOrderingTest {
    private fun track(name: String, date: String? = null, uri: String = name) =
        CatalogTrack(uri = uri, name = name, artist = "Artist", addedAt = date)

    @Test fun newestAndOldestKeepUnknownDatesLast() {
        val unknown = track("unknown")
        val old = track("old", "2020-01-01T00:00:00Z")
        val new = track("new", "2026-01-01T00:00:00Z")
        val input = listOf(unknown, old, new)
        assertEquals(listOf(new, old, unknown), orderPlaylistTracksByAddedAt(input, false))
        assertEquals(listOf(old, new, unknown), orderPlaylistTracksByAddedAt(input, true))
        assertEquals(listOf(unknown, old, new), input)
    }

    @Test fun duplicateOccurrencesAndTiesStayStableInBothDirections() {
        val first = track("first occurrence", "2020-01-01T00:00:00Z", "same")
        val second = track("second occurrence", "2020-01-01T00:00:00Z", "same")
        val third = track("third occurrence", "2026-01-01T00:00:00Z", "same")
        val unknown1 = track("unknown1", uri = "same")
        val unknown2 = track("unknown2", uri = "same")
        val input = listOf(first, unknown1, second, third, unknown2)
        assertEquals(listOf(third, first, second, unknown1, unknown2), orderPlaylistTracksByAddedAt(input, false))
        assertEquals(listOf(first, second, third, unknown1, unknown2), orderPlaylistTracksByAddedAt(input, true))
    }

    @Test fun allUnknownPreservesOriginalOccurrencesIncludingMalformedDates() {
        val input = listOf(track("a"), track("b", ""), track("c", "not-a-date"))
        assertFalse(hasKnownPlaylistAddedAt(input))
        for (oldest in listOf(false, true)) assertEquals(input, orderPlaylistTracksByAddedAt(input, oldest))
        assertEquals(emptyList<CatalogTrack>(), orderPlaylistTracksByAddedAt(emptyList(), false))
    }

    @Test fun comparesInstantsAcrossOffsetsAndFractionalSeconds() {
        val earlier = track("earlier", "2026-01-01T01:00:00+02:00")
        val later = track("later", "2026-01-01T00:00:00.100Z")
        assertTrue(hasKnownPlaylistAddedAt(listOf(earlier)))
        assertEquals(listOf(later, earlier), orderPlaylistTracksByAddedAt(listOf(earlier, later), false))
    }
}
