package dev.lelonio.square.download

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Owns the pump until all its blocking calls and child jobs have really returned. */
internal class DownloadPump(
    private val scope: CoroutineScope,
    private val cancelNative: (String) -> Unit,
    private val onStarting: () -> Unit,
    private val onFinished: () -> Unit,
    private val canStart: () -> Boolean = { true },
    private val drain: suspend () -> Unit,
) {
    private val lock = Any()
    private var pump: Job? = null
    private var restartRequested = false
    private var joining = 0
    private val active = mutableMapOf<String, Job>()

    fun wake(): Unit = synchronized(lock) {
        if (joining != 0 || !canStart()) return@synchronized
        val old = pump
        if (old != null) {
            if (old.isCancelled) restartRequested = true
        } else startLocked()
    }

    private fun startLocked() {
        onStarting()
        val next = scope.launch(start = CoroutineStart.LAZY) { drain() }
        pump = next
        next.invokeOnCompletion {
            synchronized(lock) {
                // Completion includes every child, unlike a finally inside drain.
                val restart = restartRequested && joining == 0 && canStart()
                restartRequested = false
                pump = null
                // An explicit wake keeps the service and network watch alive
                // across the handoff; no transient idle emission can stop it.
                if (restart) startLocked() else onFinished()
            }
        }
        next.start()
    }

    fun stop() = synchronized(lock) { stopLocked() }

    /** Predicate reads only the caller's run identity, never an account/IO lock. */
    fun stopIf(expectedRun: () -> Boolean): Boolean = synchronized(lock) {
        if (!expectedRun()) false else { stopLocked(); true }
    }

    private fun stopLocked(): Job? {
        restartRequested = false
        val old = pump ?: return null
        old.cancel()
        // Rust clears its cancel flag on entry. Repeat until the blocking call
        // exits, covering stop between our ensureActive and native clear_cancel.
        // This worker is deliberately outside the cancelled pump, on its IO scope.
        scope.launch {
            while (true) {
                val any = synchronized(lock) {
                    val tracks = active.filterValues { it.isCancelled }.keys.toList()
                    for (uri in tracks) runCatching { cancelNative(uri) }
                    tracks.isNotEmpty()
                }
                if (!any) break
                delay(25)
            }
        }
        return old
    }

    /**
     * Suppresses wakes while joining. Return means no transfer or native cancel
     * from this pump can still touch the engine. Call before native shutdown;
     * after return only a new explicit wake starts work again.
     */
    suspend fun stopAndJoin() {
        val old = synchronized(lock) {
            joining++
            stopLocked()
        }
        try {
            old?.join()
        } finally {
            synchronized(lock) { joining-- }
        }
    }

    /** Call on an IO dispatcher: [block] is the actual blocking JNI operation. */
    suspend fun <T> transfer(uri: String, block: () -> T): T {
        val context = currentCoroutineContext()
        val job = context[Job]!!
        synchronized(lock) {
            context.ensureActive()
            check(uri !in active) { "Overlapping download for $uri" }
            active[uri] = job
        }
        try {
            val result = block()
            context.ensureActive()
            return result
        } catch (failure: Exception) {
            // Native cancellation may arrive as an ordinary JNI exception.
            context.ensureActive()
            throw failure
        } finally {
            // Also waits for an already executing cancelNative, so no late
            // cancellation can reach a subsequent transfer of this URI.
            synchronized(lock) { active.remove(uri) }
        }
    }
}
