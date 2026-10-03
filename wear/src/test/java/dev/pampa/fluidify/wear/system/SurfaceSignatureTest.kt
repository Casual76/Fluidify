package dev.pampa.fluidify.wear.system

import dev.pampa.fluidify.wear.protocol.DeviceInfo
import dev.pampa.fluidify.wear.protocol.DeviceKind
import dev.pampa.fluidify.wear.protocol.PlaybackSnapshot
import dev.pampa.fluidify.wear.protocol.TrackInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The tile and the complication redraw for what they show, and for nothing else. */
class SurfaceSignatureTest {

    private val base = PlaybackSnapshot(
        seq = 1,
        sentAtEpochMs = 1_000,
        track = TrackInfo(uri = "spotify:track:a", title = "A", artist = "B", artKey = "k1", durationMs = 200_000),
        positionMs = 10_000,
        isPlaying = true,
        playWhenReady = true,
        liked = false,
        device = DeviceInfo("phone", "Phone", DeviceKind.PHONE, volume = 0.5f),
    )

    private fun signature(snapshot: PlaybackSnapshot?) = SystemSurfaces.signatureOf(snapshot)

    @Test
    fun seeksVolumeAndNewSequenceNumbersRedrawNothing() {
        val moved = base.copy(seq = 9, sentAtEpochMs = 9_000, positionMs = 120_000, device = base.device!!.copy(volume = 0.9f), shuffle = true)
        assertEquals(signature(base), signature(moved))
    }

    @Test
    fun songPlayStateHeartAndCoverRedraw() {
        assertNotEquals(signature(base), signature(base.copy(track = base.track!!.copy(uri = "spotify:track:b"))))
        assertNotEquals(signature(base), signature(base.copy(isPlaying = false, playWhenReady = false)))
        assertNotEquals(signature(base), signature(base.copy(liked = true)))
        assertNotEquals(signature(base), signature(base.copy(track = base.track!!.copy(artKey = "k2"))))
    }

    @Test
    fun nothingPlayingIsOneState() {
        assertEquals(signature(null), signature(base.copy(track = null)))
    }
}
