package dev.pampa.fluidify.wear.standalone

/**
 * How long to leave a cover alone after it could not be fetched: half a minute the first time,
 * twice as long each time after, never more than ten minutes. A song's cover is asked for by every
 * snapshot the player publishes, so a cover that cannot be had (offline, refused, not an image)
 * must not be asked for again at the pace of the snapshots.
 */
internal object ArtRetry {
    const val MIN_MS = 30_000L
    const val MAX_MS = 600_000L

    /** The wait after the [attempts]th failure in a row, counting from 1. */
    fun waitMs(attempts: Int): Long = (MIN_MS shl (attempts - 1).coerceIn(0, MAX_DOUBLINGS)).coerceAtMost(MAX_MS)

    /** Past this many doublings the cap is long reached; it also keeps the shift in range. */
    private const val MAX_DOUBLINGS = 10
}
