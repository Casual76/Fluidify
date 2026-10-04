package dev.pampa.fluidify.wear.protocol.logic

import dev.pampa.fluidify.wear.protocol.PlaybackSnapshot
import dev.pampa.fluidify.wear.protocol.TrackInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class PositionExtrapolatorTest {

    private fun snapshot(
        position: Long = 10_000,
        sampledAt: Long = 100_000,
        sentAt: Long = 100_020,
        playing: Boolean = true,
        speed: Float = 1f,
        buffering: Boolean = false,
        duration: Long = 200_000,
    ) = PlaybackSnapshot(
        seq = 1,
        sentAtEpochMs = sentAt,
        track = TrackInfo("spotify:track:a", "a", durationMs = duration),
        positionMs = position,
        sampledAtEpochMs = sampledAt,
        isPlaying = playing,
        speed = speed,
        buffering = buffering,
    )

    @Test
    fun playingAdvancesWithTheClock() {
        assertEquals(15_000, PositionExtrapolator.positionAt(snapshot(), receivedAtLocalMs = 100_050, nowLocalMs = 105_000))
    }

    @Test
    fun pausedStaysPut() {
        assertEquals(10_000, PositionExtrapolator.positionAt(snapshot(playing = false), 100_050, 160_000))
    }

    @Test
    fun bufferingStaysPut() {
        assertEquals(10_000, PositionExtrapolator.positionAt(snapshot(buffering = true), 100_050, 160_000))
    }

    @Test
    fun speedScalesTheAdvance() {
        assertEquals(20_000, PositionExtrapolator.positionAt(snapshot(speed = 2f), 100_050, 105_000))
    }

    @Test
    fun clampedToTheTrack() {
        assertEquals(200_000, PositionExtrapolator.positionAt(snapshot(), 100_050, 10_000_000))
        assertEquals(0, PositionExtrapolator.positionAt(snapshot(position = -50, playing = false), 100_050, 100_050))
    }

    @Test
    fun skewedClockReanchorsToReceipt() {
        // The watch's clock is an hour ahead: trust the receipt time, keeping the 20 ms
        // the phone spent between sampling and sending.
        val hour = 3_600_000L
        val received = 100_050 + hour
        assertEquals(received - 20, PositionExtrapolator.sampleTimeLocal(snapshot(), received))
        assertEquals(15_020, PositionExtrapolator.positionAt(snapshot(), received, received + 5_000))
    }

    @Test
    fun nowBeforeTheSampleDoesNotRewind() {
        assertEquals(10_000, PositionExtrapolator.positionAt(snapshot(), 100_050, 99_000))
    }

    @Test
    fun aLateDeliveryIsNotASkewedClock() {
        // The clocks agree (the offset learnt is 30 ms); this snapshot arrived 20 s late, the watch
        // coming back into range. The position is the phone's, not 20 s behind.
        val late = 100_020 + 20_000L
        assertEquals(30_020, PositionExtrapolator.positionAt(snapshot(), late, late, clockOffsetMs = 30))
    }

    @Test
    fun aKnownOffsetCorrectsASkewedClock() {
        val hour = 3_600_000L
        // An hour ahead, learnt from earlier snapshots: the sample is moved by exactly that.
        assertEquals(100_000 + hour, PositionExtrapolator.sampleTimeLocal(snapshot(), 100_050 + hour, clockOffsetMs = hour))
    }

    @Test
    fun theSmallestGapIsTheOffset() {
        val offset = ClockOffset(window = 3)
        assertEquals(40, offset.observe(sentAtRemoteMs = 1_000, receivedAtLocalMs = 1_040))
        // A late one does not move it.
        assertEquals(40, offset.observe(sentAtRemoteMs = 2_000, receivedAtLocalMs = 9_000))
        assertEquals(25, offset.observe(sentAtRemoteMs = 3_000, receivedAtLocalMs = 3_025))
        // The window moves on: the oldest gaps are forgotten.
        assertEquals(25, offset.observe(sentAtRemoteMs = 4_000, receivedAtLocalMs = 4_100))
    }

    @Test
    fun aPlayingSnapshotPastItsSongIsStale() {
        // 190 s left of the song from 100 000; stale 30 s after it would have ended.
        val s = snapshot()
        assertEquals(290_000L, PositionExtrapolator.endsAtLocal(s, 100_050, clockOffsetMs = 0))
        assertEquals(false, PositionExtrapolator.isStale(s, 100_050, 300_000, clockOffsetMs = 0))
        assertEquals(true, PositionExtrapolator.isStale(s, 100_050, 330_001, clockOffsetMs = 0))
        assertEquals(false, PositionExtrapolator.isStale(snapshot(playing = false), 100_050, 10_000_000, clockOffsetMs = 0))
    }
}
