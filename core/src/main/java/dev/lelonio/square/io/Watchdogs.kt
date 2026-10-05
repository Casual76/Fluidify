package dev.lelonio.square.io

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The one thread that watches every guarded stream.
 *
 * Each watchdog used to start its own scheduled executor, so every channel to or from the watch
 * cost a thread for as long as it was open — and one that was never closed (an exception between
 * opening the stream and the `use`) leaked the thread for the life of the process. A check is a
 * couple of volatile reads every 100 ms; one thread does all of them.
 */
internal object Watchdogs {
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "stream-watchdog").apply { isDaemon = true }
    }

    fun every(periodMs: Long, check: () -> Unit): ScheduledFuture<*> =
        scheduler.scheduleWithFixedDelay({ runCatching(check) }, periodMs, periodMs, TimeUnit.MILLISECONDS)
}

/**
 * What both watchdogs measure: an idle limit that only counts while the guarded call is in
 * progress, and an overall deadline that always counts.
 *
 * Idle is measured *inside* the call on purpose. Counted from the last byte, a producer that was
 * simply slow to hand over the next chunk (reading it from disk, waiting on the network) looked
 * exactly like a peer that had stopped reading, and a healthy channel was closed under it.
 */
internal class StallClock(timeoutMs: Long, deadlineMs: Long) {
    @Volatile private var idleNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    @Volatile private var deadlineAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deadlineMs)
    @Volatile private var busySince = 0L
    @Volatile private var inCall = false

    fun reset(timeoutMs: Long, deadlineMs: Long) {
        idleNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val now = System.nanoTime()
        busySince = now
        deadlineAt = now + TimeUnit.MILLISECONDS.toNanos(deadlineMs)
    }

    fun <T> call(block: () -> T): T {
        busySince = System.nanoTime()
        inCall = true
        try {
            return block()
        } finally {
            inCall = false
        }
    }

    /** Some bytes moved: the peer is alive, so the idle count starts again. */
    fun progressed() {
        busySince = System.nanoTime()
    }

    fun stalled(): Boolean {
        val now = System.nanoTime()
        return now >= deadlineAt || (inCall && now - busySince >= idleNanos)
    }
}

/** Shared by both watchdogs: close the channel first, then the stream, once. */
internal class Guard(private val closeChannel: () -> Unit, private val closeStream: () -> Unit) {
    private val closed = AtomicBoolean()
    @Volatile var task: ScheduledFuture<*>? = null

    val isClosed: Boolean get() = closed.get()

    /** Initiates channel closure first: closing the stream itself may wait for the blocked call. */
    fun trip() {
        if (closed.get()) return
        runCatching { closeChannel() }
        close()
    }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            task?.cancel(false)
            closeStream()
        }
    }
}
