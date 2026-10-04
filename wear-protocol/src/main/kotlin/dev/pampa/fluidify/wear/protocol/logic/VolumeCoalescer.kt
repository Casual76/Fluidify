package dev.pampa.fluidify.wear.protocol.logic

/**
 * Turns a spinning bezel into a handful of volume messages.
 *
 * The bezel reports a detent every few milliseconds while it turns. Each one
 * moves a local target at once, so the arc on screen follows the hand, but the
 * phone is told at most every [intervalMs], always the latest absolute level,
 * and once more when the hand stops (the trailing send), so the phone ends up
 * exactly where the watch shows. Absolute levels also make a lost or repeated
 * message harmless.
 */
class VolumeCoalescer(
    private val intervalMs: Long = 100,
    private val step: Float = 1f / 30f,
) {
    var target: Float = 0f
        private set

    private var lastSentAt: Long? = null
    private var lastSentLevel: Float? = null
    private var dirty = false

    /** Adopts a level the phone reported, unless the hand is turning right now. */
    fun syncFromRemote(level: Float) {
        if (!dirty) {
            target = level.coerceIn(0f, 1f)
            lastSentLevel = null
        }
    }

    fun reset(level: Float) {
        dirty = false
        lastSentAt = null
        lastSentLevel = null
        target = level.coerceIn(0f, 1f)
    }

    /** One bezel event of [detents] steps (negative turns it down). Returns the new target. */
    fun turn(detents: Float): Float {
        target = (target + detents * step).coerceIn(0f, 1f)
        dirty = true
        return target
    }

    /** Sets the level directly (a drag on the arc). */
    fun set(level: Float): Float {
        target = level.coerceIn(0f, 1f)
        dirty = true
        return target
    }

    /** When the next send is due, or null when the phone already has [target]. */
    fun dueAt(): Long? {
        if (!dirty) return null
        val last = lastSentAt ?: return Long.MIN_VALUE
        return last + intervalMs
    }

    fun isDue(now: Long): Boolean = dueAt()?.let { now >= it } ?: false

    /** Takes the level to send at [now], or null when there is nothing new to say. */
    fun take(now: Long): Float? {
        if (!isDue(now)) return null
        dirty = false
        lastSentAt = now
        if (lastSentLevel == target) return null
        lastSentLevel = target
        return target
    }
}
