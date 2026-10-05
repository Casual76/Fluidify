package dev.lelonio.square.playback

import androidx.media3.common.Player
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

/** Visual measurements only. Nothing here changes PCM or waits for the renderer. */
data class AudioLightFrame(
    val track: String = "", val generation: Long = 0, val positionMs: Long = 0,
    val dueNs: Long = 0, val energy: Float = 0f, val bass: Float = 0f,
    val mids: Float = 0f, val treble: Float = 0f, val bands: List<Float> = List(8) { 0f },
)
enum class AudioLightSource { NATIVE, MEDIA3 }

class AudioSpectrum {
    private val real = DoubleArray(2048)
    private val imaginary = DoubleArray(2048)
    private var reference = 0.08
    private var low = 0.0
    private val cosines by lazy { DoubleArray(1024) { cos(-2 * PI * it / 2048) } }
    private val sines by lazy { DoubleArray(1024) { sin(-2 * PI * it / 2048) } }
    private val window by lazy { DoubleArray(2048) { .5 - .5 * cos(2 * PI * it / 2047) } }
    private val edges = doubleArrayOf(30.0, 80.0, 180.0, 400.0, 900.0, 2000.0, 4500.0, 9000.0, 20000.0)
    fun reset() { reference = 0.08; low = 0.0 }

    fun measure(samples: FloatArray, count: Int, rate: Int, light: Boolean): AudioLightFrame {
        if (count <= 0 || rate <= 0) return AudioLightFrame()
        var squares = 0.0; var lowSquares = 0.0
        val alpha = 1 - exp(-2 * PI * 250 / rate)
        for (i in 0 until count) {
            val s = samples[i].toDouble(); squares += s * s
            low += alpha * (s - low); lowSquares += low * low
        }
        val rms = sqrt(squares / count)
        // A floor prevents silence and quiet intros being inflated by AGC.
        reference = max(0.08, max(rms, reference * 0.995))
        fun level(x: Double): Float = if (rms < 0.002) 0f else
            (sqrt((x / reference).coerceIn(0.0, 1.0)) * (rms / 0.015).coerceAtMost(1.0)).toFloat()
        val energy = level(rms)
        if (light) return AudioLightFrame(energy = energy, bass = level(sqrt(lowSquares / count)))
        real.fill(0.0); imaginary.fill(0.0)
        for (i in 0 until min(count, 2048)) real[i] = samples[i] * window[i]
        var j = 0
        for (i in 1 until 2048) {
            var bit = 1024
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val t = real[i]; real[i] = real[j]; real[j] = t }
        }
        var size = 2
        while (size <= 2048) {
            val half = size / 2
            for (base in 0 until 2048 step size) for (k in 0 until half) {
                val twiddle = k * 2048 / size
                val r = real[base + k + half] * cosines[twiddle] - imaginary[base + k + half] * sines[twiddle]
                val im = real[base + k + half] * sines[twiddle] + imaginary[base + k + half] * cosines[twiddle]
                real[base + k + half] = real[base + k] - r
                imaginary[base + k + half] = imaginary[base + k] - im
                real[base + k] += r; imaginary[base + k] += im
            }
            size *= 2
        }
        val bands = (0..7).map { b ->
            var sum = 0.0
            for (bin in max(1, ceil(edges[b] * 2048 / rate).toInt()) until min(1024, ceil(edges[b + 1] * 2048 / rate).toInt()))
                sum += real[bin] * real[bin] + imaginary[bin] * imaginary[bin]
            level(sqrt(sum) * 2 / count)
        }
        return AudioLightFrame(energy = energy, bass = bands.take(3).max(),
            mids = bands.slice(3..5).average().toFloat(), treble = bands.takeLast(2).average().toFloat(), bands = bands)
    }
}

/** One process owns one audible source. Subscribers are independent of playback ownership. */
object AudioReactive {
    private data class Source(val track: String, val epoch: Long, val position: Long, val at: Long, val playing: Boolean, val speed: Float, val kind: AudioLightSource)
    private class Packet {
        val mono = FloatArray(2048)
        var count = 0; var rate = 0; var light = false; var frame = AudioLightFrame()
    }
    private val users = ConcurrentHashMap<Any, Boolean>()
    @Volatile private var enabled = false

    /** Whether anyone is showing the light, so a sink can skip measuring for it. */
    val isEnabled: Boolean get() = enabled
    @Volatile private var light = true
    // Monotonic boot time also distinguishes a restarted phone process while
    // the watch keeps its subscription alive; starting again at zero would
    // make the receiver reject every new generation as an older playback.
    @Volatile private var source = Source("", System.nanoTime().coerceAtLeast(0), 0, System.nanoTime(), false, 1f, AudioLightSource.NATIVE)
    private val free = ArrayBlockingQueue<Packet>(4).apply { repeat(4) { add(Packet()) } }
    private val pending = ArrayBlockingQueue<Packet>(4)
    private val history = java.util.concurrent.CopyOnWriteArrayList<AudioLightFrame>()
    @Volatile private var lastCapture = 0L
    private var assembling: Packet? = null // Only the PCM producer thread touches this window.
    private val started = java.util.concurrent.atomic.AtomicBoolean()
    private val capturing = java.util.concurrent.atomic.AtomicBoolean()

