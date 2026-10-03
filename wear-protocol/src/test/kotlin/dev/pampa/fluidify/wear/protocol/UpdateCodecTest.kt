package dev.pampa.fluidify.wear.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateCodecTest {

    @Test
    fun offerRoundTrips() {
        val offer = UpdateOffer(versionName = "1.5.0", versionCode = 8, sizeBytes = 4_747_461, sha256 = "ab12", requestedByUser = true)
        assertEquals(offer, WearCodec.decode(UpdateOffer.serializer(), WearCodec.encode(UpdateOffer.serializer(), offer)))
    }

    @Test
    fun statusRoundTripsEveryPhase() {
        UpdatePhase.entries.forEach { phase ->
            val status = UpdateStatus(phase, "1.5.0", progress = 0.5f, reason = "x")
            assertEquals(status, WearCodec.decode(UpdateStatus.serializer(), WearCodec.encode(UpdateStatus.serializer(), status)))
        }
    }

    @Test
    fun updatePathsCarryNoProtocolVersion() {
        // A watch any number of majors behind must still hear the offer that brings it up to date.
        listOf(WearPaths.HELLO, WearPaths.UPDATE_OFFER, WearPaths.UPDATE_STATUS, WearPaths.UPDATE_APK).forEach { path ->
            assert(!path.contains("/${ProtocolVersion.MAJOR}/")) { path }
        }
    }
}
