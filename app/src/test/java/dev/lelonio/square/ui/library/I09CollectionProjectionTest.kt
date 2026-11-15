package dev.lelonio.square.ui.library

import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.data.DownloadStore
import dev.lelonio.square.data.OwnerState
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

/** Prepared only, NOT EXECUTED. All fixtures are synthetic account-local metadata. */
class I09CollectionProjectionTest {
    @Test fun orphanOwnersAndLikedAliasRemainReachableAfterUnfollowAndUnsave() {
        val album = "spotify:album:orphan"
        val artist = "spotify:artist:orphan"
        val playlist = "spotify:playlist:orphan"
        val archived = "downloads:legacy:old"
        val owners = setOf(album, artist, playlist, archived, DownloadStore.LIKED, DownloadStore.SINGLES)
        val rows = i09DownloadOwnerCollections(owners, owners.associateWith {
            DownloadStore.OwnerLabel("Name $it", "cover:$it")
        }, "Fallback")
        val singles = CatalogPlaylist(DownloadStore.SINGLES, "Songs")
        val shown = i09DownloadedCollections(listOf(CatalogPlaylist("spotify:playlist:unowned", "Other")),
            owners, rows, singles)
        assertEquals(owners, shown.map { it.uri }.toSet())
        assertEquals(owners.size, shown.size)
        assertEquals(album, i09FilterCollectionsByName(shown, "Name $album").single().uri)
        assertTrue(i09ShouldOpenStoredCollection(album, owners, emptySet()))
        assertTrue(i09ShouldOpenStoredCollection(artist, owners, emptySet()))
        assertTrue(i09ShouldOpenStoredCollection(playlist, owners, emptySet()))
        // Special shelves keep their existing dedicated dispatch.
        assertFalse(i09ShouldOpenStoredCollection(DownloadStore.SINGLES, owners, emptySet()))
        assertFalse(i09ShouldOpenStoredCollection(DownloadStore.LIKED, owners, emptySet()))
    }

    @Test fun currentMetadataDeduplicatesWithoutChangingIdentityAndRemovedOwnersDisappear() {
        val owned = CatalogPlaylist("spotify:playlist:p", "Current", "new", editable = true)
        val stale = owned.copy(name = "Old", artworkUrl = "old", editable = null)
        val owners = setOf(owned.uri)
        assertEquals(listOf(owned), i09DownloadedCollections(listOf(owned), owners, listOf(stale, stale), null))
        assertFalse(i09ShouldOpenStoredCollection(owned.uri, owners, setOf(owned.uri)))
        assertTrue(i09DownloadedCollections(listOf(owned), emptySet(), listOf(stale), null).isEmpty())
        assertTrue(i09DownloadOwnerCollections(emptySet(), mapOf(owned.uri to DownloadStore.OwnerLabel("Old")), "Fallback").isEmpty())
        assertEquals("Fallback", i09DownloadOwnerCollections(owners, emptyMap(), "Fallback").single().name)
    }

    @Test fun localSearchKeepsOrderCapabilitiesAndLikedCollection() {
        val liked = CatalogPlaylist("spotify:user:a:collection", "Preferiti", editable = null)
        val created = CatalogPlaylist("spotify:playlist:new", "Caffè", editable = true)
        val readonly = CatalogPlaylist("downloads:legacy:old", "Caffè archivio", editable = false)
        val rows = listOf(liked, created, readonly)
        assertSame(rows, i09FilterCollectionsByName(rows, "  "))
        assertEquals(listOf(created, readonly), i09FilterCollectionsByName(rows, " CAFFE "))
        assertSame(liked, i09FilterCollectionsByName(rows, "preferiti").single())
        assertTrue(i09FilterCollectionsByName(rows, "no match").isEmpty())
        assertEquals(rows, i09FilterCollectionsByName(rows, ""))
    }

    @Test fun searchUsesNameOnlyAndLiteralPunctuationRegardlessOfTurkishLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val row = CatalogPlaylist("spotify:playlist:hidden", "INDIE 100%_ [live]", owner = "secret")
            assertEquals(listOf(row), i09FilterCollectionsByName(listOf(row), "indie 100%_"))
            assertTrue(i09FilterCollectionsByName(listOf(row), "secret").isEmpty())
            assertTrue(i09FilterCollectionsByName(listOf(row), "spotify:").isEmpty())
            assertFalse(i09CollectionNameMatches("Hindi कि", "Hindi की"))
        } finally { Locale.setDefault(original) }
    }

    @Test fun partialAndIncompleteSourceNeverClaimCompleteEvenAtEqualCounts() {
        assertEquals(I09DownloadPhase.PARTIAL, i09DownloadPhase(OwnerState.Partial(3, 3), false, false))
        assertEquals(I09DownloadPhase.READING, i09DownloadPhase(OwnerState.Partial(3, 3), true, true))
        assertEquals(I09DownloadPhase.INCOMPLETE, i09DownloadPhase(OwnerState.Partial(3, 3), true, false))
        assertEquals(I09DownloadPhase.INCOMPLETE, i09DownloadPhase(OwnerState.Complete, true, false))
        assertEquals(I09DownloadPhase.COMPLETE, i09DownloadPhase(OwnerState.Complete, false, false))
        assertEquals(I09DownloadPhase.PENDING, i09DownloadPhase(OwnerState.Running(.3f, 1, 3), false, false))
        assertEquals(I09DownloadPhase.PARTIAL, i09DownloadPhase(OwnerState.None, false, false, true))
    }

    @Test fun failureSummaryExcludesOtherOwnersCompletedFilesAndDuplicateOccurrences() {
        val failure = DownloadStore.Failure("synthetic failure", DownloadStore.MAX_ATTEMPTS, 123)
        val failures = mapOf("a" to failure, "b" to failure.copy(attempts = 1), "c" to failure)
        assertEquals(I09DownloadFailures(1, false), i09DownloadFailures(listOf("a", "a", "b"), failures, setOf("b"), true))
        assertEquals(I09DownloadFailures(2, true), i09DownloadFailures(listOf("a", "b"), failures, emptySet(), true))
        assertEquals(I09DownloadFailures(2, false), i09DownloadFailures(listOf("a", "b"), failures, emptySet(), false))
        assertEquals(I09DownloadFailures(0, false), i09DownloadFailures(emptyList(), failures, emptySet(), true))
    }
}
