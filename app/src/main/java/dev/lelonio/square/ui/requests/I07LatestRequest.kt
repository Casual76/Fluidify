package dev.lelonio.square.ui.requests

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Main-thread request ownership for Radio and personalised shelves. Empty is an answer. */
internal class I07LatestRequest<T>(
    private val scope: CoroutineScope,
    private val publish: (State) -> Unit = {},
    private val timeoutMs: Long = 20_000L,
) {
    enum class Phase { IDLE, LOADING, FAILED, UNAVAILABLE }
    data class State(val key: String? = null, val phase: Phase = Phase.IDLE)
    private val ownership = RequestOwnership()
    private var job: Job? = null

    fun invalidate() {
        ownership.invalidate()
        job?.cancel()
        job = null
        publish(State())
    }

    fun unavailable(key: String) {
        invalidate()
        publish(State(key, Phase.UNAVAILABLE))
    }

    fun start(
        key: String,
        proof: I07RequestProof,
        read: suspend () -> T,
        useful: (T) -> Boolean = { true },
        accept: (T) -> Unit,
    ): Job? {
        invalidate()
        try { proof.ensureCurrent() } catch (_: Exception) { unavailable(key); return null }
        val ticket = ownership.begin()
        try { proof.publish { publish(State(key, Phase.LOADING)) } }
        catch (_: Exception) { unavailable(key); return null }
        return scope.launch {
            try {
                ticket.ensureCurrent()
                proof.ensureCurrent()
                val result = withTimeout(timeoutMs) { read() }
                currentCoroutineContext().ensureActive()
                proof.publish {
                    ticket.ensureCurrent()
                    if (useful(result)) {
                        publish(State())
                        accept(result)
                    } else publish(State(key, Phase.FAILED))
                }
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                reportFailure(ticket, proof, key)
            } catch (cancelled: CancellationException) {
                if (ticket.isCurrent()) publish(State())
                throw cancelled
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                reportFailure(ticket, proof, key)
            } finally {
                // Cancellation of this job cannot clear a successor's waiting/error message.
                if (!currentCoroutineContext()[Job]!!.isActive && ticket.isCurrent()) {
                    runCatching { proof.publish { ticket.ensureCurrent(); publish(State()) } }
                }
            }
        }.also { job = it }
    }

    private fun reportFailure(ticket: RequestOwnership.Ticket, proof: I07RequestProof, key: String) {
        // Revocation is not a failure belonging to the next account or network lease.
        runCatching { proof.publish { ticket.ensureCurrent(); publish(State(key, Phase.FAILED)) } }
            .onFailure { if (ticket.isCurrent()) publish(State()) }
    }
}