    @Synchronized fun acquire(owner: Any, lightweight: Boolean = false) {
        users[owner] = lightweight; light = users.values.all { it }; enabled = true
        if (started.compareAndSet(false, true)) Thread({
            val analyzer = AudioSpectrum(); var epoch = -1L
            while (true) {
                val p = pending.take()
                try {
                    if (!enabled || p.frame.generation != source.epoch) continue
                    if (epoch != p.frame.generation) { analyzer.reset(); epoch = p.frame.generation }
                    val measured = analyzer.measure(p.mono, p.count, p.rate, p.light)
                    val f = measured.copy(track = p.frame.track, generation = p.frame.generation,
                        positionMs = p.frame.positionMs, dueNs = p.frame.dueNs)
                    if (enabled && f.generation == source.epoch) {
                        history.add(f)
                        while (history.size > 60) history.removeAt(0)
                    }
                } finally { free.offer(p) }
            }
        }, "audio-light").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }
    @Synchronized fun release(owner: Any) {
        users.remove(owner); enabled = users.isNotEmpty(); light = users.values.all { it }
        if (!enabled) { history.clear(); source = source.copy(epoch = source.epoch + 1); lastCapture = 0 }
    }
    @Synchronized fun source(track: String, playing: Boolean, positionMs: Long, speed: Float = 1f, discontinuity: Boolean = false, kind: AudioLightSource = AudioLightSource.NATIVE) {
        val old = source
        val epoch = if (track != old.track || discontinuity || playing != old.playing || speed != old.speed || kind != old.kind) old.epoch + 1 else old.epoch
        source = Source(track, epoch, positionMs.coerceAtLeast(0), System.nanoTime(), playing, speed, kind)
        if (epoch != old.epoch) { history.clear(); lastCapture = 0 }
    }
    fun frame(nowNs: Long = System.nanoTime()): AudioLightFrame? {
        val s = source
        if (!enabled || !s.playing) return null
        return history.lastOrNull { it.generation == s.epoch && it.dueNs <= nowNs && nowNs - it.dueNs < 1_000_000_000 }
    }
    /** duplicate + absolute reads preserve the caller's content, position, limit and byte order. */
    fun capture(buffer: ByteBuffer, bytes: Int, rate: Int, channels: Int, floatPcm: Boolean = false, queuedMs: Long = 0, kind: AudioLightSource = AudioLightSource.NATIVE) {
        // Old and new sinks can overlap during a source switch. Never wait on
        // another audio thread; drop only the visual samples on contention.
        if (!capturing.compareAndSet(false, true)) return
        try { captureWindow(buffer, bytes, rate, channels, floatPcm, queuedMs, kind) }
        finally { capturing.set(false) }
    }
    private fun captureWindow(buffer: ByteBuffer, bytes: Int, rate: Int, channels: Int, floatPcm: Boolean, queuedMs: Long, kind: AudioLightSource) {
        val s = source; val now = System.nanoTime()
        if (kind != s.kind) return // The previous sink may still be draining after a source switch.
        val previous = assembling
        if (previous != null && (!enabled || previous.frame.generation != s.epoch || previous.rate != rate || previous.light != light)) {
            assembling = null; free.offer(previous)
        }
        if (!enabled || !s.playing || rate <= 0 || channels !in 1..8 || now - lastCapture < if (light) 100_000_000 else 33_333_333) return
        val packet = assembling ?: (free.poll() ?: return).also {
            it.count = 0; it.rate = rate; it.light = light
            val delay = queuedMs.coerceIn(0, 1500)
            it.frame = AudioLightFrame(track = s.track, generation = s.epoch,
                positionMs = s.position + (((now - s.at) / 1_000_000 + delay) * s.speed).toLong(), dueNs = now + delay * 1_000_000)
            assembling = it
        }
        val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val width = if (floatPcm) 4 else 2
        val frames = min(2048 - packet.count, min(bytes, b.remaining()) / (channels * width))
        if (frames <= 0) return
        for (i in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                val index = b.position() + (i * channels + c) * width
                sum += if (floatPcm) b.getFloat(index).takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f else b.getShort(index) / 32768f
            }
            packet.mono[packet.count + i] = sum / channels
        }
        packet.count += frames
        if (!packet.light && packet.count < 2048) return
        assembling = null
        lastCapture = now
        if (!pending.offer(packet)) free.offer(packet)
    }
}

class AudioReactivePlayer(private val player: Player) : Player.Listener, AutoCloseable {
    init { player.addListener(this); refresh(false) }
    override fun onEvents(player: Player, events: Player.Events) = refresh(events.contains(Player.EVENT_POSITION_DISCONTINUITY))
    private fun refresh(reset: Boolean) = AudioReactive.source(player.currentMediaItem?.mediaId.orEmpty(),
        player.isPlaying, player.currentPosition, player.playbackParameters.speed, reset,
        if (player is LibrespotPlayer) AudioLightSource.NATIVE else AudioLightSource.MEDIA3)
    override fun close() { player.removeListener(this); AudioReactive.source("", false, 0) }
}
