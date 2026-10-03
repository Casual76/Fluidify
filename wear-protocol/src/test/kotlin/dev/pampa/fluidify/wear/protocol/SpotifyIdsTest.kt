package dev.pampa.fluidify.wear.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifyIdsTest {

    @Test
    fun knownTrackIdConverts() {
        // librespot's own test vector for SpotifyId (base62 ↔ base16).
        assertEquals("b39fe8081e1f4c54be38e8d6f9f12bb9", SpotifyIds.base62ToHex("5sWHDYs0csV6RS48xBl0tH"))
        assertEquals("b39fe8081e1f4c54be38e8d6f9f12bb9", SpotifyIds.trackHex("spotify:track:5sWHDYs0csV6RS48xBl0tH"))
    }

    @Test
    fun smallIdsArePaddedToThirtyTwoDigits() {
        assertEquals("0".repeat(32), SpotifyIds.base62ToHex("0".repeat(22)))
    }

    @Test
    fun notATrackOrNotAnId() {
        assertNull(SpotifyIds.trackHex("spotify:album:5sWHDYs0csV6RS48xBl0tH"))
        assertNull(SpotifyIds.base62ToHex("tooshort"))
        assertNull(SpotifyIds.base62ToHex("5sWHDYs0csV6RS48xBl0t!"))
    }
}
