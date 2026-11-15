package dev.lelonio.square.data

import kotlinx.coroutines.CancellationException

/** Immutable authority for an explicit Connect gesture across discovery, queues and retries. */
class ConnectCommandLease(
    val permit: SpotifyNetworkPolicy.Permit,
    private val current: () -> Boolean,
    private val admission: (() -> Unit) -> Unit,
    private val requireNativeScope: Boolean = true,
) {
    fun check() {
        permit.check()
        if (!current()) throw CancellationException("Connect request authority changed")
    }

    fun isCurrent(): Boolean = runCatching { check() }.isSuccess

    fun admit(enter: () -> Unit) = admission { check(); enter() }

    fun <T> dispatch(stillCurrent: () -> Boolean = { true }, block: () -> T): T {
        fun checkReceipt() {
            check()
            if (!stillCurrent()) throw CancellationException("Connect request ended")
        }
        checkReceipt()
        val answer = permit.dispatchWithAdmission(admission = { enter ->
            admit { checkReceipt(); enter() }
        }, requireNativeScope = requireNativeScope) { checkReceipt(); block() }
        checkReceipt()
        return answer
    }
}
