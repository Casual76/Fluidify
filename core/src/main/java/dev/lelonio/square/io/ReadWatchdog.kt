package dev.lelonio.square.io

import java.io.InputStream

/**
 * A blocking read cannot observe coroutine timeouts: this closes its channel from another thread
 * when a read waits longer than [timeoutMs] for a byte, or when [deadlineMs] has passed overall.
 * See [StallClock] for why the idle limit only counts while a read is waiting.
 */
class ReadWatchdog(
    input: InputStream,
    timeoutMs: Long,
    deadlineMs: Long = timeoutMs,
    closeChannel: () -> Unit,
) : InputStream() {
    private val source = input
    private val clock = StallClock(timeoutMs, deadlineMs)
    private val guard = Guard(closeChannel) { source.close() }

    init {
        guard.task = Watchdogs.every(CHECK_MS) { if (!guard.isClosed && clock.stalled()) guard.trip() }
    }

    /** From here on, a new idle limit and a new overall deadline: for the body after a header. */
    fun timeoutAfterProgress(timeoutMs: Long, deadlineMs: Long = timeoutMs) = clock.reset(timeoutMs, deadlineMs)

    override fun read(): Int = clock.call { source.read() }.also { if (it >= 0) clock.progressed() }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
        clock.call { source.read(bytes, offset, length) }.also { if (it > 0) clock.progressed() }

    override fun close() = guard.close()

    private companion object {
        const val CHECK_MS = 100L
    }
}
