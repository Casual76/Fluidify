// PREPARED, NOT COMPILED OR EXECUTED. Uses the proposed AirPodsExoOutput.
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.lelonio.square.playback

import android.app.Application
import android.media.AudioFormat
import android.media.AudioTrack
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
class ExoPlayOriginTest {
    private data class Marker(val id: Long, val epoch: Long, val generation: Long)
    private fun track() = AudioTrack.Builder().setAudioFormat(AudioFormat.Builder().setSampleRate(44100)
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
        .setBufferSizeInBytes(4096).build()

    @Test fun coincidentPlayingAndCallbackBeforeOriginNeverCreateReceipt() {
        val output = AirPodsExoOutput(); val track = track(); var marker: Marker? = null
        try {
            track.play(); output.trackCreated(track)
            output.enqueuePlayOrigin = { id, epoch, generation -> marker = Marker(id, epoch, generation) }
            output.armPlay(42)
            repeat(3) { assertTrue(output.read().active); assertNull(output.playApplied) }
            output.played() // older sink play before our playback-queue marker
            assertNull(output.playApplied)
            val m = marker!!
            output.applyPlayOrigin(m.id, m.epoch - 1, m.generation); output.played()
            assertNull(output.playApplied)
            output.applyPlayOrigin(m.id, m.epoch, m.generation)
            track.pause(); output.played(); assertNull(output.playApplied)
            track.play(); output.played(); assertEquals(42L, output.playApplied)
        } finally { output.reset(); track.release() }
    }

    @Test fun manualCommandResetAndNewTrackRejectOldQueuedOrigin() {
        for (kind in 0..2) {
            val output = AirPodsExoOutput(); val first = track(); val second = track(); var marker: Marker? = null
            try {
                first.play(); output.trackCreated(first)
                output.enqueuePlayOrigin = { id, epoch, generation -> marker = Marker(id, epoch, generation) }
                output.armPlay(42); val m = marker!!
                when (kind) { 0 -> output.command(); 1 -> output.reset(); else -> { second.play(); output.trackCreated(second) } }
                output.applyPlayOrigin(m.id, m.epoch, m.generation); output.played()
                assertFalse(output.ownsPlay(42)); assertNull(output.playApplied)
            } finally { output.reset(); first.release(); second.release() }
        }
    }
}
