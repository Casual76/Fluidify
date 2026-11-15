package dev.lelonio.square.ui.player

/** Resolve the current row and its neighbour again at action time, as the drag path does. */
internal fun i13MoveQueueBy(
    queue: List<QueueEntry>,
    index: Int,
    direction: Int,
    onMove: (Int, Int) -> Unit,
): Boolean {
    val at = queue.indexOfFirst { it.index == index }
    if (at < 0 || queue[at].isCurrent || (direction != -1 && direction != 1)) return false
    val destination = queue.getOrNull(at + direction) ?: return false
    onMove(index, destination.index)
    return true
}
