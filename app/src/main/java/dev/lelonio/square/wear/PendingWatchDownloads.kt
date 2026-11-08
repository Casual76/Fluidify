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
 * An interrupted, unconfirmed send stays queued: the existing protocol has no delivery receipt
 * with which to distinguish a lost message from a lost acknowledgement.
 */
internal class PendingWatchDownloads(
    private val read: () -> String?,
    private val write: (String) -> Unit,
    private val broadcast: suspend (DownloadRequest) -> Boolean,
    private val send: suspend (String, DownloadRequest) -> Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val state = Mutex()
    private val sending = Mutex()
    private val count = MutableStateFlow(0)
    val waiting = count.asStateFlow()
    private val serializer = ListSerializer(DownloadRequest.serializer())

    suspend fun refresh() = withContext(io) {
        state.withLock { count.value = load().size }
    }

    suspend fun request(request: DownloadRequest) {
        // Once accepted, persist even if the caller is cancelled before transport can start.
        withContext(NonCancellable + io) {
            state.withLock { save(load() + request) }
        }
        withContext(io) {
            sending.withLock { drain(broadcast) }
        }
    }

    suspend fun flush(nodeId: String) = withContext(io) {
        sending.withLock { drain { send(nodeId, it) } }
    }

    private suspend fun drain(deliver: suspend (DownloadRequest) -> Boolean) {
        val batch = state.withLock { load() }
        for (request in batch) {
            currentCoroutineContext().ensureActive()
            // Stop at the first failure: an unkeep must never overtake a failed keep.
            // Exceptions/cancellation leave this request and its suffix persisted.
            if (!deliver(request)) return
            // Commit each success, not a final snapshot that could erase concurrent appends.
            // Acknowledged sends must be removed even when cancellation races the return.
            withContext(NonCancellable + io) {
                state.withLock { save(load().drop(1)) }
            }
        }
    }

    private fun load(): List<DownloadRequest> = read()?.let {
        WearCodec.json.decodeFromString(serializer, it)
    }.orEmpty()

    private fun save(requests: List<DownloadRequest>) {
        write(WearCodec.json.encodeToString(serializer, requests))
        count.value = requests.size
    }
}
