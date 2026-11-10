package dev.lelonio.square.widget.media

/** Accessed under the bridge render lock. A resize also invalidates any pending artwork render. */
internal class WidgetArtworkEpoch {
    private var value = 0L
    fun next(): Long = ++value
    fun isCurrent(token: Long): Boolean = token == value
}
