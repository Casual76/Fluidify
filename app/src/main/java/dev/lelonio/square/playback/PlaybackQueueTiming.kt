@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.lelonio.square.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import dev.lelonio.square.ui.EXTRA_QUEUE_CYCLE_ANCHOR
import kotlin.math.ceil

/** Immutable timing data captured with the displayed queue's player and account owner. */
internal class PlaybackQueueTiming private constructor(
    private val order: List<Int>,
    private val current: Int,
    private val anchor: Int,
    private val durations: List<Long?>,
    private val speed: Float,
    private val boundary: QueueDurationBoundary,
) {
    private val full = remainingQueueDuration(order, current, anchor, 0L, 1f, boundary, durations::getOrNull)

    /** A video may have a different length from the Spotify audio occurrence it temporarily replaces. */
    fun withCurrentDuration(milliseconds: Long): PlaybackQueueTiming {
        if (milliseconds <= 0 || durations.getOrNull(current) == milliseconds) return this
        return PlaybackQueueTiming(order, current, anchor,
            durations.mapIndexed { index, duration -> if (index == current) milliseconds else duration },
            speed, boundary)
    }

    fun remaining(positionMs: Long, playbackSpeed: Float = speed): QueueDuration? {
        val base = full ?: return null
        val elapsed = durations.getOrNull(current)?.takeIf { it > 0 }
            ?.let { positionMs.coerceIn(0L, it) } ?: 0L
        val rate = playbackSpeed.takeIf { it.isFinite() && it > 0f }?.toDouble() ?: 1.0
        return base.copy(milliseconds = ceil((base.milliseconds - elapsed).coerceAtLeast(0L) / rate).toLong())
    }

    companion object {
        fun capture(player: Player): PlaybackQueueTiming? {
            val timeline = player.currentTimeline
            val current = player.currentMediaItemIndex
            if (timeline.isEmpty || current !in 0 until timeline.windowCount) return null
            val order = buildList {
                var at = timeline.getFirstWindowIndex(player.shuffleModeEnabled)
                val visited = HashSet<Int>()
                while (at != C.INDEX_UNSET && visited.add(at)) {
                    add(at)
                    at = timeline.getNextWindowIndex(at, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
                }
            }
            val window = Timeline.Window()
            val durations = (0 until timeline.windowCount).map { index ->
                val item = timeline.getWindow(index, window).mediaItem
                item.mediaMetadata.durationMs?.takeIf { it > 0 }
                    ?: window.durationMs.takeIf { it > 0 && it != C.TIME_UNSET }
            }
            val anchor = (0 until timeline.windowCount).firstOrNull { index ->
                timeline.getWindow(index, window).mediaItem.mediaMetadata.extras
                    ?.getBoolean(EXTRA_QUEUE_CYCLE_ANCHOR) == true
            } ?: order.firstOrNull() ?: return null
            return PlaybackQueueTiming(order, current, anchor, durations, player.playbackParameters.speed,
                when (player.repeatMode) {
                    Player.REPEAT_MODE_ONE -> QueueDurationBoundary.REPEAT_TRACK
                    Player.REPEAT_MODE_ALL -> QueueDurationBoundary.REPEAT_CYCLE
                    else -> QueueDurationBoundary.END
                })
        }
    }
}
