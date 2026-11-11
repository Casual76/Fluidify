package dev.lelonio.square.wear

import dev.pampa.fluidify.wear.protocol.DownloadRequest
import dev.pampa.fluidify.wear.protocol.WearCodec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer

/**
 * Durable FIFO owned by the bridge's single remote instance. Persistence and count share a lock;
 * sends have their own serial gate, so appends do not wait for an in-flight network operation.
 * An interrupted, unconfirmed send stays queued. Transport callbacks return success only after
 * a watch receipt confirms durable application; stable ids make retries idempotent.
 */
internal class PendingWatchDownloads(
    private val read: (String) -> String?,
    private val write: (String, String) -> Unit,
    private val broadcast: suspend (DownloadRequest) -> Boolean,
    private val send: suspend (String, DownloadRequest) -> Boolean,
    private val capture: () -> Scope?,
    private val current: (Scope) -> Boolean,
    private val online: (Scope) -> Boolean,
    private val use: (Scope, () -> Unit) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** A captured process capability; namespace persists, session is rebound only on a new drain. */
    data class Scope(val namespace: String, val session: String)
    private val state = Mutex()
    private val sending = Mutex()
    private val count = MutableStateFlow(0)
    val waiting = count.asStateFlow()
    private val serializer = ListSerializer(DownloadRequest.serializer())

    suspend fun refresh() = withContext(io) {
        val account = capture()
        state.withLock {
            if (account == null) count.value = 0
            else use(account) { count.value = load(account).size }
        }
    }
    fun accountChanged() { count.value = 0 }

    suspend fun request(request: DownloadRequest) {
        val account = capture() ?: return
        request(request, account)
    }
    suspend fun request(request: DownloadRequest, account: Scope) {
        // Once accepted, persist even if the caller is cancelled before transport can start.
        withContext(NonCancellable + io) {
            state.withLock {
                use(account) { save(account, load(account) + request.copy(accountSession = null, accountNamespace = null,
                    requestId = request.requestId ?: java.util.UUID.randomUUID().toString())) }
            }
        }
        withContext(io) {
            sending.withLock { drain(account, broadcast) }
        }
    }

    suspend fun flush(nodeId: String) = withContext(io) {
        val account = capture() ?: return@withContext
        sending.withLock { drain(account) { send(nodeId, it) } }
    }
    suspend fun flushBroadcast() = withContext(io) {
        val account = capture() ?: return@withContext
        sending.withLock { drain(account, broadcast) }
    }

    private suspend fun drain(account: Scope, deliver: suspend (DownloadRequest) -> Boolean) {
        if (!current(account) || !online(account)) return
        val batch = state.withLock { load(account) }
        for (request in batch) {
            currentCoroutineContext().ensureActive()
            if (!current(account) || !online(account)) return
            // Stop at the first failure: an unkeep must never overtake a failed keep.
            // Exceptions/cancellation leave this request and its suffix persisted.
            if (!deliver(request.copy(accountSession = account.session, accountNamespace = account.namespace))) return
            // Commit each success, not a final snapshot that could erase concurrent appends.
            // Acknowledged sends must be removed even when cancellation races the return.
            withContext(NonCancellable + io) {
                state.withLock {
                    use(account) { save(account, load(account).drop(1)) }
                }
            }
        }
    }

    private fun load(account: Scope): List<DownloadRequest> = read(account.namespace)?.let {
        WearCodec.json.decodeFromString(serializer, it)
    }.orEmpty()

    private fun save(account: Scope, requests: List<DownloadRequest>) {
        write(account.namespace, WearCodec.json.encodeToString(serializer, requests))
        count.value = if (current(account)) requests.size else 0
    }
}
