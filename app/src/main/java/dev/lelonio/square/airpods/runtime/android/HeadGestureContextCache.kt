// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.android

/** Refreshed independently of sensor delivery. invalidate fences an in-flight refresh as well
 * as the published snapshot. Readers perform only volatile reads, comparisons and clock math. */
internal class HeadGestureContextCache<T>(
    private val clock: () -> Long,
    private val maxAgeMs: Long = 250,
) {
    private data class Snapshot<T>(val value: T, val at: Long, val revision: Long)
    private val revision = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var snapshot: Snapshot<T>? = null
    fun invalidate() { revision.incrementAndGet(); snapshot = null }
    suspend fun refresh(read: suspend () -> T) {
        val version = revision.get()
        // Freshness starts at publish: a refresh blocked on a main-thread read must not
        val value = read()
        // publish an already-expired snapshot. The revision fence still rejects results
        // computed before an invalidation.
        if (revision.get() == version) snapshot = Snapshot(value, clock(), version)
    }
    fun current(): T? {
        val value = snapshot ?: return null
        return value.value.takeIf { value.revision == revision.get() && clock() - value.at in 0..maxAgeMs }
    }
}
