// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods

/** Process-local Fluidify exclusion only. Does not inspect or control LibrePods.
 * Reserve BEFORE scheduling a service or repository factory. No platform call holds [lock]. */
class AirPodsTransportOwnership {
    enum class Owner { DIAGNOSTIC, PRODUCTION }
    companion object { val process = AirPodsTransportOwnership() }
    private val lock = Any()
    private var generation = 0L
    private var active: Lease? = null

    fun reserve(owner: Owner): Lease? = synchronized(lock) {
        if (active != null) null else Lease(owner, ++generation).also { active = it }
    }

    inner class Lease internal constructor(val owner: Owner, val generation: Long) : java.io.Closeable {
        private var constructing = false
        private var constructed = false
        private var stopping = false
        private var operations = 0
        private var raw: AacpTransport? = null
        private var closing = false
        private var closed = false
        val released: Boolean get() = synchronized(lock) { active !== this }

        /** At most one factory; queued callbacks after close never invoke it. A cancelled
         * blocking factory retains the lease until it returns and its result is closed. */
        fun construct(factory: () -> AacpTransport): AacpTransport {
            synchronized(lock) {
                check(active === this && !stopping && !constructed)
                constructed = true; constructing = true
            }
            try {
                val created = factory()
                synchronized(lock) { raw = created }
                val abandoned = synchronized(lock) { stopping }
                if (abandoned) { close(); error("STALE_TRANSPORT_LEASE") }
                return object : AacpTransport {
                    override fun connect() = operation { created.connect() }
                    override fun readSdu(): ByteArray? = operation { created.readSdu() }
                    override fun write(packet: ByteArray) = operation { created.write(packet) }
                    override fun close() = this@Lease.close()
                }
            } catch (failure: Throwable) {
                // Factory owns cleanup of resources it never returned. Once returned, this
                // lease retains any close failure, even if repository swallows the exception.
                synchronized(lock) { stopping = true }
                throw failure
            } finally {
                synchronized(lock) { constructing = false; releaseIfTerminal() }
            }
        }

        private fun <T> operation(block: () -> T): T {
            synchronized(lock) { check(active === this && !stopping); operations++ }
            try { return block() } finally {
                synchronized(lock) { operations--; releaseIfTerminal() }
            }
        }

        /** Close interrupts I/O, but successful close alone is insufficient while connect,
         * read, write or construction is still executing. Failed close retains ownership;
         * only a later explicit cleanup may retry it. No retry worker is scheduled here. */
        override fun close() {
            val target = synchronized(lock) {
                if (active !== this) return
                stopping = true
                if (closing || closed) { releaseIfTerminal(); return }
                raw?.also { closing = true } ?: run { releaseIfTerminal(); return }
            }
            var success = false
            try { target.close(); success = true } finally {
                synchronized(lock) { closing = false; closed = success; releaseIfTerminal() }
            }
        }

        private fun releaseIfTerminal() {
            if (active === this && stopping && !constructing && operations == 0 && !closing && (raw == null || closed)) active = null
        }
    }
}
