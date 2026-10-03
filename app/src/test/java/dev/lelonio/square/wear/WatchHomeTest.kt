package dev.lelonio.square.wear

import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.data.RecentContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchHomeTest {

    private val liked = CatalogPlaylist("spotify:user:me:collection", "Liked Songs")
    private val gym = CatalogPlaylist("spotify:playlist:gym", "Gym")
    private val chill = CatalogPlaylist("spotify:playlist:chill", "Chill")
    private val road = CatalogPlaylist("spotify:playlist:road", "Road trip")
    private val playlists = listOf(liked, gym, chill, road)

    @Test
    fun pinsLeadThenWhatWasPlayedThenWhatWasOpened() {
        val rows = WatchHome.top(
            pinned = listOf("spotify:playlist:road"),
            recent = listOf(
                RecentContext("spotify:album:x", "An album", "https://i.scdn.co/image/a"),
                RecentContext("spotify:playlist:chill", "Chill"),
            ),
            opened = listOf("spotify:playlist:gym"),
            playlists = playlists,
            limit = 10,
        )
        assertEquals(
            listOf("spotify:playlist:road", "spotify:album:x", "spotify:playlist:chill", "spotify:playlist:gym", "spotify:user:me:collection"),
            rows.map { it.uri },
        )
        assertTrue(rows.first().pinned)
        assertFalse(rows[1].pinned)
    }

    @Test
    fun noRowTwiceAndTheLimitHolds() {
        val rows = WatchHome.top(
            pinned = listOf("spotify:playlist:gym"),
            recent = listOf(RecentContext("spotify:playlist:gym", "Gym")),
            opened = listOf("spotify:playlist:gym", "spotify:playlist:chill"),
            playlists = playlists,
            limit = 2,
        )
        assertEquals(listOf("spotify:playlist:gym", "spotify:playlist:chill"), rows.map { it.uri })
    }

    @Test
    fun aPinTheLibraryDoesNotKnowIsLeftOut() {
        val rows = WatchHome.top(listOf("spotify:playlist:gone"), emptyList(), emptyList(), playlists, limit = 10)
        assertFalse(rows.any { it.uri == "spotify:playlist:gone" })
    }

    @Test
    fun madeForYouIsTheListenersOwnPlaylists() {
        assertTrue(WatchHome.isMadeForYou("spotify:playlist:37i9dQZEVXcJZyENOWUFo7"))
        assertTrue(WatchHome.isMadeForYou("spotify:playlist:37i9dQZF1E39vTG3GurFPW"))
        // Editorial, and the DJ.
        assertFalse(WatchHome.isMadeForYou("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"))
        assertFalse(WatchHome.isMadeForYou("spotify:playlist:37i9dQZF1EYkqdzj48dyYq"))
        assertFalse(WatchHome.isMadeForYou("spotify:album:37i9dQZEVXcJZyENOWUFo7"))
    }

    @Test
    fun discoverWeeklyLeadsTheMadeForYouShelf() {
        val mix = HomeEntry("spotify:playlist:37i9dQZF1E39vTG3GurFPW", "Daily Mix 1", null)
        val weekly = HomeEntry("spotify:playlist:37i9dQZEVXcJZyENOWUFo7", "Discover Weekly", null)
        val shelf = WatchHome.madeForYou(listOf(mix), emptyList(), listOf(weekly, mix), limit = 10)
        assertEquals(listOf(weekly.uri, mix.uri), shelf.map { it.uri })
    }
}
