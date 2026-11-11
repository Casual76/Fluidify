@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.lelonio.square.playback

import android.app.Application
import android.os.Looper
import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Synthetic PCM and native-free players exercise the session decorator boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = AudioReactivePlayerTest.Host::class)
class AudioReactivePlayerTest {
    class Host : Application(), CoreHost { override val qualityIsAutomatic = false }

    @Test fun sessionDecoratorKeepsNativePcmAndWrapperObservation() = nativeSource(nested = false)

    @Test fun nestedSessionDecoratorsKeepNativePcmAndWrapperObservation() = nativeSource(nested = true)

    private fun nativeSource(nested: Boolean) {
        val raw = nativePlayer()
        val observed = ObservedPlayer(if (nested) ForwardingPlayer(raw) else raw)
        val owner = Any()
        val light = AudioReactivePlayer(observed)
        AudioReactive.acquire(owner)
        try {
            val first = captureFrame(AudioLightSource.NATIVE)
            assertEquals("wrapper-track", first.track)
            assertTrue(first.energy > 0f)
            assertTrue("Position and speed come from the wrapper", first.positionMs >= 4_521L)

            observed.position = 9_000L
            observed.emit(Player.EVENT_POSITION_DISCONTINUITY)
            assertNull("Seek clears old frames", AudioReactive.frame())
            val afterSeek = captureFrame(AudioLightSource.NATIVE)
            assertTrue(afterSeek.generation > first.generation)

            observed.playing = false
            observed.emit(Player.EVENT_IS_PLAYING_CHANGED)
            assertNull("Pause clears visible frames", AudioReactive.frame())
        } finally {
            light.close()
            assertNull("Listener is removed from the supplied wrapper", observed.listener)
            AudioReactive.release(owner)
            raw.release()
        }
    }

    @Test fun switchingFromNativeToWrappedMedia3AcceptsOnlyTheNewSink() {
        val native = nativePlayer()
        val media3 = object : SimpleBasePlayer(Looper.getMainLooper()) {
            override fun getState(): State = State.Builder().build()
        }
        val owner = Any()
        val observed = ObservedPlayer(ForwardingPlayer(media3))
        var light = AudioReactivePlayer(ObservedPlayer(native))
        AudioReactive.acquire(owner)
        try {
            val before = captureFrame(AudioLightSource.NATIVE)
            light.close()
            light = AudioReactivePlayer(observed)
            assertNull(AudioReactive.frame())
            val after = captureFrame(AudioLightSource.MEDIA3)
            assertTrue(after.generation > before.generation)
            assertEquals("wrapper-track", after.track)

            observed.emit(Player.EVENT_POSITION_DISCONTINUITY)
            capture(AudioLightSource.NATIVE)
            Thread.sleep(100)
            assertNull("A draining native sink cannot light the Media3 track", AudioReactive.frame())
            assertNotNull(captureFrame(AudioLightSource.MEDIA3))
        } finally {
            light.close()
            assertNull(observed.listener)
            assertNull("Detach clears visible frames", AudioReactive.frame())
            AudioReactive.release(owner)
            native.release()
            media3.release()
        }
    }

    private fun nativePlayer() = LibrespotPlayer(
        RuntimeEnvironment.getApplication(), Looper.getMainLooper(), PlayQueue(),
        onSpeedAndPitch = { _, _ -> }, fadeOutThen = { it() }, fadeIn = {},
        onPlaybackActive = {}, onDuckReverb = {},
        loadQueueCommand = { _, _, _, _, _, _, _ -> }, refreshRemoteState = {},
        airPodsPauseCommand = {}, airPodsPlayCommand = {},
    ).also { it.shutdownEngineOnRelease = false }

    /** Distinct wrapper state catches fixes that also bypass its getters or listener. */
    private class ObservedPlayer(raw: Player) : ForwardingPlayer(raw) {
        var listener: Player.Listener? = null
        var playing = true
        var position = 4_321L
        override fun getCurrentMediaItem(): MediaItem = MediaItem.Builder().setMediaId("wrapper-track").build()
        override fun isPlaying() = playing
        override fun getCurrentPosition() = position
        override fun getPlaybackParameters() = PlaybackParameters(2f)
        override fun addListener(listener: Player.Listener) { this.listener = listener }
        override fun removeListener(listener: Player.Listener) {
            assertSame(this.listener, listener)
            this.listener = null
        }
        fun emit(event: Int) {
            checkNotNull(listener).onEvents(this, Player.Events(FlagSet.Builder().add(event).build()))
        }
    }

    private fun capture(kind: AudioLightSource) {
        val pcm = ByteBuffer.allocateDirect(4_096).order(ByteOrder.LITTLE_ENDIAN)
        repeat(2_048) { pcm.putShort(if (it % 2 == 0) 8_000.toShort() else (-8_000).toShort()) }
        pcm.flip()
        AudioReactive.capture(pcm, pcm.remaining(), 48_000, 1, queuedMs = 100, kind = kind)
        assertEquals("Analysis must not consume playback PCM", 0, pcm.position())
    }

    private fun captureFrame(kind: AudioLightSource): AudioLightFrame {
        capture(kind)
        val visibleAt = System.nanoTime() + 500_000_000L
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            AudioReactive.frame(visibleAt)?.let { return it }
            Thread.sleep(5)
        }
        error("No audio-light frame from $kind through the supplied session player")
    }
}
