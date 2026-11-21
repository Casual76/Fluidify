// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.protocol
import dev.lelonio.square.airpods.*
import org.junit.Assert.*
import org.junit.Test

class ProtocolDecodingTest {
    private fun hex(s: String) = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()
    private fun property(frame: String) = AacpCodec.decodeSdu(hex(frame)).single()
    private fun observed(p: CommandProperty, v: Int) = AacpEvent.Property(p, PropertyValue.Number(v))

    @Test fun multibyteMeaningIsPreservedAndOnlyTheValueByteIsRead() {
        assertEquals(observed(CommandProperty.CALL_MANAGEMENT_CONFIG,3), property("04 00 04 00 09 00 24 00 03 00 00"))
        assertEquals(observed(CommandProperty.CHIME_VOLUME,0), property("04 00 04 00 09 00 1f 00 50 00 00"))
        // FIX-05: like pinned AACPManager + its screens, the other bytes are not checked.
        assertEquals(observed(CommandProperty.CHIME_VOLUME,50), property("04 00 04 00 09 00 1f 32 00 00 00"))
        assertEquals(observed(CommandProperty.MIC_MODE,0), property("04 00 04 00 09 00 01 00 01 00 00"))
        assertEquals(observed(CommandProperty.CALL_MANAGEMENT_CONFIG,2), property("04 00 04 00 09 00 24 07 02 00 00"))
        // CALL reads byte 1: a lone 3 in byte 0 leaves byte 1 = 0, which is outside the domain.
        assertTrue(property("04 00 04 00 09 00 24 03 00 00 00") is AacpEvent.Unknown)
    }
    @Test fun dirtyPaddingZeroOneBooleansAndZeroSwipeIntervalAreObserved() {
        assertEquals(observed(CommandProperty.EAR_DETECTION_CONFIG,1), property("04 00 04 00 09 00 0a 01 00 ff 00"))
        assertEquals(observed(CommandProperty.EAR_DETECTION_CONFIG,1), property("04 00 04 00 09 00 0a 01 00 00 01"))
        // 0/1 encoding: 0 is off, exactly like 2.
        assertEquals(observed(CommandProperty.EAR_DETECTION_CONFIG,2), property("04 00 04 00 09 00 0a 00 00 00 00"))
        assertEquals(observed(CommandProperty.EAR_DETECTION_CONFIG,2), property("04 00 04 00 09 00 0a 02 00 00 00"))
        assertEquals(observed(CommandProperty.VOLUME_SWIPE_INTERVAL,1), property("04 00 04 00 09 00 23 00 00 00 00"))
        assertEquals(observed(CommandProperty.CHIME_VOLUME,100), property("04 00 04 00 09 00 1f c8 50 00 00"))
        assertEquals(observed(CommandProperty.AUTO_ANC_STRENGTH,0), property("04 00 04 00 09 00 2e c8 00 00 00"))
        assertEquals(AacpEvent.Mode(ListeningMode.ANC), property("04 00 04 00 09 00 0d 02 ff ff ff"))
        // Values without a meaning stay retained Unknowns, never an exception.
        for (bad in listOf("04 00 04 00 09 00 0a 03 00 00 00", "04 00 04 00 09 00 0d 00 00 00 00",
            "04 00 04 00 09 00 0d 05 00 00 00", "04 00 04 00 09 00 23 04 00 00 00", "04 00 04 00 09 00 01 03 00 00 00")) {
            val event = property(bad) as AacpEvent.Unknown
            assertEquals(hex(bad).drop(7).map { it.toInt() and 255 }, event.retainedControlPayload)
        }
    }
    @Test fun hearingControlsDecodeTheReceivedEnrolledByte() {
        assertEquals(observed(CommandProperty.HEARING_AID, HearingAidWire.pack(1, true)), property("04 00 04 00 09 00 2c 01 01 00 00"))
        assertEquals(observed(CommandProperty.HEARING_AID, HearingAidWire.pack(1, false)), property("04 00 04 00 09 00 2c 01 02 00 00"))
        assertEquals(observed(CommandProperty.HEARING_AID, HearingAidWire.pack(0, false)), property("04 00 04 00 09 00 2c 00 00 00 00"))
        val received = (property("04 00 04 00 09 00 2c 01 01 00 00") as AacpEvent.Property).value as PropertyValue.Number
        assertEquals(1, HearingAidWire.enrolled(received.value)); assertTrue(HearingAidWire.enabled(received.value))
        assertTrue(property("04 00 04 00 09 00 2c 01 03 00 00") is AacpEvent.Unknown)
        assertEquals(observed(CommandProperty.HEARING_ASSIST_CONFIG,1), property("04 00 04 00 09 00 33 01 00 00 00"))
        assertEquals(observed(CommandProperty.HPS_GAIN_SWIPE,2), property("04 00 04 00 09 00 2f 02 00 00 00"))
    }
    @Test fun batteryKeepsKnownSlotsWhateverTheLayoutByteOrAnUnknownSlot() {
        // Slot count byte says 4 and the third slot carries an unknown component: only it is dropped.
        val packet=hex("04 00 04 00 04 00 04 04 00 50 02 00 02 00 40 01 00 10 00 63 02 00")
        val battery=(AacpCodec.decodeSdu(packet).single() as AacpEvent.Battery).components
        assertEquals(setOf(4,2),battery.keys)
        assertEquals(BatteryValue(80,2),battery[4]); assertEquals(BatteryValue(64,1),battery[2])
        packet[7]=0x20; packet[12]=0x30
        assertTrue(AacpCodec.decodeSdu(packet).single() is AacpEvent.Unknown)
    }
    @Test fun conversationActivityIsByteNineWhateverPrecedesIt() {
        assertEquals(AacpEvent.ConversationActivity(1), AacpCodec.decodeSdu(hex("04 00 04 00 4b 00 02 05 00 01")).single())
        assertEquals(AacpEvent.ConversationActivity(0x33), AacpCodec.decodeSdu(hex("04 00 04 00 4b 00 07 00 00 33")).single())
    }
    @Test fun anyTrackingFrameOfSeventyBytesIsAHeadSample() {
        val frame=ByteArray(70)
        hex("04 00 04 00 17 00 01 02 11 03 52 09").copyInto(frame)
        frame[43]=5; frame[51]=0; frame[52]=0x80.toByte(); frame[53]=0xff.toByte(); frame[54]=0x7f
        val sample=(AacpCodec.decodeSdu(frame).single() as AacpEvent.Head).sample
        assertEquals(5,sample.orientation1); assertEquals(-32768,sample.horizontal); assertEquals(32767,sample.vertical)
        assertEquals(0x52,sample.wireVariant)
        assertTrue(AacpCodec.decodeSdu(frame.copyOf(69)).single() is AacpEvent.Unknown)
    }
    @Test fun smartRoutingBecomesAnOpcodeWithoutAnyPayloadByte() {
        // Synthetic, shaped after pinned AACPManager.kt:507-559: a sender MAC then text naming a host.
        val mac=hex("a1 b2 c3 d4 e5 f6")
        val text="btName iPad SetOwnershipToFalse ShowNearbyUI".toByteArray()
        val buffer=dev.lelonio.square.airpods.runtime.AirPodsRawRxBuffer(clock={0})
        dev.lelonio.square.airpods.runtime.AirPodsRxCapture.install(buffer)
        try {
            for (opcode in listOf(0x11,0x0e,0x2e)) {
                val rows=mutableListOf<AacpRxFrame>()
                val frame=byteArrayOf(4,0,4,0,opcode.toByte(),0)+mac+text
                val events=AacpCodec.decodeSdu(frame+AacpCodec.mode(ListeningMode.ANC)) { rows.add(it) }
                assertEquals(listOf(AacpEvent.AudioMovedToOtherDevice(opcode),AacpEvent.Mode(ListeningMode.ANC)),events)
                assertTrue(rows.first().bytes.isEmpty())
                assertEquals(frame.size,rows.first().byteCount)
            }
            val text2=buffer.export()
            assertFalse(text2.contains("a1b2c3"))
            assertFalse(text2.lowercase().contains("ipad"))
            assertTrue(text2.contains("op=11 id=- len="))
        } finally { dev.lelonio.square.airpods.runtime.AirPodsRxCapture.remove(buffer) }
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
        assertEquals(37,AirPodsFeatures.includedIds.size)
        assertEquals(21,AirPodsProperties.controls.count { !it.internalDependency })
        val unknown=AirPodsIdentity("A9999","Apple Inc.","fixture")
        assertFalse(unknown.recognized);assertTrue(unknown.modelDeclared.isEmpty())
        val pro1=AirPodsIdentity("A2084","Apple Inc.","fixture")
        assertEquals(setOf(ListeningMode.OFF,ListeningMode.ANC,ListeningMode.TRANSPARENCY),pro1.modes)
        assertFalse(AirPodsProperties.spec(CommandProperty.AUTO_ANC_STRENGTH)!!.candidate(pro1))
        assertNull(AirPodsState().deviceAnnounced)
    }
    @Test fun optimizedChargingRetainsReceivedPercentAndUnknownStatusIsKeptRaw() {
        val packet=hex("04 00 04 00 04 00 03 04 00 50 05 00 02 00 40 02 00 08 00 63 04 00")
        val batteries=(AacpCodec.decodeSdu(packet).single() as AacpEvent.Battery).components
        assertEquals(80,batteries[4]?.percent);assertEquals(5,batteries[4]?.status)
        assertNull(batteries[8]?.percent) // disconnected: no percentage, never a stale or 0%
        // FIX-06: any status is accepted as received; the level stays a level.
        packet[10]=0x7f
        assertEquals(BatteryValue(80,0x7f),(AacpCodec.decodeSdu(packet).single() as AacpEvent.Battery).components[4])
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
