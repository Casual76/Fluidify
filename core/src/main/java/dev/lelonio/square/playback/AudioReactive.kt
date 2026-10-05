package dev.lelonio.square.playback

import androidx.media3.common.Player
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What the music sounds like at one moment, for the light that follows it.
 *
 * Visual measurements only: nothing here changes the PCM or waits for the renderer. [dueNs] is
 * when the sound it was measured from will actually be heard — the audio sits in the output's
 * buffer for a while after it is written — so a frame is shown at its moment and not early.
 */
data class AudioLightFrame(
    val track: String = "",
    val generation: Long = 0,
    val positionMs: Long = 0,
    val dueNs: Long = 0,
    val energy: Float = 0f,
    val bass: Float = 0f,
    val mids: Float = 0f,
    val treble: Float = 0f,
    val bands: List<Float> = List(8) { 0f },
)

/** Which sink is feeding the light: librespot's own, or a Media3 player (local files, video). */
enum class AudioLightSource { NATIVE, MEDIA3 }

/**
 * Eight bands of loudness out of 2048 mono samples.
 *
 * A Hann window and an in-place radix-2 FFT, kept allocation-free: the arrays are reused for every
 * measurement, which runs on the analysis thread a few dozen times a second. Levels are relative
 * to a slowly decaying reference, with a floor so silence and quiet intros are not inflated into a
 * light show.
 */
class AudioSpectrum {
    private val real = DoubleArray(SIZE)
    private val imaginary = DoubleArray(SIZE)
    private var reference = REFERENCE_FLOOR
    private var low = 0.0
    private val cosines by lazy { DoubleArray(SIZE / 2) { cos(-2 * PI * it / SIZE) } }
    private val sines by lazy { DoubleArray(SIZE / 2) { sin(-2 * PI * it / SIZE) } }
    private val window by lazy { DoubleArray(SIZE) { .5 - .5 * cos(2 * PI * it / (SIZE - 1)) } }
    private val edges = doubleArrayOf(30.0, 80.0, 180.0, 400.0, 900.0, 2000.0, 4500.0, 9000.0, 20000.0)

    fun reset() {
        reference = REFERENCE_FLOOR
        low = 0.0
    }

    /** [light] measures only the overall energy and the bass, for the cheap mode. */
    fun measure(samples: FloatArray, count: Int, rate: Int, light: Boolean): AudioLightFrame {
        if (count <= 0 || rate <= 0) return AudioLightFrame()
        var squares = 0.0
        var lowSquares = 0.0
        // A one-pole low-pass at 250 Hz: the bass, without paying for a transform.
        val alpha = 1 - exp(-2 * PI * 250 / rate)
        for (i in 0 until count) {
            val sample = samples[i].toDouble()
            squares += sample * sample
            low += alpha * (sample - low)
            lowSquares += low * low
        }
        val rms = sqrt(squares / count)
        // The floor stops silence and quiet intros being inflated by the gain control.
        reference = max(REFERENCE_FLOOR, max(rms, reference * 0.995))
        fun level(x: Double): Float = if (rms < 0.002) {
            0f
        } else {
            (sqrt((x / reference).coerceIn(0.0, 1.0)) * (rms / 0.015).coerceAtMost(1.0)).toFloat()
        }
        val energy = level(rms)
        if (light) return AudioLightFrame(energy = energy, bass = level(sqrt(lowSquares / count)))

        real.fill(0.0)
        imaginary.fill(0.0)
        for (i in 0 until min(count, SIZE)) real[i] = samples[i] * window[i]
        transform()

        val bands = (0..7).map { band ->
            var sum = 0.0
            val from = max(1, ceil(edges[band] * SIZE / rate).toInt())
            val to = min(SIZE / 2, ceil(edges[band + 1] * SIZE / rate).toInt())
            for (bin in from until to) sum += real[bin] * real[bin] + imaginary[bin] * imaginary[bin]
            level(sqrt(sum) * 2 / count)
        }
        return AudioLightFrame(
            energy = energy,
            bass = bands.take(3).max(),
            mids = bands.slice(3..5).average().toFloat(),
            treble = bands.takeLast(2).average().toFloat(),
            bands = bands,
        )
    }

    /** In-place iterative radix-2 FFT over [real] and [imaginary]. */
    private fun transform() {
        // Bit-reversal permutation.
        var j = 0
        for (i in 1 until SIZE) {
            var bit = SIZE / 2
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val swap = real[i]
                real[i] = real[j]
                real[j] = swap
            }
        }
        var size = 2
        while (size <= SIZE) {
            val half = size / 2
            for (base in 0 until SIZE step size) {
                for (k in 0 until half) {
                    val twiddle = k * SIZE / size
                    val odd = base + k + half
                    val r = real[odd] * cosines[twiddle] - imaginary[odd] * sines[twiddle]
                    val im = real[odd] * sines[twiddle] + imaginary[odd] * cosines[twiddle]
                    real[odd] = real[base + k] - r
                    imaginary[odd] = imaginary[base + k] - im
                    real[base + k] += r
                    imaginary[base + k] += im
                }
            }
            size *= 2
        }
    }

    private companion object {
        const val SIZE = 2048
        const val REFERENCE_FLOOR = 0.08
    }
}

