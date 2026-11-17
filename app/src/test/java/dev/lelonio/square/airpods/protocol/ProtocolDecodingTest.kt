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
    @Test fun unknownFrameBetweenKnownFramesDoesNotConsumeTheFollowingControl() {
        val frame=hex("04 00 04 00 7f 00")+AacpCodec.mode(ListeningMode.ANC)
        val events=AacpCodec.decodeSdu(AacpCodec.conversation(true)+frame)
        assertEquals(3,events.size)
        assertEquals(AacpEvent.ConversationSetting(true),events[0])
        assertTrue(events[1] is AacpEvent.Unknown)
        assertEquals(AacpEvent.Mode(ListeningMode.ANC),events[2])
    }
    @Test fun meaningfulZeroFieldsAndTrailingEnvelopeZerosSurviveCoalescence() {
        val sdu = hex("04 00 04 00 09 00 39 00 00 00 00 00 00") +
            hex("04 00 04 00 09 00 2e 64 00 00 00 00 00 00")
        assertEquals(listOf(AacpEvent.Property(CommandProperty.STEM_CONFIG, PropertyValue.Number(0)),
            AacpEvent.Property(CommandProperty.AUTO_ANC_STRENGTH, PropertyValue.Number(0))), AacpCodec.decodeSdu(sdu))
        val cycle = AirPodsProperties.spec(CommandProperty.LISTENING_MODE_CONFIGS)!!
        for (mask in listOf(0,1,2,4,8)) {
            val rx = AacpCodec.decodeSdu(hex("04 00 04 00 09 00 1a")+byteArrayOf(mask.toByte(),0,0,0)).single()
            assertEquals(AacpEvent.Property(CommandProperty.LISTENING_MODE_CONFIGS,PropertyValue.Number(mask)),rx)
            assertFalse(cycle.accepts(mask))
            assertTrue(runCatching { AacpCodec.property(CommandProperty.LISTENING_MODE_CONFIGS,mask) }.isFailure)
        }
    }
    @Test fun numericRawDiagnosticsRetainRejectedBytesAndRedactPrivatePayloads() {
        val rows=mutableListOf<AacpRxFrame>()
        val control=hex("04 00 04 00 09 00 0a 01 00 00 01")
        AacpCodec.decodeSdu(control) { rows.add(it) }
        assertEquals(control.map { it.toInt() and 255 },rows.single().bytes)
        rows.clear()
        val name=hex("04 00 04 00 1a 00 01 03 00 61 62 63")
        AacpCodec.decodeSdu(name) { rows.add(it) }
        assertTrue(rows.single().bytes.isEmpty())
    }
    @Test fun splitFramesReachW2CaptureWithoutNamesOrAggregatePayloads() {
        val buffer=dev.lelonio.square.airpods.runtime.AirPodsRawRxBuffer(clock={0})
        dev.lelonio.square.airpods.runtime.AirPodsRxCapture.install(buffer)
        try {
            AacpCodec.decodeSdu(hex("04 00 04 00 09 00 0d 02 00 00 00")+
                hex("04 00 04 00 7f 00 00")+hex("04 00 04 00 09 00 39 00 00 00 00"))
            AacpCodec.decodeSdu(AacpCodec.rename("private-synthetic-name"))
            val text=buffer.export()
            assertTrue(text.contains("op=9 id=d len=4 bytes=02000000"))
            assertTrue(text.contains("op=9 id=39 len=4 bytes=00000000"))
            assertTrue(text.contains("op=7f id=- len=1 bytes=omitted"))
            assertTrue(text.contains("op=1a id=- len=25 bytes=omitted"))
            assertFalse(text.contains("private-synthetic-name"))
        } finally { dev.lelonio.square.airpods.runtime.AirPodsRxCapture.remove(buffer) }
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
            val coalesced=AacpCodec.decodeSdu(frame+hex("04 00 04 00 7f 00")+frame)
            assertEquals(3,coalesced.size)
            assertEquals(sample,(coalesced[0] as AacpEvent.Head).sample)
            assertTrue(coalesced[1] is AacpEvent.Unknown)
            assertEquals(sample,(coalesced[2] as AacpEvent.Head).sample)
            assertEquals(sample,(AacpCodec.decodeSdu(frame+byteArrayOf(1,2,3)).single() as AacpEvent.Head).sample)
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
