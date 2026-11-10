// SPDX-License-Identifier: GPL-3.0-only
package dev.lelonio.square.bridge

import dev.lelonio.square.data.SpotifyNetworkPolicy
import java.util.concurrent.atomic.AtomicBoolean

/** One host lease, captured before token acquisition. Facts below are receipts, not policy. */
internal class BridgeNetworkOperation(
    val permit: SpotifyNetworkPolicy.Permit,
    private val accountFence: () -> Unit,
) {
    private val mutation = AtomicBoolean()
    private val reconciliation = AtomicBoolean()
    val writeUncertain: Boolean get() = mutation.get() || reconciliation.get()
    val writeAdmitted: Boolean get() = mutation.get()

    fun checkAccount() = accountFence()
    fun check() { accountFence(); permit.check() }

    suspend fun token(read: suspend (SpotifyNetworkPolicy.Permit) -> String): String {
        check()
        val answer = read(permit)
        check()
        return answer
    }

    /** Once per actual transport attempt. No IO under the gate. */
    fun admit(method: String) = permit.admit {
        accountFence()
        if (method !in setOf("GET", "HEAD", "OPTIONS")) mutation.set(true)
    }

    fun reconciling() { reconciliation.set(true) }

    /** Bounded synchronous publication only; never token, disk or network IO. */
    fun <T> publish(block: () -> T): T = permit.admit { accountFence(); block() }

    companion object {
        fun capture(fence: () -> Unit): BridgeNetworkOperation =
            BridgeNetworkOperation(SpotifyNetworkPolicy.capture(), fence).also { it.check() }
    }
}

/** Interceptors wrap account failures in IOException. Preserve the typed cause. */
internal fun Throwable.bridgeCauseCode(): String? = generateSequence(this) { it.cause }
    .take(16).firstNotNullOfOrNull { (it as? BridgeFailure)?.code }

internal fun bridgeFailureCode(error: Exception, operation: BridgeNetworkOperation?): String {
    error.bridgeCauseCode()?.let { return it }
    if (operation?.writeUncertain == true) return "write_uncertain"
    if (generateSequence<Throwable>(error) { it.cause }.take(16).any { it is SpotifyNetworkPolicy.Offline } ||
        (operation != null && !SpotifyNetworkPolicy.isCurrent(operation.permit.revision))) return "spotify_offline"
    return when ((error as? retrofit2.HttpException)?.code()) {
        401 -> "authentication_failed"
        403 -> "permission_denied"
        404 -> "source_not_found"
        429 -> "rate_limited"
        else -> "source_unavailable"
    }
}
