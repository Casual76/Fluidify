// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.android.platform

import dev.lelonio.square.airpods.runtime.storage.PairAddress

enum class AudioOperation { CONNECT, DISCONNECT }
enum class AudioOrigin { MANUAL, UNWORN }
enum class PlatformFailure {
    STALE, PERMISSION, NOT_SELECTED_BONDED, CALL_OR_ROUTE_MIX, NOT_ENTITLED,
    UNWORN_NOT_AUTHORIZED, BUSY, CLOSED, PLATFORM_UNAVAILABLE, HIDDEN_API_UNAVAILABLE,
    SECURITY, REJECTED, NO_HANDLER, NO_CONTEXT, BACKGROUND_NOT_AUTHORIZED,
}
sealed interface PlatformResult {
    /** Request submitted only. Neither audio connection nor activity launch is confirmed. */
    data object RequestAccepted : PlatformResult
    data object Queued : PlatformResult
    data class Denied(val reason: PlatformFailure) : PlatformResult
    /** Data only: caller may offer Settings from an explicit visible manual action. */
    data class SettingsRequired(val reason: PlatformFailure) : PlatformResult
}
data class PlatformAction(
    val pair: PairAddress,
    val sessionGeneration: Long,
    val consentGeneration: Long,
    val issuedAtElapsed: Long,
)
data class AudioAuthorization(
    val selectedBonded: Boolean,
    val permission: Boolean,
    val controllerEntitled: Boolean,
    val noCallOrRouteMix: Boolean,
    val unwornPreference: Boolean = false,
    val validatedNone: Boolean = false,
)

/** Android implementation must also re-read permission/bond immediately before invocation. */
interface AudioProfilePort : AutoCloseable {
    fun ready(): Boolean
    fun prepare(onReady: () -> Unit): Boolean
    fun invoke(pair: PairAddress, operation: AudioOperation): PlatformResult
}

/** One pending action, never replaced by another pair. No polling/retry/replay.
 *
 * Lock order: an owner may hold its own locks before this monitor (controller gate ->
 * platformLock -> this), never the reverse. Under this monitor run only the live checks
 * (stillAuthorized/authorization, which must not take owner locks) and the profile, so the final
 * check and the platform invocation stay atomic against [close]. A `completed` callback is owner
 * code: it is delivered by the producing thread only after it has released every frame of this
 * monitor (including a synchronous prepare callback nested in [request]). */
