package dev.pampa.fluidify.wear.standalone

/**
 * How long to wait between looks at something the engine will have ready soon: quickly at first,
 * when it usually is, then less and less often, so that waiting for a start that is slow (a first
 * sign-in takes tens of seconds) is not a loop that wakes four times a second.
 */
internal object PollBackoff {
    private const val FIRST_MS = 250L
    private const val LONGEST_MS = 1_000L

    /** The wait after the [attempt]th look, counting from 0: 250, 500, then 1000 ms. */
    fun delayMs(attempt: Int): Long = (FIRST_MS shl attempt.coerceIn(0, MAX_DOUBLINGS)).coerceAtMost(LONGEST_MS)

    private const val MAX_DOUBLINGS = 2
}
