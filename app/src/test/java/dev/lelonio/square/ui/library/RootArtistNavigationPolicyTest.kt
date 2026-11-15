package dev.lelonio.square.ui.library

import org.junit.Assert.*
import org.junit.Test

/** Prepared on a copy during review freeze. Root must install with the R17-01 patch. */
class RootArtistNavigationPolicyTest {
    private val artist = "spotify:artist:kept"

    @Test fun aStoredFollowedArtistWithoutWebApiReachesTheLocalCollection() {
        val stored = setOf(artist)
        assertFalse(i09ArtistNeedsWebApi(artist, webApiReady = false, owners = stored))
        assertTrue(i09ShouldOpenStoredCollection(artist, stored, known = stored, artistWebApiReady = false))
    }

    @Test fun anOrphanArtistIsStillLocalAndNoNewOnlineReaderIsRequired() {
        assertFalse(i09ArtistNeedsWebApi(artist, false, setOf(artist)))
        assertTrue(i09ShouldOpenStoredCollection(artist, setOf(artist), emptySet(), artistWebApiReady = false))
        assertTrue(i09ShouldOpenStoredCollection(artist, setOf(artist), emptySet(), artistWebApiReady = true))
    }

    @Test fun aNewArtistStillHasTheActualWebApiPrerequisite() {
        assertTrue(i09ArtistNeedsWebApi(artist, false, emptySet()))
        assertFalse(i09ShouldOpenStoredCollection(artist, emptySet(), emptySet(), artistWebApiReady = false))
        assertFalse(i09ArtistNeedsWebApi(artist, true, emptySet()))
        assertFalse(i09ShouldOpenStoredCollection(artist, setOf(artist), setOf(artist), artistWebApiReady = true))
    }

    @Test fun artistSetupNeverInterceptsAnAlbumOrPlaylist() {
        for (uri in listOf("spotify:album:kept", "spotify:playlist:kept")) {
            assertFalse(i09ArtistNeedsWebApi(uri, false, emptySet()))
            assertTrue(i09ShouldOpenStoredCollection(uri, setOf(uri), emptySet(), artistWebApiReady = false))
            assertFalse(i09ShouldOpenStoredCollection(uri, setOf(uri), setOf(uri), artistWebApiReady = false))
        }
    }
}
