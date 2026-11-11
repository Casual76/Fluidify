package dev.lelonio.square.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serialises native ownership without holding a JVM monitor across suspension. */
internal class AccountLifecycle {
    private val mutex = Mutex()
    @Volatile var generation = 0L
        private set
    @Volatile var blocked = false
        private set

    @Synchronized fun invalidate(): Long {
        blocked = true
        return ++generation
    }
    @Synchronized fun activate() { blocked = false }
    fun current(ticket: Long) = !blocked && ticket == generation
    fun requireCurrent(ticket: Long) {
        if (!current(ticket)) throw CancellationException("Playback account changed")
    }
    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }
    suspend fun runCurrent(ticket: Long, block: suspend () -> Unit) = exclusive {
        if (current(ticket)) block()
    }
}
