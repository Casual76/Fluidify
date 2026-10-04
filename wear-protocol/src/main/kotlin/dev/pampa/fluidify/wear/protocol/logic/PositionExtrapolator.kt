package dev.pampa.fluidify.wear.protocol.logic

import dev.pampa.fluidify.wear.protocol.PlaybackSnapshot
import kotlin.math.abs

/**
 * Where the song is now, from the last thing the phone said about it.
 *
 * The phone never streams the position: it sends where the song was at
 * [PlaybackSnapshot.sampledAtEpochMs], on its own clock, and how fast it moves.
 * A paired watch takes its time from the phone, so the two clocks normally agree
 * within a few milliseconds and the sample time can be used as it is. When they
 * do not ([MAX_SKEW_MS], a watch whose clock has drifted or is set by hand), the
 * sample is re-anchored to the moment the snapshot arrived instead, which costs
 * the Bluetooth transit time (tens of milliseconds) and nothing else.
 *
 * Better than that, when the watch knows how far apart the clocks are (the
 * smallest gap seen between a snapshot's sending and its arrival, see
 * [ClockOffset]): a snapshot that arrives late — the watch back in range, out of
 * doze — is then not mistaken for a skewed clock. Re-anchored to its arrival,
 * it put the ring behind by the delay for the rest of the song.
 */
object PositionExtrapolator {

    /** Beyond this the two clocks are treated as unrelated. */
    const val MAX_SKEW_MS = 5_000L

    /**
     * The local-clock instant [snapshot]'s position was sampled at.
     *
     * [receivedAtLocalMs] is when the watch received it, on the watch's clock.
     */
    fun sampleTimeLocal(snapshot: PlaybackSnapshot, receivedAtLocalMs: Long, clockOffsetMs: Long? = null): Long {
        if (clockOffsetMs != null) {
            return if (abs(clockOffsetMs) <= MAX_SKEW_MS) snapshot.sampledAtEpochMs else snapshot.sampledAtEpochMs + clockOffsetMs
        }
        val skew = receivedAtLocalMs - snapshot.sentAtEpochMs
        return if (abs(skew) <= MAX_SKEW_MS) {
            snapshot.sampledAtEpochMs
        } else {
            receivedAtLocalMs - (snapshot.sentAtEpochMs - snapshot.sampledAtEpochMs).coerceAtLeast(0)
        }
    }

    /** The position at [nowLocalMs], clamped to the track. */
    fun positionAt(snapshot: PlaybackSnapshot, receivedAtLocalMs: Long, nowLocalMs: Long, clockOffsetMs: Long? = null): Long {
        val base = snapshot.positionMs
        val moving = snapshot.isPlaying && !snapshot.buffering
        val position = if (moving) {
            val elapsed = (nowLocalMs - sampleTimeLocal(snapshot, receivedAtLocalMs, clockOffsetMs)).coerceAtLeast(0)
            base + (elapsed * snapshot.speed.toDouble()).toLong()
        } else {
            base
        }
        val duration = snapshot.track?.durationMs ?: 0
        return if (duration > 0) position.coerceIn(0, duration) else position.coerceAtLeast(0)
    }

    /**
     * When, on the local clock, a playing [snapshot]'s song runs out; null when it is not moving or
     * has no length.
     */
    fun endsAtLocal(snapshot: PlaybackSnapshot, receivedAtLocalMs: Long, clockOffsetMs: Long? = null): Long? {
        val duration = snapshot.track?.durationMs ?: 0
        if (!snapshot.isPlaying || snapshot.buffering || duration <= 0 || snapshot.speed <= 0f) return null
        val left = (duration - snapshot.positionMs).coerceAtLeast(0)
        return sampleTimeLocal(snapshot, receivedAtLocalMs, clockOffsetMs) + (left / snapshot.speed).toLong()
    }

    /**
     * Whether a snapshot that says "playing" is too old to believe: its song would have ended more
     * than [graceMs] ago, and nothing has been heard since. The phone was killed, or went out of
     * range mid-song; shown as playing, the watch kept a pause button and a full ring for good.
     */
    fun isStale(snapshot: PlaybackSnapshot, receivedAtLocalMs: Long, nowLocalMs: Long, clockOffsetMs: Long? = null, graceMs: Long = STALE_GRACE_MS): Boolean {
        val end = endsAtLocal(snapshot, receivedAtLocalMs, clockOffsetMs) ?: return false
        return nowLocalMs > end + graceMs
    }

    /** Long enough for the next song's snapshot to arrive over a slow link. */
    const val STALE_GRACE_MS = 30_000L
}

/**
 * How far the watch's clock is from the phone's, learnt from the snapshots: the smallest gap seen
 * between one being sent and arriving. Transit only ever adds to that gap, so the smallest of the
 * last few is the clocks' difference plus the quickest delivery — a few milliseconds.
 */
class ClockOffset(private val window: Int = 8) {
    private val seen = ArrayDeque<Long>()

    /** Records a snapshot sent at [sentAtRemoteMs] (the phone's clock) and received at [receivedAtLocalMs]. */
    fun observe(sentAtRemoteMs: Long, receivedAtLocalMs: Long): Long {
        seen.addLast(receivedAtLocalMs - sentAtRemoteMs)
        while (seen.size > window) seen.removeFirst()
        return seen.min()
    }
}
