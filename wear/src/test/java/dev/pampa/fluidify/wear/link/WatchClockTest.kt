package dev.pampa.fluidify.wear.link

import dev.pampa.fluidify.wear.protocol.PlaybackSnapshot
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class WatchClockTest {
    @Test fun lateDataDeliveryDoesNotChangeHelloClockEstimate() {
        val state = WatchState(File.createTempFile("watch-clock", ".json").apply { delete() })
        state.observeClock(100_000, 110_000)
        state.accept(PlaybackSnapshot(seq = 1, sentAtEpochMs = 101_000, sampledAtEpochMs = 101_000), 131_000)
        assertEquals(10_000L, state.current.value!!.clockOffsetMs)
        state.observeClock(102_000, 112_020)
        assertEquals(10_000L, state.current.value!!.clockOffsetMs)
    }
}
