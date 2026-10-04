package dev.lelonio.square.io

import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Closes the channel from another thread if a blocking write stops making progress. */
class WriteWatchdog(
    private val output: OutputStream,
    timeoutMs: Long,
    deadlineMs: Long,
    private val closeChannel: () -> Unit,
) : OutputStream() {
    private val closed = AtomicBoolean()
    @Volatile private var lastByteAt = System.nanoTime()
    private val idleNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    private val deadline = lastByteAt + TimeUnit.MILLISECONDS.toNanos(deadlineMs)
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "wear-write-watchdog").apply { isDaemon = true }
    }
    init {
        executor.scheduleWithFixedDelay({
            val now = System.nanoTime()
            if (!closed.get() && (now - lastByteAt >= idleNanos || now >= deadline)) {
                runCatching { closeChannel() }
                runCatching { close() }
            }
        }, 100, 100, TimeUnit.MILLISECONDS)
    }
    override fun write(value: Int) { output.write(value); lastByteAt = System.nanoTime() }
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        output.write(bytes, offset, length)
        if (length > 0) lastByteAt = System.nanoTime()
    }
    override fun flush() = output.flush()
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdownNow()
            output.close()
        }
    }
}
