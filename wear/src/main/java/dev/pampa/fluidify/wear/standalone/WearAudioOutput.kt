package dev.pampa.fluidify.wear.standalone

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
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

    /**
     * Set by [release]: the service that owned this output is gone. The engine keeps a reference
     * until the next service hands it a new one, and may still call in — a Connect transfer to the
     * watch while only a download holds the engine. Before this, a write rebuilt an AudioTrack
     * nobody would ever release, and a start reached the stopped fade thread and threw inside a
     * native callback.
     */
    @Volatile private var released = false

    /** Where the sound should go: the speaker, a headset. Null is the system's choice. */
    @Volatile var preferredDevice: AudioDeviceInfo? = null
        set(value) {
            field = value
            synchronized(this) { track?.preferredDevice = value }
        }

    /**
     * Writes in a row that moved no sound, and whether the failure of the track was already said:
     * see [write]. Only the engine's audio thread touches them.
     */
    private var stalledWrites = 0
    private var deadTrackLogged = false

    /** Until when no new AudioTrack is attempted after one that would not initialise. */
    @Volatile private var noBuildBefore = 0L

    private val fades = Executors.newSingleThreadExecutor { Thread(it, "wear-fade") }
    private val fadeGeneration = AtomicLong()

    override fun start() {
        if (released) return
        discarding = false
        val output = synchronized(this) { track?.takeIf { it.state == AudioTrack.STATE_INITIALIZED } } ?: return
        runCatching {
            output.setVolume(0f)
            gain = 0f
            output.play()
        }
        val generation = fadeGeneration.incrementAndGet()
        execute { ramp(output, 1f, FADE_IN_MS, generation) }
    }

    override fun stop() {
        if (released) return
        discarding = false
        val output = synchronized(this) { track?.takeIf { it.state == AudioTrack.STATE_INITIALIZED } } ?: return
        ramp(output, 0f, FADE_OUT_MS, 0)
        runCatching {
            output.pause()
            output.flush()
        }
    }

    override fun write(data: ByteBuffer, sizeInBytes: Int, sampleRate: Int, channels: Int) {
        if (discarding || released) return
        val output = synchronized(this) { ensureTrack(sampleRate, channels) }
        if (output == null) {
            // No track to give the sound to: the write must still take as long as the sound does,
            // or the decoder runs at the speed of the CPU, through the whole song, into nothing.
            pace(sizeInBytes, sampleRate, channels)
            return
        }
        val head = runCatching { output.playbackHeadPosition.toLong() and 0xffffffffL }.getOrDefault(0L)
        if (lightTrack !== output || lightFramesWritten < head || lightFramesWritten - head > sampleRate * 2L) {
            lightTrack = output; lightFramesWritten = head
        }
        dev.lelonio.square.playback.AudioReactive.capture(data, sizeInBytes, sampleRate, channels,
            queuedMs = (lightFramesWritten - head) * 1000 / sampleRate)
        var written = 0
        while (written < sizeInBytes) {
            val result = output.write(data, sizeInBytes - written, AudioTrack.WRITE_BLOCKING)
            if (result <= 0) {
                stalled(output, result)
                // The rest of this packet is lost; the time it would have taken is not.
                pace(sizeInBytes - written, sampleRate, channels)
                return
            }
            lightFramesWritten += result / (channels * 2)
            written += result
        }
        stalledWrites = 0
        deadTrackLogged = false
    }

    /**
     * A write that took no sound. A negative result is the track telling it is no good any more
     * (ERROR_DEAD_OBJECT after the audio route changed, the server restarting): it is released
     * here, and the next write builds another through [ensureTrack]. Before, the function just
     * returned, and the dead track stayed for the rest of the session — silence, with the engine
     * playing on. A zero is given a few tries, then the same.
     */
    private fun stalled(output: AudioTrack, result: Int) {
        stalledWrites++
        if (result >= 0 && stalledWrites < MAX_STALLED_WRITES) return
        if (!deadTrackLogged) {
            Log.w(TAG, "AudioTrack write failed ($result, $stalledWrites in a row): rebuilding the track")
            deadTrackLogged = true
        }
        synchronized(this) {
            if (track !== output) return
            runCatching { output.pause() }
            runCatching { output.release() }
            track = null
            configuredRate = 0
            configuredChannels = 0
        }
        stalledWrites = 0
    }

    /** Takes as long as [sizeInBytes] of sound lasts, so a write that cannot play still keeps time. */
    private fun pace(sizeInBytes: Int, sampleRate: Int, channels: Int) {
        val frames = sizeInBytes / (channels.coerceAtLeast(1) * 2)
        val ms = (frames * 1000L / sampleRate.coerceAtLeast(1)).coerceIn(0L, MAX_PACE_MS)
        if (ms <= 0L) return
        try {
            Thread.sleep(ms)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** The track the audio light is counting frames of, and how many have been written to it. */
    private var lightTrack: AudioTrack? = null
    private var lightFramesWritten = 0L

    /** Fades out, drops what is buffered, then runs [action]: the player is loading another track. */
    fun fadeOutThen(action: () -> Unit) {
        val output = synchronized(this) { track?.takeIf { it.state == AudioTrack.STATE_INITIALIZED } }
        if (output == null || released) {
            action()
            return
        }
        val generation = fadeGeneration.incrementAndGet()
        execute {
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
        execute { ramp(output, 1f, FADE_IN_MS, generation) }
    }

    fun release() {
        released = true
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

    /** On the fade thread, unless it has been stopped: then nowhere, which is what a released output does. */
    private fun execute(task: () -> Unit) {
        runCatching { fades.execute(task) }
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
        if (released) return null
        val existing = track
        if (existing != null && configuredRate == sampleRate && configuredChannels == channels) return existing
        // A format the device would not take a moment ago is not asked again for every packet.
        if (existing == null && SystemClock.elapsedRealtime() < noBuildBefore) return null
        existing?.run {
            runCatching { pause() }
            release()
        }
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) {
            Log.e(TAG, "unsupported output format: $sampleRate Hz, $channels ch")
            track = null
            noBuildBefore = SystemClock.elapsedRealtime() + REBUILD_BACKOFF_MS
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
            // Music, not a call: latency is worth nothing here, and the deep buffer path lets the
            // CPU sleep between wake-ups, which on a watch is the battery.
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_POWER_SAVING)
            .build()
        if (created.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack failed to initialise")
            created.release()
            track = null
            noBuildBefore = SystemClock.elapsedRealtime() + REBUILD_BACKOFF_MS
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

        /** Writes in a row that moved nothing (not an error, just nothing) before the track is rebuilt. */
        const val MAX_STALLED_WRITES = 3

        /** The longest a write that cannot play waits: a packet is a few tens of milliseconds. */
        const val MAX_PACE_MS = 250L
        const val REBUILD_BACKOFF_MS = 500L
    }
}