/**
 * The analysis behind the music light: PCM in from whichever sink is playing, frames out to
 * whoever is drawing.
 *
 * One process owns one audible source, and the light is independent of who owns playback: the
 * screens that draw it [acquire] and [release] it, and while nobody holds it nothing is measured
 * at all. The sink's thread is never made to wait — [capture] copies a window of samples into a
 * pooled packet and returns, dropping the visual samples rather than blocking on contention — and
 * the transform runs on one low-priority thread of its own.
 */
object AudioReactive {
    private data class Source(
        val track: String,
        val epoch: Long,
        val position: Long,
        val at: Long,
        val playing: Boolean,
        val speed: Float,
        val kind: AudioLightSource,
    )

    /** A window of mono samples on its way to the analysis thread, reused from [free]. */
    private class Packet {
        val mono = FloatArray(WINDOW)
        var count = 0
        var rate = 0
        var light = false
        var frame = AudioLightFrame()
    }

    private val users = ConcurrentHashMap<Any, Boolean>()
    @Volatile private var enabled = false
    @Volatile private var light = true

    /** Whether anyone is showing the light, so a sink can skip measuring for it. */
    val isEnabled: Boolean get() = enabled

    // Monotonic boot time also distinguishes a restarted phone process while the watch keeps its
    // subscription alive; starting again at zero would make the receiver reject every new
    // generation as an older playback.
    @Volatile private var source = Source(
        "", System.nanoTime().coerceAtLeast(0), 0, System.nanoTime(), false, 1f, AudioLightSource.NATIVE,
    )
    private val free = ArrayBlockingQueue<Packet>(POOL).apply { repeat(POOL) { add(Packet()) } }
    private val pending = ArrayBlockingQueue<Packet>(POOL)

    /**
     * The last [HISTORY] measured frames, oldest first, in a ring. Written by the analysis thread,
     * read by the drawing side; the lock is held for a copy of one reference either way. It used
     * to be a copy-on-write list trimmed with `removeAt(0)`, which copied the whole list twice
     * for every frame measured.
     */
    private val history = arrayOfNulls<AudioLightFrame>(HISTORY)
    private var historyEnd = 0
    private var historySize = 0
    private val historyLock = Any()

    @Volatile private var lastCapture = 0L
    private var assembling: Packet? = null // Only the PCM producer thread touches this window.
    private val started = AtomicBoolean()
    private val capturing = AtomicBoolean()

    /** Starts measuring for [owner]; [lightweight] owners are content with energy and bass. */
    @Synchronized fun acquire(owner: Any, lightweight: Boolean = false) {
        users[owner] = lightweight
        light = users.values.all { it }
        enabled = true
        if (started.compareAndSet(false, true)) startAnalysis()
    }

    @Synchronized fun release(owner: Any) {
        users.remove(owner)
        enabled = users.isNotEmpty()
        light = users.values.all { it }
        if (!enabled) {
            clearHistory()
            source = source.copy(epoch = source.epoch + 1)
            lastCapture = 0
        }
    }

    /**
     * What is playing, from the player's listener. Anything that makes the frames on their way
     * stale — another track, a seek, a pause, a speed change, another sink — starts a new epoch,
     * and frames of an older one are never shown.
     */
    @Synchronized fun source(
        track: String,
        playing: Boolean,
        positionMs: Long,
        speed: Float = 1f,
        discontinuity: Boolean = false,
        kind: AudioLightSource = AudioLightSource.NATIVE,
    ) {
        val old = source
        val changed = track != old.track || discontinuity || playing != old.playing ||
            speed != old.speed || kind != old.kind
        val epoch = if (changed) old.epoch + 1 else old.epoch
        source = Source(track, epoch, positionMs.coerceAtLeast(0), System.nanoTime(), playing, speed, kind)
        if (epoch != old.epoch) {
            clearHistory()
            lastCapture = 0
        }
    }

    /** The newest frame whose sound is being heard now, or `null` if there is none. */
    fun frame(nowNs: Long = System.nanoTime()): AudioLightFrame? {
        val current = source
        if (!enabled || !current.playing) return null
        synchronized(historyLock) {
            for (back in 0 until historySize) {
                val index = (historyEnd - 1 - back + HISTORY) % HISTORY
                val candidate = history[index] ?: continue
                if (candidate.generation == current.epoch &&
                    candidate.dueNs <= nowNs &&
                    nowNs - candidate.dueNs < STALE_NS
                ) {
                    return candidate
                }
            }
        }
        return null
    }

