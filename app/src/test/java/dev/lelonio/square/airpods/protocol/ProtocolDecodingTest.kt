// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.protocol
import dev.lelonio.square.airpods.*
import org.junit.Assert.*
import org.junit.Test

class ProtocolDecodingTest {
    private fun hex(s: String) = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()
    @Test fun multibyteMeaningIsPreservedAndBadPaddingCannotConfirm() {
        assertEquals(AacpEvent.Property(CommandProperty.CALL_MANAGEMENT_CONFIG,PropertyValue.Number(3)),
            AacpCodec.decodeSdu(hex("04 00 04 00 09 00 24 00 03 00 00")).single())
        assertEquals(AacpEvent.Property(CommandProperty.CHIME_VOLUME,PropertyValue.Number(0)),
            AacpCodec.decodeSdu(hex("04 00 04 00 09 00 1f 00 50 00 00")).single())
        for (bad in listOf("04 00 04 00 09 00 24 03 00 00 00", "04 00 04 00 09 00 1f 32 00 00 00",
            "04 00 04 00 09 00 01 00 01 00 00")) assertTrue(AacpCodec.decodeSdu(hex(bad)).single() is AacpEvent.Unknown)
    }
    @Test fun eqRequiresRealReceivedLayoutAndNeverManufacturesFlatDefault() {
        val frame=hex("04 00 04 00 63 00 05 00 01 02 00 32 64")
        assertEquals(AacpEvent.Property(CommandProperty.EQ,PropertyValue.Eq(true,0,50,100)),AacpCodec.decodeSdu(frame).single())
        for (n in 1 until frame.size) assertTrue(AacpCodec.decodeSdu(frame.copyOf(n)).all { it is AacpEvent.Unknown })
        frame[6]=4
        assertTrue(AacpCodec.decodeSdu(frame).single() is AacpEvent.Unknown)
    }
    @Test fun unknownVariableFrameConsumesRestWithoutHeaderScanning() {
        val frame=hex("04 00 04 00 7f 00")+AacpCodec.mode(ListeningMode.ANC)
        assertEquals(1,AacpCodec.decodeSdu(frame).size)
        assertTrue(AacpCodec.decodeSdu(frame).single() is AacpEvent.Unknown)
    }
    @Test fun syntheticEarStemAndTrackingCaptures() {
        // Synthetic frames based on pinned Packets.kt and HeadOrientation.kt, not radio captures.
        assertEquals(EarState.BOTH,(AacpCodec.decodeSdu(hex("04 00 04 00 06 00 00 00")).single() as AacpEvent.Ear).sample.normalized)
        assertEquals(EarState.NONE,(AacpCodec.decodeSdu(hex("04 00 04 00 06 00 01 01")).single() as AacpEvent.Ear).sample.normalized)
        assertEquals(EarState.UNKNOWN,(AacpCodec.decodeSdu(hex("04 00 04 00 06 00 ff 01")).single() as AacpEvent.Ear).sample.normalized)
        assertEquals(AacpEvent.Stem(StemSample(8,2)),AacpCodec.decodeSdu(hex("04 00 04 00 19 00 08 02")).single())
        assertTrue(AacpCodec.decodeSdu(hex("04 00 04 00 19 00 09 02")).single() is AacpEvent.Unknown)
        for (variant in listOf(0x44,0x45)) {
            val frame=ByteArray(70)
            hex("04 00 04 00 17 00 00 00 10 00").copyInto(frame)
            frame[10]=variant.toByte(); frame[43]=0xff.toByte(); frame[44]=0xff.toByte()
            frame[51]=0; frame[52]=0x80.toByte(); frame[53]=0xff.toByte();frame[54]=0x7f
            val sample=(AacpCodec.decodeSdu(frame).single() as AacpEvent.Head).sample
            assertEquals(-1,sample.orientation1); assertEquals(-32768,sample.horizontal);assertEquals(32767,sample.vertical)
            assertEquals(variant,sample.wireVariant)
            assertTrue(AacpCodec.decodeSdu(frame.copyOf(69)).single() is AacpEvent.Unknown)
        }
    }
    @Test fun modelsAndCatalogDoNotFallBackOrClaimAnnouncements() {
        assertEquals(11,AirPodsModels.models.size)
        assertEquals(35,AirPodsFeatures.includedIds.size)
        assertEquals(19,AirPodsProperties.controls.count { !it.internalDependency })
        val unknown=AirPodsIdentity("A9999","Apple Inc.","fixture")
        assertFalse(unknown.recognized);assertTrue(unknown.modelDeclared.isEmpty())
        val pro1=AirPodsIdentity("A2084","Apple Inc.","fixture")
        assertEquals(setOf(ListeningMode.OFF,ListeningMode.ANC,ListeningMode.TRANSPARENCY),pro1.modes)
        assertFalse(AirPodsProperties.spec(CommandProperty.AUTO_ANC_STRENGTH)!!.candidate(pro1))
        assertNull(AirPodsState().deviceAnnounced)
    }
    @Test fun optimizedChargingRetainsReceivedPercentAndUnknownStatusDoesNotInventOne() {
        val packet=hex("04 00 04 00 04 00 03 04 00 50 05 00 02 00 40 02 00 08 00 63 04 00")
        val batteries=(AacpCodec.decodeSdu(packet).single() as AacpEvent.Battery).components
        assertEquals(80,batteries[4]?.percent);assertEquals(5,batteries[4]?.status)
        assertNull(batteries[8]?.percent)
        packet[10]=0x7f
        assertNull((AacpCodec.decodeSdu(packet).single() as AacpEvent.Battery).components[4]?.percent)
    }
    @Test fun receivedInformationKeepsAllFieldsAndRedactsSecrets() {
        val strings=listOf("ignored","Cuffie è","A3063","Apple Inc.","secretSerial","v1","v2","hw","updater","secretLeft","secretRight","v3")
        val frame=hex("04 00 04 00 1d 00 01 00")+strings.joinToString("\u0000",postfix="\u0000").toByteArray()
        val id=(AacpCodec.decodeSdu(frame).single() as AacpEvent.Information).identity
        assertEquals("Cuffie è",id.name);assertEquals("v1",id.firmware);assertEquals("v2",id.version2)
        assertEquals("v3",id.version3); assertEquals("secretLeft",id.leftSerialNumber)
        assertEquals("secretRight",id.rightSerialNumber);assertEquals("hw",id.hardwareRevision)
        assertEquals("updater",id.updaterIdentifier)
        assertFalse(id.toString().contains("secret"))
        assertTrue(AacpCodec.decodeSdu(frame.copyOf(frame.size-1)).single() is AacpEvent.Unknown)
        val invalid=frame.copyOf(); invalid[10]=0xff.toByte()
        assertTrue(AacpCodec.decodeSdu(invalid).single() is AacpEvent.Unknown)
    }
}
