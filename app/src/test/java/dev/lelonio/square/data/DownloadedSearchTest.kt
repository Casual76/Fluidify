package dev.lelonio.square.data

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

/** Prepared regression tests. NOT EXECUTED by the search-ui writer. */
class DownloadedSearchTest {
    @Test fun completedOnlyAndMissingMetadataSkipped() {
        val tracks = listOf(track("done"), track("pending")).associateBy { it.uri }
        val lookedUp = mutableListOf<String>()
        val found = findDownloadedTracks("song", linkedSetOf("done", "missing")) {
            lookedUp += it
            tracks[it]
        }
        assertEquals(listOf("done"), found.map { it.uri })
        assertEquals(listOf("done", "missing"), lookedUp)
    }

    @Test fun duplicateMetadataIsReturnedOnceAndOrderIsStable() {
        val first = track("first")
        val second = track("second")
        val found = findDownloadedTracks("song", linkedSetOf("first", "alias", "second")) {
            if (it == "second") second else first
        }
        assertEquals(listOf(first, second), found)
    }

    @Test fun mismatchedMetadataCannotIntroduceAnUncompletedTrack() {
        assertTrue(findDownloadedTracks("song", setOf("done")) { track("pending") }.isEmpty())
    }

    @Test fun newCompletedSnapshotRemovesDeletedAudio() {
        val metadata = listOf(track("one"), track("two")).associateBy { it.uri }
        assertEquals(2, findDownloadedTracks("song", metadata.keys, metadata::get).size)
        assertEquals(listOf("two"), findDownloadedTracks("song", setOf("two"), metadata::get).map { it.uri })
    }

    @Test fun blankQueryLeavesHistoryToTheHostWithoutReadingMetadata() {
        assertTrue(findDownloadedTracks(" \n ", setOf("done")) { error("unexpected lookup") }.isEmpty())
    }

    @Test fun matchesTitleArtistAndAlbumWithTrimCaseAndDecomposedAccents() {
        val track = CatalogTrack("t", "CAFFÈ", "Beyonce\u0301", album = "Été")
        listOf(" caffe ", "BEYONCE", "ete", "caffè").forEach {
            assertTrue("query=$it", matchesTrackText(track, it))
        }
        assertFalse(matchesTrackText(track, "lyrics only"))
        assertTrue(matchesTrackText(track, "  "))
    }

    @Test fun matchingDoesNotDependOnTurkishDefaultLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertTrue(matchesTrackText(CatalogTrack("t", "INDIGO", "Artist"), "indigo"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test fun nonLatinLettersAndDistinctiveMarksArePreserved() {
        val track = CatalogTrack("t", "東京 が", "किताब", album = "Музыка")
        listOf("東京", "か\u3099", "किताब", "МУЗЫКА").forEach {
            assertTrue("query=$it", matchesTrackText(track, it))
        }
        assertFalse(matchesTrackText(CatalogTrack("t", "が", ""), "か"))
        assertFalse(matchesTrackText(CatalogTrack("t", "किताब", ""), "कताब"))
    }

    private fun track(uri: String) = CatalogTrack(uri, "Song $uri", "Artist")
}
