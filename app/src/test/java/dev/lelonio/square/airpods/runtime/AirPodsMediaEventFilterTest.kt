package dev.lelonio.square.airpods.runtime

import dev.lelonio.square.airpods.*
import dev.lelonio.square.airpods.protocol.EarSample
import dev.lelonio.square.airpods.protocol.HeadSample
import org.junit.Assert.*
import org.junit.Test

class AirPodsMediaEventFilterTest {
    @Test fun onlyObservedEarAndConversationActivityEnterMainMediaDispatch() {
        assertTrue(AacpEvent.Ear(EarSample(0, 0)).isMediaRuntimeEvent())
        for (status in listOf(1, 2, 6, 8, 9))
            assertTrue(AacpEvent.ConversationActivity(status).isMediaRuntimeEvent())
        assertFalse(AacpEvent.Head(HeadSample(0, 0, 0, 1, 2, 0x44)).isMediaRuntimeEvent())
        assertFalse(AacpEvent.ConversationSetting(true).isMediaRuntimeEvent())
        assertFalse(AacpEvent.Mode(ListeningMode.ANC).isMediaRuntimeEvent())
        assertFalse(AacpEvent.Battery(emptyMap()).isMediaRuntimeEvent())
        assertFalse(AacpEvent.Unknown(0xff, 6, "fixture").isMediaRuntimeEvent())
    }
}
