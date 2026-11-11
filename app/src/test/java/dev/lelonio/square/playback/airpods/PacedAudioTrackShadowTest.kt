// PREPARED, NOT COMPILED OR EXECUTED.
package dev.lelonio.square.playback

import android.app.Application
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Looper
import java.nio.ByteBuffer
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35], shadows = [PacedAudioTrackShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class PacedAudioTrackShadowTest {
    private fun track() = AudioTrack.Builder().setAudioFormat(AudioFormat.Builder()
        .setSampleRate(44100).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()).setBufferSizeInBytes(17640).build()
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test fun finiteQueueAppliesBackpressureAndOnlyTimeAdvancesPlaybackHead() {
        val track = track()
        try {
            val capacity = track.bufferSizeInFrames
            val buffer = ByteBuffer.allocateDirect(capacity * 4 * 3)
            assertEquals(capacity * 4, track.write(buffer, buffer.remaining(), AudioTrack.WRITE_NON_BLOCKING))
            val fullPosition = buffer.position()
            assertEquals(0, track.write(buffer, buffer.remaining(), AudioTrack.WRITE_NON_BLOCKING))
            assertEquals(fullPosition, buffer.position())
            assertEquals(0, track.playbackHeadPosition)
            track.play(); advance(50)
            assertEquals(2205, track.playbackHeadPosition)
            assertEquals(2205 * 4, track.write(buffer, buffer.remaining(), AudioTrack.WRITE_NON_BLOCKING))
            assertEquals(2205, track.playbackHeadPosition) // another write grants no playback progress
            val snapshot = Shadow.extract<PacedAudioTrackShadow>(track).snapshot()
            assertEquals(capacity.toLong(), snapshot.writtenFrames - snapshot.playedFrames)
            assertTrue(snapshot.fullWrites > 0)
        } finally { track.release() }
    }

    @Test fun stopResetsPublicHeadBeforeNewPlayback() {
        val track = track()
        try {
            val buffer = ByteBuffer.allocateDirect(track.bufferSizeInFrames * 4)
            assertTrue(track.write(buffer, buffer.remaining(), AudioTrack.WRITE_NON_BLOCKING) > 0)
            track.play(); advance(20)
            assertEquals(882, track.playbackHeadPosition)
            track.stop()
            assertEquals(AudioTrack.PLAYSTATE_STOPPED, track.playState)
            assertEquals(0, track.playbackHeadPosition)
            advance(100); assertEquals(0, track.playbackHeadPosition)
            val next = ByteBuffer.allocateDirect(441 * 4)
            assertEquals(441 * 4, track.write(next, next.remaining(), AudioTrack.WRITE_NON_BLOCKING))
            assertEquals(0, track.playbackHeadPosition)
            track.play(); advance(1)
            assertEquals(44, track.playbackHeadPosition)
        } finally { track.release() }
    }

    @Test fun pauseFlushAndUnderrunNeverAccumulateConsumptionCredit() {
        val track = track()
        try {
            val buffer = ByteBuffer.allocateDirect(track.bufferSizeInFrames * 4)
            assertTrue(track.write(buffer, buffer.remaining(), AudioTrack.WRITE_NON_BLOCKING) > 0)
            track.play(); advance(30); track.pause()
            assertEquals(AudioTrack.PLAYSTATE_PAUSED, track.playState)
            val paused = track.playbackHeadPosition
            assertEquals(1323, paused)
            advance(200); assertEquals(paused, track.playbackHeadPosition)
            track.flush(); assertEquals(0, track.playbackHeadPosition)
            track.play(); advance(100)
            assertEquals(0, track.playbackHeadPosition)
            val short = ByteBuffer.allocateDirect(441 * 4)
            assertEquals(441 * 4, track.write(short, short.remaining(), AudioTrack.WRITE_NON_BLOCKING))
            assertEquals(0, track.playbackHeadPosition)
            advance(5); assertEquals(220, track.playbackHeadPosition)
            advance(100); assertEquals(441, track.playbackHeadPosition)
            advance(1000)
            val refill = ByteBuffer.allocateDirect(100 * 4)
            assertEquals(400, track.write(refill, refill.remaining(), AudioTrack.WRITE_NON_BLOCKING))
            assertEquals(441, track.playbackHeadPosition)
            advance(2); assertEquals(529, track.playbackHeadPosition)
        } finally { track.release() }
    }
}
