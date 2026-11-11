package dev.lelonio.square.wear

import dev.pampa.fluidify.wear.protocol.DownloadPlanAck
import dev.pampa.fluidify.wear.protocol.DownloadRequest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Transport success is not plan application. Receipts must match id, account and live session. */
internal class WatchDownloadReceipts(private val current: (DownloadRequest) -> Boolean) {
    private data class Waiting(val request: DownloadRequest, val receipt: CompletableDeferred<Boolean>)
    private val pending = ConcurrentHashMap<String, Waiting>()
    fun onAck(ack: DownloadPlanAck) {
        val wait = pending[ack.requestId] ?: return
        if (ack.accountSession != wait.request.accountSession || ack.accountNamespace != wait.request.accountNamespace ||
            !current(wait.request)) return
        wait.receipt.complete(ack.accepted)
    }
    suspend fun deliver(request: DownloadRequest, send: suspend () -> Boolean): Boolean {
        val id = request.requestId ?: return false
        repeat(2) {
            if (!current(request)) return false
            val wait = Waiting(request, CompletableDeferred())
            pending[id] = wait
            try {
                if (!send()) return false
                if (withTimeoutOrNull(2_000L) { wait.receipt.await() } == true && current(request)) return true
            } finally { pending.remove(id, wait) }
        }
        return false
    }
}
