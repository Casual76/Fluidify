package dev.pampa.fluidify.wear.protocol.logic

/**
 * Decides when a burst of playback changes is worth one message to the watch.
 *
 * A skip on the phone is not one change but five within a few milliseconds: the
 * item transitions, the position jumps, buffering starts, the metadata arrives,
 * buffering ends. Sending each would wake the watch five times for one fact.
 *
 * So the first change opens a short gathering window ([gatherMs]) and whatever
 * arrives inside it rides along; consecutive messages are also kept at least
 * [minIntervalMs] apart, and a change that lands inside that gap is not dropped
 * but sent at its end (the trailing edge), because the last state is the one
 * that matters. Changes that only matter eventually ([offer] with
 * `urgent = false`) wait [relaxedMs] instead and are folded into the next urgent
 * message if one comes first.
 *
 * Pure and clock-agnostic: the caller passes the time in and schedules the
 * returned deadline however it likes, which is what makes the timing testable.
 */
class StateCoalescer(
    private val gatherMs: Long = 150,
    private val minIntervalMs: Long = 250,
    private val relaxedMs: Long = 2_000,
) {
    private var lastSentAt: Long? = null
    private var pendingSince: Long? = null
    private var pendingUrgent = false

    /** True while a change is waiting to be sent. */
    val hasPending: Boolean get() = pendingSince != null

    /**
     * Records a change at [now] and returns the time it should be sent at.
     * Calling it again before then never moves the deadline later.
     */
    fun offer(now: Long, urgent: Boolean = true): Long {
        if (pendingSince == null) pendingSince = now
        pendingUrgent = pendingUrgent || urgent
        return dueAt()!!
    }

    /** When the pending change should go out, or null when nothing is pending. */
    fun dueAt(): Long? {
        val since = pendingSince ?: return null
        val window = since + if (pendingUrgent) gatherMs else relaxedMs
        val spacing = lastSentAt?.let { it + minIntervalMs } ?: Long.MIN_VALUE
        return maxOf(window, spacing)
    }

    /** True when a pending change should be sent at [now]. */
    fun isDue(now: Long): Boolean = dueAt()?.let { now >= it } ?: false

    /** The caller sent the current state at [now]; everything pending went with it. */
    fun markSent(now: Long) {
        lastSentAt = now
        pendingSince = null
        pendingUrgent = false
    }
}
