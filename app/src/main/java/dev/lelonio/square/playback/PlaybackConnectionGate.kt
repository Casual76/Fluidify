package dev.lelonio.square.playback

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred

/** Serializes new clients with service retirement, including native teardown after onDestroy. */
internal class PlaybackConnectionGate {
    class Retirement internal constructor(internal val ended: CompletableDeferred<Unit>)
    private val lock = Any()
    private var clients = 0
    private var retirement: Retirement? = null

    val hasClients: Boolean get() = synchronized(lock) { clients > 0 }

    fun retain(): Closeable {
        synchronized(lock) { clients++ }
        val closed = AtomicBoolean()
        return Closeable { if (closed.compareAndSet(false, true)) synchronized(lock) { clients-- } }
    }

    /** Idle may retire only when no client is acquiring/using the current session. */
    fun beginRetirement(force: Boolean = false): Retirement? = synchronized(lock) {
        retirement ?: if (!force && clients > 0) null
        else Retirement(CompletableDeferred()).also { retirement = it }
    }

    fun finish(value: Retirement) {
        synchronized(lock) { if (retirement === value) retirement = null }
        value.ended.complete(Unit)
    }

    suspend fun awaitReady() {
        while (true) {
            val ending = synchronized(lock) { retirement } ?: return
            ending.ended.await()
        }
    }

    suspend fun <T> withClient(block: suspend () -> T): T {
        val lease = retain()
        return try { awaitReady(); block() } finally { lease.close() }
    }
}