    /**
     * Copies a window of the sink's PCM for analysis. Never blocks the caller.
     *
     * `duplicate` and absolute reads leave the caller's buffer exactly as it was: content,
     * position, limit and byte order.
     */
    fun capture(
        buffer: ByteBuffer,
        bytes: Int,
        rate: Int,
        channels: Int,
        floatPcm: Boolean = false,
        queuedMs: Long = 0,
        kind: AudioLightSource = AudioLightSource.NATIVE,
    ) {
        // Old and new sinks can overlap during a source switch. Never wait on another audio
        // thread; drop only the visual samples on contention.
        if (!capturing.compareAndSet(false, true)) return
        try {
            captureWindow(buffer, bytes, rate, channels, floatPcm, queuedMs, kind)
        } finally {
            capturing.set(false)
        }
    }

    private fun captureWindow(
        buffer: ByteBuffer,
        bytes: Int,
        rate: Int,
        channels: Int,
        floatPcm: Boolean,
        queuedMs: Long,
        kind: AudioLightSource,
    ) {
        val current = source
        val now = System.nanoTime()
        if (kind != current.kind) return // The previous sink may still be draining after a switch.
        val previous = assembling
        if (previous != null &&
            (!enabled || previous.frame.generation != current.epoch || previous.rate != rate || previous.light != light)
        ) {
            assembling = null
            free.offer(previous)
        }
        val interval = if (light) LIGHT_INTERVAL_NS else FULL_INTERVAL_NS
        if (!enabled || !current.playing || rate <= 0 || channels !in 1..8 || now - lastCapture < interval) return

        val packet = assembling ?: (free.poll() ?: return).also {
            it.count = 0
            it.rate = rate
            it.light = light
            val delay = queuedMs.coerceIn(0, MAX_QUEUED_MS)
            it.frame = AudioLightFrame(
                track = current.track,
                generation = current.epoch,
                positionMs = current.position + (((now - current.at) / 1_000_000 + delay) * current.speed).toLong(),
                dueNs = now + delay * 1_000_000,
            )
            assembling = it
        }
        val samples = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val width = if (floatPcm) 4 else 2
        val frames = min(WINDOW - packet.count, min(bytes, samples.remaining()) / (channels * width))
        if (frames <= 0) return
        for (i in 0 until frames) {
            var sum = 0f
            for (channel in 0 until channels) {
                val index = samples.position() + (i * channels + channel) * width
                sum += if (floatPcm) {
                    samples.getFloat(index).takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
                } else {
                    samples.getShort(index) / 32768f
                }
            }
            packet.mono[packet.count + i] = sum / channels
        }
        packet.count += frames
        // The light mode measures whatever one buffer gave; the full one waits for a whole window.
        if (!packet.light && packet.count < WINDOW) return
        assembling = null
        lastCapture = now
        if (!pending.offer(packet)) free.offer(packet)
    }

    private fun startAnalysis() {
        Thread({
            val analyzer = AudioSpectrum()
            var epoch = -1L
            while (true) {
                val packet = pending.take()
                try {
                    if (!enabled || packet.frame.generation != source.epoch) continue
                    if (epoch != packet.frame.generation) {
                        analyzer.reset()
                        epoch = packet.frame.generation
                    }
                    val measured = analyzer.measure(packet.mono, packet.count, packet.rate, packet.light)
                    val frame = measured.copy(
                        track = packet.frame.track,
                        generation = packet.frame.generation,
                        positionMs = packet.frame.positionMs,
                        dueNs = packet.frame.dueNs,
                    )
                    if (enabled && frame.generation == source.epoch) remember(frame)
                } finally {
                    free.offer(packet)
                }
            }
        }, "audio-light").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    private fun remember(frame: AudioLightFrame) = synchronized(historyLock) {
        history[historyEnd] = frame
        historyEnd = (historyEnd + 1) % HISTORY
        if (historySize < HISTORY) historySize++
    }

    private fun clearHistory() = synchronized(historyLock) {
        history.fill(null)
        historyEnd = 0
        historySize = 0
    }

    /** Samples per analysis window. */
    private const val WINDOW = 2048
    private const val POOL = 4
    private const val HISTORY = 60
    private const val MAX_QUEUED_MS = 1_500L
    /** A frame older than this is not "now" any more, whatever came after it. */
    private const val STALE_NS = 1_000_000_000L
    private const val FULL_INTERVAL_NS = 33_333_333L
    private const val LIGHT_INTERVAL_NS = 100_000_000L
}

/** Tells [AudioReactive] what [player] is playing, for as long as it is attached. */
class AudioReactivePlayer(private val player: Player) : Player.Listener, AutoCloseable {
    init {
        player.addListener(this)
        refresh(false)
    }

    override fun onEvents(player: Player, events: Player.Events) =
        refresh(events.contains(Player.EVENT_POSITION_DISCONTINUITY))

    private fun refresh(reset: Boolean) = AudioReactive.source(
        player.currentMediaItem?.mediaId.orEmpty(),
        player.isPlaying,
        player.currentPosition,
        player.playbackParameters.speed,
        reset,
        if (player is LibrespotPlayer) AudioLightSource.NATIVE else AudioLightSource.MEDIA3,
    )

    override fun close() {
        player.removeListener(this)
        AudioReactive.source("", false, 0)
    }
}
