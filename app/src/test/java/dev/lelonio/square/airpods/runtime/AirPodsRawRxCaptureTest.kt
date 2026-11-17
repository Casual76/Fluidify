package dev.lelonio.square.airpods.runtime

import org.junit.Assert.*
import org.junit.Test

class AirPodsRawRxCaptureTest {
    @Test fun optInIsBoundedRelativeAndCopiesPayload() {
        var now = 100L
        val buffer = AirPodsRawRxBuffer({ now }, 2)
        val payload = byteArrayOf(1, 2, 0, 0)
        buffer.record(9, 0x0d, payload)
        payload[0] = 99
        assertTrue(buffer.export().contains("bytes=01020000"))
        now += 20
        buffer.record(6, null, byteArrayOf(1, 0))
        buffer.record(0x4b, null, byteArrayOf(2, 0, 0, 1))
        val text = buffer.export()
        assertTrue(text.contains("dropped=1"))
        assertTrue(text.contains("20 RX op=6 id=- len=2 bytes=0100"))
        assertFalse(text.contains("op=9"))
    }
    @Test fun txUsesTheSameRingAndNeverRetainsNamesOrOpaquePayloads() {
        var now = 20L
        val buffer = AirPodsRawRxBuffer({ now }, 10)
        AirPodsRxCapture.install(buffer)
        try {
            dev.lelonio.square.airpods.AacpCodec.captureTx(dev.lelonio.square.airpods.AacpCodec.conversation(true))
            now += 5
            buffer.record(9, 0x28, byteArrayOf(2, 0, 0, 0))
            dev.lelonio.square.airpods.AacpCodec.captureTx(dev.lelonio.square.airpods.AacpCodec.rename("PRIVATE"))
            buffer.recordTx(0x17, null, "PRIVATE".toByteArray())
            buffer.recordTx(9, 0xff, "PRIVATE".toByteArray())
            val text = buffer.export()
            assertTrue(text.contains("0 TX op=9 id=28 len=4 bytes=01000000"))
            assertTrue(text.contains("5 RX op=9 id=28 len=4 bytes=02000000"))
            assertEquals(3, text.lineSequence().count { it.contains("bytes=omitted") })
            assertFalse(text.contains("50524956415445"))
        } finally { AirPodsRxCapture.remove(buffer) }
    }
    @Test fun longPressNoteRequiresValidObservedStemConfigAndTxDoesNotCountAsRx() {
        val buffer = AirPodsRawRxBuffer({ 0 })
        buffer.recordTx(9, 0x39, byteArrayOf(8, 0, 0, 0))
        buffer.record(0x19, null, byteArrayOf(8, 1))
        buffer.record(9, 0x39, byteArrayOf(8, 1, 0, 0)) // malformed, not observation
        buffer.record(0x19, null, byteArrayOf(8, 2))
        buffer.record(9, 0x39, byteArrayOf(8, 0, 0, 0))
        buffer.record(0x19, null, byteArrayOf(8, 1))
        assertEquals(2, buffer.export().lineSequence().count { it.contains("LONG_PRESS_WITHOUT_OBSERVED_STEM_CONFIG") })
    }
    @Test fun identityNameUnknownAndOversizedPayloadsNeverEscape() {
        val buffer = AirPodsRawRxBuffer({ 0 })
        val secret = "IDENTITY-NAME-ADDRESS".toByteArray()
        for (op in listOf(0x1d, 0x1a, 0xff)) buffer.record(op, null, secret)
        buffer.record(9, 0xff, secret)
        buffer.record(9, 0x0d, ByteArray(1000) { 65 })
        assertEquals(5, buffer.export().lineSequence().count { it.contains("bytes=omitted") })
        assertFalse(buffer.export().contains("4944454e"))
    }
    @Test fun removingOldSessionCannotDisableNewOne() {
        val old = AirPodsRawRxBuffer({ 0 }); val next = AirPodsRawRxBuffer({ 0 })
        AirPodsRxCapture.install(old)
        AirPodsRxCapture.install(next)
        AirPodsRxCapture.remove(old)
        AirPodsRxCapture.record(6, null, byteArrayOf(1, 0))
        AirPodsRxCapture.remove(next)
        AirPodsRxCapture.record(6, null, byteArrayOf(0, 0))
        assertEquals(1, next.export().lineSequence().count { it.contains("op=6") })
        assertFalse(old.export().contains("op=6"))
    }
}
