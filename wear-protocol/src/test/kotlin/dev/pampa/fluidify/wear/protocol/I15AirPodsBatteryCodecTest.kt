package dev.pampa.fluidify.wear.protocol

import kotlinx.serialization.Serializable
import org.junit.Assert.*
import org.junit.Test

class I15AirPodsBatteryCodecTest {
    @Serializable private data class OldReader(val seq: Long, val sentAtEpochMs: Long, val isPlaying: Boolean = false)

    @Test fun literalOldPhonePayloadHasNoBatteryAndStillPlays() {
        val old = """{"seq":17,"sentAtEpochMs":100000,"source":"PHONE","isPlaying":true}"""
        val decoded = WearCodec.decode(PlaybackSnapshot.serializer(), old.encodeToByteArray())
        assertTrue(decoded.isPlaying)
        assertNull(decoded.airPodsBattery)
    }

    @Test fun oldWatchReaderIgnoresNewOptionalTelemetry() {
        val snapshot = PlaybackSnapshot(18, 100_000, source = PlaybackSource.PHONE, isPlaying = true,
            airPodsBattery = AirPodsBatterySnapshot("session", "connection", right = AirPodsBatteryLevel(42, 99_000)))
        val encoded = WearCodec.encode(PlaybackSnapshot.serializer(), snapshot)
        assertEquals(OldReader(18, 100_000, true), WearCodec.decode(OldReader.serializer(), encoded))
        assertEquals(snapshot, WearCodec.decode(PlaybackSnapshot.serializer(), encoded))
    }

    @Test fun oneComponentNeverFillsTheOthersAndTrueZeroSurvives() {
        val literal = """{"seq":1,"sentAtEpochMs":100000,"airPodsBattery":{"accountSession":"a","sourceSession":"s","right":{"percent":0,"observedAtEpochMs":99000}}}"""
        val battery = WearCodec.decode(PlaybackSnapshot.serializer(), literal.encodeToByteArray()).airPodsBattery!!
        assertEquals(0, battery.right!!.percent)
        assertNull(battery.left)
        assertNull(battery.case)
    }

    @Test fun absentFeatureDoesNotChangeOldMinimalWirePayload() {
        assertEquals("""{"seq":1,"sentAtEpochMs":2}""",
            WearCodec.encode(PlaybackSnapshot.serializer(), PlaybackSnapshot(1, 2)).decodeToString())
    }
}
