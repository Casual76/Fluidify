package dev.lelonio.square.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** Main-thread ingress. A new selection supersedes pending work; additions after it stay FIFO. */
internal class I06PlaybackIntents<Owner : Any, Controller : Any>(
    private val scope: CoroutineScope,
    private val isOwnerCurrent: (Owner) -> Boolean,
    private val onFailure: (Throwable) -> Unit,
    private val connectionTimeoutMs: Long = 4_000L,
) {
    private class Request<O, C>(val owner: O, val lifetime: Long, val selection: Long,
        val apply: suspend (C, () -> Boolean) -> Unit)
    private val pending = ArrayDeque<Request<Owner, Controller>>()
    private var ready = CompletableDeferred<Controller?>()
    private var active = true // onCreate intents may precede onStart; execution still waits for connect.
    private var lifetime = 0L
    private var selection = 0L
    private var worker: Job? = null

    fun start() {
        if (active) return
        active = true
        ready = CompletableDeferred()
    }

    /** A new user attempt may recover a failed/disconnected binding; never replay an expired tap. */
    fun connecting() { if (active && ready.isCompleted) ready = CompletableDeferred() }
    fun connected(controller: Controller?) { if (active) ready.complete(controller) }

    fun stop() {
        active = false
        lifetime++
        pending.clear()
        ready.complete(null)
        worker?.cancel()
        worker = null
    }

    fun submit(owner: Owner, replacesQueue: Boolean = false,
        apply: suspend (Controller, () -> Boolean) -> Unit) {
        if (!active || !isOwnerCurrent(owner)) return
        if (replacesQueue) {
            selection++
            pending.clear()
            worker?.cancel()
            worker = null
        }
        pending.addLast(Request(owner, lifetime, selection, apply))
        drain()
    }

    private fun drain() {
        if (worker != null) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                while (pending.isNotEmpty()) {
                    val request = pending.removeFirst()
                    val current = {
                        active && request.lifetime == lifetime && request.selection == selection &&
                            isOwnerCurrent(request.owner)
                    }
                    try {
                        val controller = withTimeoutOrNull(connectionTimeoutMs) { ready.await() }
                        if (!current()) continue
                        if (controller == null) onFailure(IllegalStateException("Playback connection unavailable"))
                        else request.apply(controller, current)
                    } catch (cancelled: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        if (!active || request.lifetime != lifetime) throw cancelled
                        // An account/selection revocation is not a request to replay on the next owner.
                        if (current()) onFailure(cancelled)
                    } catch (failure: Exception) {
                        if (current()) onFailure(failure)
                    }
                }
            } finally {
                if (worker === currentCoroutineContext()[Job]) worker = null
            }
        }
        worker = job
        job.start()
    }
}
