package dev.lelonio.square.airpods

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import org.junit.Assert.*
import org.junit.Test

class DebugInformationIngressTest {
    private fun information() = byteArrayOf(4,0,4,0,0x1d,0,1,0) +
        listOf("ignored", "PRIVATE_NAME_MARKER", "A3063", "Apple Inc.", "SERIAL_MARKER",
            "firmware", "v2", "hw", "updater", "left", "right", "v3")
            .joinToString("\u0000", postfix = "\u0000").toByteArray()
    private fun captured(record: ByteArray): ByteArray = DataInputStream(ByteArrayInputStream(record)).use {
        assertEquals(0x46495031, it.readInt())
        it.readLong(); it.readLong(); repeat(3) { _ -> it.readInt() }
        it.readLong(); it.readInt(); it.readInt()
        ByteArray(it.readInt()).also(it::readFully)
    }
    @Test fun retainsOneExactCandidatePrivatelyWithoutChangingDecode() {
        val rows = mutableListOf<QualificationEvent>()
        val hook = DebugInformationIngress(AirPodsQualificationObserver { rows += it }, { 100L })
        val packet = information()
        val before = packet.clone()
        val decoded = AacpCodec.decodeSdu(packet)
        hook.observe(7, packet)
        hook.observe(7, packet + byteArrayOf(0))
        assertArrayEquals(before, packet)
        assertEquals(decoded, AacpCodec.decodeSdu(packet))
        assertArrayEquals(packet, captured(hook.drainPrivateRecord()))
        assertTrue(rows.all { it.flags in 30..32 && it.values.size <= 9 })
        assertFalse(rows.toString().contains("PRIVATE_NAME_MARKER"))
        assertFalse(rows.toString().contains("SERIAL_MARKER"))
    }
    @Test fun followsKnownFixedBoundaryButNeverScansAnUnknownPayload() {
        val hook = DebugInformationIngress(AirPodsQualificationObserver.NONE, { 100L })
        hook.observe(1, AacpCodec.mode(ListeningMode.ANC) + information())
        assertArrayEquals(information(), captured(hook.drainPrivateRecord()))
        val unknown = DebugInformationIngress(AirPodsQualificationObserver.NONE, { 100L })
        unknown.observe(1, byteArrayOf(4,0,4,0,0x7f,0) + information())
        assertEquals(0, captured(unknown.drainPrivateRecord()).size)
    }
    @Test fun repeatedEnvelopeIsCapturedButStillRejectedByUnchangedCodec() {
        val packet = byteArrayOf(4,0,4,0) + information()
        assertTrue(AacpCodec.decodeSdu(packet).all { it !is AacpEvent.Information })
        val hook = DebugInformationIngress(AirPodsQualificationObserver.NONE, { 100L })
        hook.observe(1, packet)
        assertArrayEquals(packet, captured(hook.drainPrivateRecord()))
    }
    @Test fun disarmAndBudgetsDoNotRetainLaterSdus() {
        val stopped = DebugInformationIngress(AirPodsQualificationObserver.NONE, { 100L })
        stopped.disarm(); stopped.observe(1, information())
        assertEquals(0, captured(stopped.drainPrivateRecord()).size)
        val limited = DebugInformationIngress(AirPodsQualificationObserver.NONE, { 100L }, maxSdus = 1)
        limited.observe(1, AacpCodec.mode(ListeningMode.ANC)); limited.observe(1, information())
        assertEquals(0, captured(limited.drainPrivateRecord()).size)
        var time = 100L
        val expired = DebugInformationIngress(AirPodsQualificationObserver.NONE, { time })
        time += 60_000
        expired.observe(1, information())
        assertEquals(0, captured(expired.drainPrivateRecord()).size)
    }
    @Test fun oversizedCandidateIsCountedAndNeverTruncatedIntoAReceipt() {
        val rows = mutableListOf<QualificationEvent>()
        val hook = DebugInformationIngress(AirPodsQualificationObserver { rows += it }, { 100L }, maxBytes = 8)
        hook.observe(1, information())
        assertEquals(0, captured(hook.drainPrivateRecord()).size)
        assertEquals(-1, rows.first { it.flags == 30 }.values.last())
    }
    @Test fun observerFailureDoesNotEscapeReadObservation() {
        val hook = DebugInformationIngress(AirPodsQualificationObserver { error("observer failed") }, { 100L })
        hook.observe(1, information())
        assertArrayEquals(information(), captured(hook.drainPrivateRecord()))
    }
}
