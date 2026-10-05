package dev.pampa.fluidify.wear.update

private val SHA256 = Regex("[a-fA-F0-9]{64}")

/** Whether this is a SHA-256 as hex: what an update must come with, from the phone or from the manifest. */
internal fun String.isSha256(): Boolean = matches(SHA256)

/**
 * Decides which progress of a transfer is worth reporting. Every report writes to the disk, posts
 * a notification and sends the phone a message over Bluetooth, and the transfer's own bytes are
 * what is slow, not the news of them: a report goes out for each [stepPercent] points, and always
 * for the first and the last.
 */
internal class ProgressThrottle(private val stepPercent: Int = 5, private val quietMs: Long = 5_000L) {
    private var lastPercent = -1
    private var lastAt = 0L

    /** Whether [percent] at [nowMs] is to be reported; it is remembered as the last one if so. */
    fun accept(percent: Int, nowMs: Long): Boolean {
        val worth = lastPercent < 0 ||
            percent == FULL ||
            percent - lastPercent >= stepPercent ||
            // A transfer that creeps still shows it is alive.
            (percent != lastPercent && nowMs - lastAt >= quietMs)
        if (!worth) return false
        lastPercent = percent
        lastAt = nowMs
        return true
    }

    private companion object {
        const val FULL = 100
    }
}
