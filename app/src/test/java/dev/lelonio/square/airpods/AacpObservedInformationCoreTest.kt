// SPDX-License-Identifier: GPL-3.0-or-later
// PROPOSED, NOT EXECUTED. All fields/trailer bytes below are fully synthetic.
// They reproduce the observed structural category, never the private receipt content.
package dev.lelonio.square.airpods

import org.junit.Assert.*
import org.junit.Test

class AacpObservedInformationCoreTest {
    private fun fields() = mutableListOf("ignored", "Synthetic buds", "A3063", "Apple Inc.",
        "synthetic-device", "synthetic-firmware", "synthetic-v2", "synthetic-hw",
        "synthetic-updater", "synthetic-left", "synthetic-right", "synthetic-v3")
    private fun packet(fields: List<String> = fields(), trailer: ByteArray = byteArrayOf()) =
        byteArrayOf(4,0,4,0,0x1d,0,1,0) +
            fields.joinToString("\u0000", postfix = "\u0000").toByteArray(Charsets.UTF_8) + trailer
    private fun opaque() = byteArrayOf(0xff.toByte(),0xfe.toByte(),0,0x80.toByte(),7,0,11)
    private fun information(bytes: ByteArray) = AacpCodec.decodeSdu(bytes).single() as AacpEvent.Information

    @Test fun completeCoreSurvivesOpaqueExtensionWithoutChangingKnownFields() {
        val expected = information(packet()).identity
        for (trailer in listOf(byteArrayOf(), byteArrayOf(0,0), byteArrayOf(7,0,8), opaque())) {
            assertEquals(expected, information(packet(trailer = trailer)).identity)
        }
    }
    @Test fun optionalEmptyFieldsStayPositionalWithOpaqueExtension() {
        val f = fields().also { it[6] = "" }
        val id = information(packet(f, opaque())).identity
        assertEquals("", id.version2)
        assertEquals("synthetic-hw", id.hardwareRevision)
        assertEquals("synthetic-v3", id.version3)
    }
    @Test fun unknownModelNeverAcquiresModelCapabilitiesFromExtension() {
        val f = fields().also { it[2] = "A9999" }
        val id = information(packet(f, packet())).identity
        assertFalse(id.recognized)
        assertTrue(id.modelDeclared.isEmpty())
    }
    @Test fun incompleteCoreNeverBecomesInformation() {
        val core = packet()
        for (size in 0 until core.size) {
            assertTrue(AacpCodec.decodeSdu(core.copyOf(size)).none { it is AacpEvent.Information })
        }
    }
    @Test fun invalidUtf8InAnyCoreTokenIsRejectedEvenWithAnExtension() {
        val raw = fields().map { it.toByteArray(Charsets.UTF_8) }
        for (invalid in raw.indices) {
            val corrupt = raw.toMutableList().also { it[invalid] = byteArrayOf(0xff.toByte()) }
            val bytes = byteArrayOf(4,0,4,0,0x1d,0,1,0) +
                corrupt.fold(byteArrayOf()) { all, token -> all + token + byteArrayOf(0) } + opaque()
            assertTrue(AacpCodec.decodeSdu(bytes).single() is AacpEvent.Unknown)
        }
    }
    @Test fun invalidManufacturerAndBlankModelStillFail() {
        for (f in listOf(fields().also { it[3] = "Synthetic manufacturer" }, fields().also { it[2] = " " })) {
            assertTrue(AacpCodec.decodeSdu(packet(f, opaque())).single() is AacpEvent.Unknown)
        }
    }
    @Test fun extensionIsNeverRescannedIntoControlsOrAnotherIdentity() {
        val suffix = AacpCodec.mode(ListeningMode.ANC) + packet(fields().also { it[2] = "A9999" }) + opaque()
        val events = AacpCodec.decodeSdu(packet(trailer = suffix))
        assertEquals(1, events.size)
        assertTrue(events.single() is AacpEvent.Information)
        assertEquals("A3063", (events.single() as AacpEvent.Information).identity.modelNumber)
    }
    @Test fun framingReservedByteAndUnknownEnvelopeRemainStrict() {
        val candidates = listOf(
            byteArrayOf(4,0,4,0) + packet(trailer = opaque()),
            byteArrayOf(4,0,4,0,0x7f,0) + packet(trailer = opaque()),
            packet(trailer = opaque()).also { it[5] = 1 },
            byteArrayOf(4,0,4,0,0x1d,0,1,0,0) + packet().copyOfRange(8, packet().size),
        )
        candidates.forEach { bytes ->
            assertTrue(AacpCodec.decodeSdu(bytes).none { it is AacpEvent.Information })
        }
    }
}
