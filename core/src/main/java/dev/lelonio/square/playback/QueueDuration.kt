package dev.lelonio.square.playback

import kotlin.math.ceil

enum class QueueDurationBoundary { END, REPEAT_CYCLE, REPEAT_TRACK }

/** A lower bound when at least one upcoming track has no known duration. */
data class QueueDuration(
    val milliseconds: Long,
    val incomplete: Boolean,
    val boundary: QueueDurationBoundary,
)

/**
 * [playOrder] is the actual order, including shuffle, rather than catalogue order.
 * The anchor is an occurrence index: identical tracks can occur more than once.
 */
fun remainingQueueDuration(
    playOrder: List<Int>,
    currentIndex: Int,
    cycleAnchorIndex: Int,
    positionMs: Long,
    speed: Float,
    boundary: QueueDurationBoundary,
    durationAt: (Int) -> Long?,
): QueueDuration? {
    val start = playOrder.indexOf(currentIndex)
    if (start < 0 || playOrder.size != playOrder.toSet().size) return null
    val indices = when (boundary) {
        QueueDurationBoundary.REPEAT_TRACK -> listOf(currentIndex)
        QueueDurationBoundary.END -> playOrder.drop(start)
        QueueDurationBoundary.REPEAT_CYCLE -> {
            if (cycleAnchorIndex !in playOrder) return null
            buildList {
                for (offset in playOrder.indices) {
                    val index = playOrder[(start + offset) % playOrder.size]
                    if (offset > 0 && index == cycleAnchorIndex) break
                    add(index)
                }
            }
        }
    }
    var total = 0L
    var incomplete = false
    indices.forEachIndexed { offset, index ->
        val duration = durationAt(index)?.takeIf { it > 0 }
        if (duration == null) {
            incomplete = true
        } else {
            val remaining = if (offset == 0) {
                (duration - positionMs.coerceIn(0L, duration)).coerceAtLeast(0L)
            } else duration
            total = if (Long.MAX_VALUE - total < remaining) Long.MAX_VALUE else total + remaining
        }
    }
    val rate = speed.takeIf { it.isFinite() && it > 0f }?.toDouble() ?: 1.0
    return QueueDuration(ceil(total.toDouble() / rate).toLong(), incomplete, boundary)
}
