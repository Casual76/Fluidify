package dev.lelonio.square.airpods

import org.junit.Assert.*
import org.junit.Test

class AacpCodecTest {
    @Test fun exactWireCommands() {
        assertArrayEquals(byteArrayOf(4, 0, 4, 0, 9, 0, 0x0d, 2, 0, 0, 0), AacpCodec.mode(ListeningMode.ANC))
        assertArrayEquals(byteArrayOf(4, 0, 4, 0, 9, 0, 0x28, 2, 0, 0, 0), AacpCodec.conversation(false))
        assertEquals(16, AacpCodec.handshake().size)
    }
    @Test fun truncatedControlNeverChangesState() {
        val packet = AacpCodec.mode(ListeningMode.ANC)
        for (size in 1 until packet.size) assertTrue(AacpCodec.decodeSdu(packet.copyOf(size)).all { it is AacpEvent.Unknown })
        assertEquals(listOf(AacpEvent.Mode(ListeningMode.ANC)), AacpCodec.decodeSdu(packet))
    }
    @Test fun unknownAndMultiByteControlsNeverConfirm() {
        assertTrue(AacpCodec.decodeSdu(byteArrayOf(4, 0, 4, 0, 0x7f, 0)).single() is AacpEvent.Unknown)
        val packet = AacpCodec.mode(ListeningMode.ANC); packet[8] = 1
        assertTrue(AacpCodec.decodeSdu(packet).single() is AacpEvent.Unknown)
    }
    @Test fun fixedFramesSharingSduAreDecodedInOrder() {
        assertEquals(listOf(AacpEvent.Mode(ListeningMode.TRANSPARENCY), AacpEvent.ConversationSetting(true)),
            AacpCodec.decodeSdu(AacpCodec.mode(ListeningMode.TRANSPARENCY) + AacpCodec.conversation(true)))
    }
    @Test fun upstreamRepeatedControlPrefixesAreAccepted() {
        assertEquals(listOf(AacpEvent.Mode(ListeningMode.ANC)),
            AacpCodec.decodeSdu(byteArrayOf(4,0,4,0) + AacpCodec.mode(ListeningMode.ANC)))
    }
    @Test fun conversationActivityIsNotConfiguration() {
        assertEquals(AacpEvent.ConversationActivity(6), AacpCodec.decodeSdu(byteArrayOf(4,0,4,0,0x4b,0,2,0,0,6)).single())
    }
    @Test fun batteryUnknownIsNotZeroAndDisconnectedIsNotLivePercent() {
        val packet = byteArrayOf(4,0,4,0,4,0,3,4,0,-1,2,0,2,0,80,2,0,8,0,99,4,0)
        val battery = AacpCodec.decodeSdu(packet).single() as AacpEvent.Battery
        assertNull(battery.components[4]!!.percent)
        assertEquals(80, battery.components[2]!!.percent)
        assertNull(battery.components[8]!!.percent)
        assertTrue(AacpCodec.decodeSdu(packet.copyOf(21)).single() is AacpEvent.Unknown)
    }
    @Test fun informationUsesReceivedModelAndRequiresCompleteLayout() {
        fun packet(model: String): ByteArray = byteArrayOf(4,0,4,0,0x1d,0,1,0) +
            listOf("ignored", "My buds", model, "Apple Inc.", "serial", "firmware", "v2", "hw", "updater", "left", "right", "v3")
                .joinToString("\u0000", postfix = "\u0000").toByteArray()
        for (model in listOf("A3063", "A3064", "A3065")) {
            val event = AacpCodec.decodeSdu(packet(model)).single() as AacpEvent.Information
            assertTrue(event.identity.conversationAwareness)
            assertTrue(ListeningMode.ADAPTIVE in event.identity.modes)
        }
        val unknown = AacpCodec.decodeSdu(packet("A9999")).single() as AacpEvent.Information
        assertFalse(unknown.identity.recognized)
        assertTrue(unknown.identity.modes.isEmpty())
        assertTrue(AacpCodec.decodeSdu(packet("A3063").dropLast(1).toByteArray()).single() is AacpEvent.Unknown)
    }
    @Test fun batteryFreshnessRequiresCurrentConnectionAndFiveMinuteWindow() {
        val sample = Received(BatteryValue(50, 2), 100L, 7L)
        val state = AirPodsState(generation = 7, stage = AacpStage.SUBSCRIBED, batteries = mapOf(4 to sample))
        assertEquals(50, state.liveBattery(4, 299_999)?.percent)
        assertNull(state.liveBattery(4, 300_100))
        assertNull(state.copy(stage = AacpStage.CLOSED).liveBattery(4, 101))
        assertNull(state.copy(generation = 8).liveBattery(4, 101))
    }
}
