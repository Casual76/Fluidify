package dev.lelonio.square.wear

import dev.pampa.fluidify.wear.protocol.DownloadRequest
import dev.pampa.fluidify.wear.protocol.WatchDownloadOwner
import dev.pampa.fluidify.wear.protocol.WatchDownloads
import dev.pampa.fluidify.wear.protocol.WearCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Production status ingress, with account-scoped durable inventory and a current-session fence. */
internal class WatchDownloadStatusBoundary(
    private val capture: () -> PendingWatchDownloads.Scope?,
    private val use: (PendingWatchDownloads.Scope, () -> Unit) -> Unit,
    private val read: (String) -> String?,
    private val write: (String, String) -> Unit,
) {
    private val mutable = MutableStateFlow<WatchDownloads?>(null)
    val status = mutable.asStateFlow()
    fun accountChanged() {
        mutable.value = null
        val scope = capture() ?: return
        use(scope) {
            mutable.value = read(scope.namespace)?.let {
                WearCodec.decodeOrNull(WatchDownloads.serializer(), it.encodeToByteArray())
            }?.takeIf { it.accountNamespace == scope.namespace }
                ?.copy(accountSession = scope.session)
        }
    }
    fun onStatus(bytes: ByteArray) {
        val incoming = WearCodec.decodeOrNull(WatchDownloads.serializer(), bytes) ?: return
        val scope = capture() ?: return
        if (incoming.accountSession != scope.session || incoming.accountNamespace != scope.namespace) return
        use(scope) {
            if ((mutable.value?.updatedAtEpochMs ?: 0) > incoming.updatedAtEpochMs) return@use
            write(scope.namespace, WearCodec.json.encodeToString(WatchDownloads.serializer(), incoming))
            mutable.value = incoming
        }
    }
    fun optimistic(scope: PendingWatchDownloads.Scope, request: DownloadRequest) = use(scope) {
        request.owner?.let { owner ->
            val previous = mutable.value ?: WatchDownloads(accountSession = scope.session, accountNamespace = scope.namespace)
            mutable.value = previous.copy(owners = if (request.keep) {
                if (previous.owners.any { it.uri == owner }) previous.owners
                else previous.owners + WatchDownloadOwner(owner, request.title)
            } else previous.owners.filterNot { it.uri == owner })
        }
    }
}
