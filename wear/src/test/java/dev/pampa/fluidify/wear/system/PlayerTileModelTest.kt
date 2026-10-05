package dev.pampa.fluidify.wear.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerTileModelTest {

    private val playing = PlayerTileModel(
        title = "Song",
        artist = "Artist",
        isPlaying = true,
        liked = true,
        trackUri = "spotify:track:1",
        coverKey = "a",
        positionMs = 60_000,
        durationMs = 200_000,
        sampledAtEpochMs = 1_000_000,
        next = PlayerTileModel.Next("Next song", "Other", "spotify:track:2", "b", 180_000),
    )

    @Test
    fun nextShowsTheNextSongStraightAway() {
        val after = playing.afterClick(TileActions.NEXT, nowMs = 1_005_000)
        assertEquals("Next song", after.title)
        assertEquals("spotify:track:2", after.trackUri)
        assertEquals("b", after.coverKey)
        assertEquals(0L, after.positionMs)
        assertEquals(180_000L, after.durationMs)
        assertNull(after.liked)
    }

    @Test
    fun nextWithoutKnowingWhatIsNextStillRestartsTheRing() {
        val after = playing.copy(next = null).afterClick(TileActions.NEXT, nowMs = 1_005_000)
        assertEquals("Song", after.title)
        assertEquals(0L, after.positionMs)
    }

    @Test
    fun toggleKeepsThePositionItHadReached() {
        val after = playing.afterClick(TileActions.TOGGLE, nowMs = 1_010_000)
        assertFalse(after.isPlaying)
        assertEquals(70_000L, after.positionMs)
        val resumed = after.afterClick(TileActions.TOGGLE, nowMs = 1_020_000)
        assertTrue(resumed.isPlaying)
        assertEquals(70_000L, resumed.positionMs)
    }

    @Test
    fun theHeartFollowsALike() {
        assertEquals(true, playing.copy(liked = false).afterClick(TileActions.LIKE).liked)
    }

    @Test
    fun aPressIsNamedByItsLayout() {
        val first = TileActions.id(TileActions.NEXT, layout = 1)
        val second = TileActions.id(TileActions.NEXT, layout = 2)
        assertEquals(TileActions.NEXT, TileActions.nameOf(first))
        assertTrue(first != second)
        // Taking a like back is not a tile press: it asks first, in the app.
        assertEquals(null, TileActions.nameOf("unlike@3"))
        assertEquals(null, TileActions.nameOf("open"))
        assertEquals(null, TileActions.nameOf(null))
    }

    @Test
    fun everyLayoutNamesItsButtonsAfresh() {
        // The first layout of a process is the one that went wrong: its mark was still 0 when the
        // buttons were named, so every layout after it said "next@0", and the history of handled
        // presses refused all of them.
        val first = TileButtonIds()
        val second = TileButtonIds()
        for (name in TileActions.ALL) {
            assertNotEquals("$name@0", first.of(name))
            assertNotEquals(first.of(name), second.of(name))
            assertEquals(name, TileActions.nameOf(first.of(name)))
        }
        assertEquals(first.of(TileActions.NEXT), first.of(TileActions.NEXT))
    }

    @Test
    fun aRepeatedPressIsRefusedAndAPressInANewLayoutIsNot() {
        val taps = TileTapHistory()
        val pressed = TileButtonIds().of(TileActions.NEXT)
        assertTrue(taps.claim(pressed))
        assertFalse(taps.claim(pressed))
        assertTrue(taps.claim(TileButtonIds().of(TileActions.NEXT)))
    }

    @Test
    fun onlySongsHaveAHeart() {
        assertTrue(playing.copy(trackUri = "spotify:track:1").likeable)
        assertFalse(playing.copy(trackUri = "spotify:episode:1").likeable)
        assertFalse(playing.copy(trackUri = "spotify:local:a:b:c:1").likeable)
    }

    @Test
    fun progressIsSafe() {
        assertEquals(0.3f, playing.progress, 1e-4f)
        assertEquals(0f, playing.copy(durationMs = 0).progress, 0f)
    }
}
