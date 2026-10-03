package dev.pampa.fluidify.wear.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun theHeartFollowsThePress() {
        assertEquals(false, playing.afterClick(TileActions.UNLIKE).liked)
        assertEquals(true, playing.copy(liked = false).afterClick(TileActions.LIKE).liked)
    }

    @Test
    fun progressIsSafe() {
        assertEquals(0.3f, playing.progress, 1e-4f)
        assertEquals(0f, playing.copy(durationMs = 0).progress, 0f)
    }
}
