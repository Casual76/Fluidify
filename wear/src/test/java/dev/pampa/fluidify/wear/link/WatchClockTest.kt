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

    @Test fun aFewMillisecondsOfDriftAreNotNewsForTheScreens() {
        val state = WatchState(File.createTempFile("watch-clock", ".json").apply { delete() })
        state.observeClock(100_000, 110_000)
        state.accept(PlaybackSnapshot(seq = 1, sentAtEpochMs = 101_000, sampledAtEpochMs = 101_000), 131_000)
        val held = state.current.value
        // The estimate moves by 20 ms (a message that took 20 ms less): nobody redraws for that.
        state.observeClock(103_000, 113_000 - 20)
        assertSame(held, state.current.value)
    }

    @Test fun aRealStepOfTheClockIsPassedOn() {
        val state = WatchState(File.createTempFile("watch-clock", ".json").apply { delete() })
        state.observeClock(100_000, 110_000)
        state.accept(PlaybackSnapshot(seq = 1, sentAtEpochMs = 101_000, sampledAtEpochMs = 101_000), 131_000)
        val held = state.current.value
        // 200 ms earlier than anything seen: the minimum of the window, so the estimate drops by 200 ms.
        state.observeClock(103_000, 113_000 - 200)
        assertNotSame(held, state.current.value)
        assertEquals(9_800L, state.current.value!!.clockOffsetMs)
    }
}
