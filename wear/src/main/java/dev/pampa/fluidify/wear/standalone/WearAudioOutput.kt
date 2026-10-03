package dev.pampa.fluidify.wear.standalone

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import dev.lelonio.square.nativecore.NativeAudioSink
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Where the watch's engine sends its sound: one AudioTrack, nothing in between.
 *
 * The phone's output carries a time-stretcher, a reverb and a karaoke filter;
 * none of them belong on a watch, where every millisecond of CPU is battery.
 * What is kept is what makes the music sound right: the fades that turn a skip
 * into a dissolve instead of a cut, and the flush that keeps the end of one
 * song from playing over the start of the next.
 *
 * The engine calls [start], [stop] and [write] by name (native/src/sink.rs);
 * the player calls the fades.
 */
class WearAudioOutput : NativeAudioSink {

    @Volatile private var track: AudioTrack? = null
    private var configuredRate = 0
    private var configuredChannels = 0

    /** True between a fade-out for a load and the fade-in after it: packets of the old track are dropped. */
    @Volatile private var discarding = false
    @Volatile private var gain = 1f

    /** Where the sound should go: the speaker, a headset. Null is the system's choice. */
    @Volatile var preferredDevice: AudioDeviceInfo? = null
        set(value) {
            field = value
            synchronized(this) { track?.preferredDevice = value }
        }

    private val fades = Executors.newSingleThreadExecutor { Thread(it, "wear-fade") }
    private val fadeGeneration = AtomicLong()

    override fun start() {
        discarding = false
        val output = synchronized(this) { track?.takeIf { it.state == AudioTrack.STATE_INITIALIZED } } ?: return
        runCatching {
            output.setVolume(0f)
            gain = 0f
            output.play()
        }
        val generation = fadeGeneration.incrementAndGet()
        fades.execute { ramp(output, 1f, FADE_IN_MS, generation) }
    }

    override fun stop() {
        discarding = false
        val output = synchronized(this) { track?.takeIf { it.state == AudioTrack.STATE_INITIALIZED } } ?: return
        ramp(output, 0f, FADE_OUT_MS, 0)
        runCatching {
            output.pause()
            output.flush()
        }
    }

    override fun write(data: ByteBuffer, sizeInBytes: Int, sampleRate: Int, channels: Int) {
        if (discarding) return
        val output = synchronized(this) { ensureTrack(sampleRate, channels) } ?: return
        var written = 0
        while (written < sizeInBytes) {
            val result = output.write(data, sizeInBytes - written, AudioTrack.WRITE_BLOCKING)
            if (result <= 0) return
            written += result
        }
    }

    /** Fades out, drops what is buffered, then runs [action]: the player is loading another track. */
    fun fadeOutThen(action: () -> Unit) {
        val output = synchronized(this) { track?.takeIf { it.state == AudioTrack.STATE_INITIALIZED } }
        if (output == null) {
            action()
            return
        }
        val generation = fadeGeneration.incrementAndGet()
        fades.execute {
            ramp(output, 0f, SKIP_FADE_MS, generation)
            discarding = true
            discardBuffered(output)
            action()
        }
    }

    /** Brings the level back after a load. */
    fun fadeIn() {
        val output = synchronized(this) { track?.takeIf { it.state == AudioTrack.STATE_INITIALIZED } }
        discarding = false
        output ?: return
        val generation = fadeGeneration.incrementAndGet()
        fades.execute { ramp(output, 1f, FADE_IN_MS, generation) }
    }

    fun release() {
        synchronized(this) {
            track?.run {
                runCatching { pause() }
                runCatching { flush() }
                release()
            }
            track = null
            configuredRate = 0
            configuredChannels = 0
        }
        fades.shutdownNow()
    }

    private fun discardBuffered(output: AudioTrack) {
        runCatching {
            output.pause()
            output.flush()
            output.play()
        }
    }

    private fun ramp(output: AudioTrack, target: Float, durationMs: Long, generation: Long) {
        val steps = (durationMs / STEP_MS).toInt().coerceAtLeast(1)
        val from = gain
        for (step in 1..steps) {
            if (generation != 0L && fadeGeneration.get() != generation) return
            val level = from + (target - from) * step / steps
            runCatching { output.setVolume(level) }
            gain = level
            Thread.sleep(STEP_MS)
        }
    }

    private fun ensureTrack(sampleRate: Int, channels: Int): AudioTrack? {
        val existing = track
        if (existing != null && configuredRate == sampleRate && configuredChannels == channels) return existing
        existing?.run {
            runCatching { pause() }
            release()
        }
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) {
            Log.e(TAG, "unsupported output format: $sampleRate Hz, $channels ch")
            track = null
            return null
        }
        val created = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(mask)
                    .build(),
            )
            // Bigger than the phone's: a watch's CPU sleeps harder between wake-ups, and a
            // deeper buffer is what lets it.
            .setBufferSizeInBytes(minBuffer * BUFFER_MULTIPLIER)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (created.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack failed to initialise")
            created.release()
            track = null
            return null
        }
        preferredDevice?.let { created.preferredDevice = it }
        runCatching {
            created.setVolume(gain)
            created.play()
        }
        track = created
        configuredRate = sampleRate
        configuredChannels = channels
        return created
    }

    private companion object {
        const val TAG = "WearAudioOutput"
        const val FADE_IN_MS = 220L
        const val FADE_OUT_MS = 160L
        const val SKIP_FADE_MS = 140L
        const val STEP_MS = 10L
        const val BUFFER_MULTIPLIER = 6
    }
}
