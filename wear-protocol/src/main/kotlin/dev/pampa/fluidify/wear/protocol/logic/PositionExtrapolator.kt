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
 */
object PositionExtrapolator {

    /** Beyond this the two clocks are treated as unrelated. */
    const val MAX_SKEW_MS = 5_000L

    /**
     * The local-clock instant [snapshot]'s position was sampled at.
     *
     * [receivedAtLocalMs] is when the watch received it, on the watch's clock.
     */
    fun sampleTimeLocal(snapshot: PlaybackSnapshot, receivedAtLocalMs: Long): Long {
        val skew = receivedAtLocalMs - snapshot.sentAtEpochMs
        return if (abs(skew) <= MAX_SKEW_MS) {
            snapshot.sampledAtEpochMs
        } else {
            receivedAtLocalMs - (snapshot.sentAtEpochMs - snapshot.sampledAtEpochMs).coerceAtLeast(0)
        }
    }

    /** The position at [nowLocalMs], clamped to the track. */
    fun positionAt(snapshot: PlaybackSnapshot, receivedAtLocalMs: Long, nowLocalMs: Long): Long {
        val base = snapshot.positionMs
        val moving = snapshot.isPlaying && !snapshot.buffering
        val position = if (moving) {
            val elapsed = (nowLocalMs - sampleTimeLocal(snapshot, receivedAtLocalMs)).coerceAtLeast(0)
            base + (elapsed * snapshot.speed.toDouble()).toLong()
        } else {
            base
        }
        val duration = snapshot.track?.durationMs ?: 0
        return if (duration > 0) position.coerceIn(0, duration) else position.coerceAtLeast(0)
    }
}
