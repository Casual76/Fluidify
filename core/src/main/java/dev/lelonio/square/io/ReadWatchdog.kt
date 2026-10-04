package dev.lelonio.square.io

import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A blocking read cannot observe coroutine timeouts. Close its channel from another thread. */
class ReadWatchdog(
    input: InputStream,
    timeoutMs: Long,
    private val closeChannel: () -> Unit,
) : InputStream() {
    private val source = input
    private val closed = AtomicBoolean()
    @Volatile private var lastByteAt = System.nanoTime()
    @Volatile private var timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "wear-read-watchdog").apply { isDaemon = true }
    }
    init {
        executor.scheduleWithFixedDelay({
            if (!closed.get() && System.nanoTime() - lastByteAt >= timeoutNanos) {
                // Initiate channel closure first: InputStream.close itself may wait for read().
                runCatching { closeChannel() }
                close()
            }
        }, 100, 100, TimeUnit.MILLISECONDS)
    }
    fun timeoutAfterProgress(timeoutMs: Long) {
        timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        lastByteAt = System.nanoTime()
    }
    override fun read(): Int = source.read().also { if (it >= 0) lastByteAt = System.nanoTime() }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
        source.read(bytes, offset, length).also { if (it > 0) lastByteAt = System.nanoTime() }
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdownNow()
            source.close()
        }
    }
}
