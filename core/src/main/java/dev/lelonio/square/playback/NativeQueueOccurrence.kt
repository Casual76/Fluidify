package dev.lelonio.square.playback

/** Maps the native queue window to a unique occurrence in the actual app order. */
internal fun nativeQueueOccurrence(queue: List<String>, window: List<String>, index: Int,
    uri: String): Int? {
    if (index !in window.indices || window[index] != uri || window.size > queue.size) return null
    val starts = (0..queue.size - window.size).filter { start ->
        window.indices.all { queue[start + it] == window[it] }
    }
    return starts.singleOrNull()?.plus(index)
}
