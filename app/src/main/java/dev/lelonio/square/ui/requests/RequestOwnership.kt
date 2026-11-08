package dev.lelonio.square.ui.requests

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

/** Main-thread ownership, independent of URI: reopening A invalidates the old A. */
class RequestOwnership {
    private var generation = 0L
    fun invalidate() { generation++ }
    fun begin(): Ticket { invalidate(); return Ticket(generation) }
    inner class Ticket internal constructor(private val value: Long) {
        private var job: Job? = null
        fun bind(job: Job?) { this.job = job }
        fun isCurrent(): Boolean = generation == value && job?.isActive != false
        fun ensureCurrent() { if (!isCurrent()) throw CancellationException("Replaced request") }
    }
}