class OrdinaryAudioOperations(
    private val profile: AudioProfilePort,
    private val elapsed: () -> Long,
    private val authorization: (PlatformAction) -> AudioAuthorization,
) : AutoCloseable {
    private data class Pending(
        val action: PlatformAction, val operation: AudioOperation, val origin: AudioOrigin,
        val stillAuthorized: () -> Boolean, val completed: (PlatformResult) -> Unit,
    )
    private var pending: Pending? = null
    private var closed = false
    /** Lock-free: an in-flight callback holding the monitor sees it before its platform call. */
    @Volatile private var revoked = false
    private val outbox = object : ThreadLocal<ArrayDeque<() -> Unit>>() {
        override fun initialValue() = ArrayDeque<() -> Unit>()
    }

    /** stillAuthorized must compare live pair, session AND consent generations and suppression. */
    fun request(
        action: PlatformAction, operation: AudioOperation, origin: AudioOrigin,
        stillAuthorized: () -> Boolean, completed: (PlatformResult) -> Unit = {},
    ): PlatformResult {
        return delivering { synchronized(this) { requestLocked(Pending(action, operation, origin, stillAuthorized, completed)) } }
    }

    private fun requestLocked(p: Pending): PlatformResult {
        if (closed || revoked) return PlatformResult.Denied(PlatformFailure.CLOSED)
        if (pending != null) return PlatformResult.Denied(PlatformFailure.BUSY)
        denied(p)?.let { return it }
        if (profile.ready()) return execute(p)
        pending = p
        val started = try { profile.prepare { available(p) } } catch (_: SecurityException) { false }
        if (!started && pending === p) {
            pending = null
            return fallback(p, PlatformFailure.PLATFORM_UNAVAILABLE)
        }
        return PlatformResult.Queued
    }

    private fun available(expected: Pending) = delivering {
        synchronized(this) {
            if (pending === expected && !closed) {
                pending = null
                val result = if (revoked) PlatformResult.Denied(PlatformFailure.STALE) else execute(expected)
                outbox.get().addLast { expected.completed(result) }
            }
        }
    }
    private fun denied(p: Pending): PlatformResult.Denied? {
        val now = elapsed()
        val reason = when {
            now < p.action.issuedAtElapsed || now - p.action.issuedAtElapsed > 5_000 -> PlatformFailure.STALE
            !runCatching(p.stillAuthorized).getOrDefault(false) -> PlatformFailure.STALE
            else -> {
                val a = runCatching { authorization(p.action) }.getOrNull()
                    ?: return PlatformResult.Denied(PlatformFailure.NOT_ENTITLED)
                when {
                    !a.permission -> PlatformFailure.PERMISSION
                    !a.selectedBonded -> PlatformFailure.NOT_SELECTED_BONDED
                    !a.controllerEntitled -> PlatformFailure.NOT_ENTITLED
                    !a.noCallOrRouteMix -> PlatformFailure.CALL_OR_ROUTE_MIX
                    p.origin == AudioOrigin.UNWORN && (p.operation != AudioOperation.DISCONNECT || !a.unwornPreference || !a.validatedNone) -> PlatformFailure.UNWORN_NOT_AUTHORIZED
                    else -> null
                }
            }
        }
        return reason?.let { PlatformResult.Denied(it) }
    }
    private fun execute(p: Pending): PlatformResult {
        denied(p)?.let { return it }
        // Stop/revocation may have begun while the live checks ran: last check before the platform.
        if (revoked) return PlatformResult.Denied(PlatformFailure.STALE)
        val result = try { profile.invoke(p.action.pair, p.operation) }
            catch (_: SecurityException) { PlatformResult.Denied(PlatformFailure.SECURITY) }
        return if (result is PlatformResult.Denied && result.reason in setOf(
                PlatformFailure.HIDDEN_API_UNAVAILABLE, PlatformFailure.PLATFORM_UNAVAILABLE,
                PlatformFailure.SECURITY, PlatformFailure.REJECTED)) fallback(p, result.reason) else result
    }
    private fun fallback(p: Pending, reason: PlatformFailure): PlatformResult =
        if (p.origin == AudioOrigin.MANUAL) PlatformResult.SettingsRequired(reason)
        else PlatformResult.Denied(reason)

    /** Call on stop/pair or consent change; also suitable for an existing host deadline. */
    fun cancelPending() = delivering { synchronized(this) { cancelLocked() } }
    private fun cancelLocked() {
        val old = pending ?: return
        pending = null
        outbox.get().addLast { old.completed(PlatformResult.Denied(PlatformFailure.STALE)) }
    }

    /** Withdraws authority without waiting for the monitor: no further platform invocation, even
     * from a callback already inside its live checks. Terminal; [close] must still follow. */
    fun revoke() { revoked = true }

    override fun close() {
        revoke()
        delivering {
            synchronized(this) {
                if (!closed) {
                    closed = true
                    cancelLocked()
                    profile.close()
                }
            }
        }
    }

    /** Runs [block], then this thread's completions, even if [block] throws. */
    private inline fun <T> delivering(block: () -> T): T {
        val value = try { block() } catch (t: Throwable) {
            runCatching { deliver() }.exceptionOrNull()?.let(t::addSuppressed)
            throw t
        }
        deliver()
        return value
    }

    /** Delivers this thread's completions once no frame of this monitor is held by it. */
    private fun deliver() {
        if (Thread.holdsLock(this)) return // the outermost frame (e.g. request around a sync prepare) delivers
        val queue = outbox.get()
        outbox.remove()
        var failure: Throwable? = null
        while (true) {
            val next = queue.removeFirstOrNull() ?: break
            try { next() } catch (t: Throwable) { failure?.addSuppressed(t) ?: run { failure = t } }
        }
        failure?.let { throw it }
    }
}
