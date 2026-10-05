package dev.lelonio.square.io

import java.io.OutputStream

/**
 * Closes the channel from another thread if a blocking write stops making progress for
 * [timeoutMs], or once [deadlineMs] has passed overall. See [StallClock] for why the idle limit
 * only counts while a write is blocked.
 */
class WriteWatchdog(
    private val output: OutputStream,
    timeoutMs: Long,
    deadlineMs: Long,
    closeChannel: () -> Unit,
) : OutputStream() {
    private val clock = StallClock(timeoutMs, deadlineMs)
    private val guard = Guard(closeChannel) { output.close() }

    init {
        guard.task = Watchdogs.every(CHECK_MS) { if (!guard.isClosed && clock.stalled()) guard.trip() }
    }

    override fun write(value: Int) {
        clock.call { output.write(value) }
        clock.progressed()
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        clock.call { output.write(bytes, offset, length) }
        if (length > 0) clock.progressed()
    }

    override fun flush() = clock.call { output.flush() }

    override fun close() = guard.close()

    private companion object {
        const val CHECK_MS = 100L
    }
}
