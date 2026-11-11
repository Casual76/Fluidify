package dev.lelonio.square.wear

import android.util.Log
import com.google.android.gms.wearable.Wearable
import dev.lelonio.square.SquareApplication
import dev.lelonio.square.data.AccountLocalDataCoordinator
import dev.pampa.fluidify.wear.protocol.DownloadRequest
import dev.pampa.fluidify.wear.protocol.WatchDownloads
import dev.pampa.fluidify.wear.protocol.WearCodec
import dev.pampa.fluidify.wear.protocol.WearPaths
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * The phone's view of the watch's downloads, and its way of asking for more.
 *
 * The watch keeps the list and does the work; the phone reads the DataItem the
 * watch keeps current ([status]) and asks with a message ([request]). A request
 * made while the watch is out of reach waits here and goes with the next hello.
 */
class WatchDownloadsRemote(private val app: SquareApplication, private val link: WearLink) {

    private val prefs = app.getSharedPreferences("watch_downloads_remote", android.content.Context.MODE_PRIVATE)
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private val receipts = WatchDownloadReceipts { request ->
        app.accountLocalData.captureOnline()?.let {
            it.requestSession == request.accountSession && it.namespace == request.accountNamespace
        } == true
    }
    fun onPlanAck(bytes: ByteArray) {
        WearCodec.decodeOrNull(dev.pampa.fluidify.wear.protocol.DownloadPlanAck.serializer(), bytes)?.let(receipts::onAck)
    }
    private val inventory = WatchDownloadStatusBoundary(
        capture = { capture()?.let { PendingWatchDownloads.Scope(it.namespace, it.requestSession) } },
        use = { scope, block ->
            val access = app.accountLocalData.captureRequest(scope.session)
            if (access != null && access.namespace == scope.namespace) app.accountLocalData.use(access, block)
        },
        read = { prefs.getString("status/$it", null) },
        write = { namespace, value -> check(prefs.edit().putString("status/$namespace", value).commit()) },
    )

    /** The watch's last word; null until a watch with downloads has spoken. */
    val status: StateFlow<WatchDownloads?> = inventory.status

    private val pending = PendingWatchDownloads(
        read = { namespace -> prefs.getString("$KEY_PENDING/$namespace", null) },
        write = { namespace, encoded ->
            if (!prefs.edit().putString("$KEY_PENDING/$namespace", encoded).commit()) {
                throw IOException("watch download requests could not be persisted")
            }
        },
        broadcast = { request ->
            receipts.deliver(request) { link.broadcast(WearPaths.DOWNLOAD_PLAN, WearCodec.encode(DownloadRequest.serializer(), request)) }
        },
        send = { nodeId, request ->
            receipts.deliver(request) { link.send(nodeId, WearPaths.DOWNLOAD_PLAN, WearCodec.encode(DownloadRequest.serializer(), request)) }
        },
        capture = { capture()?.let { PendingWatchDownloads.Scope(it.namespace, it.requestSession) } },
        current = { scope -> capture()?.let { it.namespace == scope.namespace && it.requestSession == scope.session } == true },
        online = { scope -> app.accountLocalData.captureOnline()?.let { it.namespace == scope.namespace && it.requestSession == scope.session } == true },
        use = { scope, block ->
            val access = app.accountLocalData.captureRequest(scope.session)
            if (access != null && access.namespace == scope.namespace) app.accountLocalData.use(access, block)
        },
    )

    init {
        app.accountLocalData.observe {
            inventory.accountChanged()
            pending.accountChanged()
            scope.launch { pending.refresh(); pending.flushBroadcast() }
        }
    }
    fun capture(): AccountLocalDataCoordinator.Access? = app.accountLocalData.capture()
    /**
     * How many requests are waiting for the watch to come back.
     *
     * A request made out of reach is kept and goes with the next hello, which is the right thing
     * and was also invisible: the list changed on screen as if the watch had been told. This is
     * what lets the Watch page say it has not been. Zero until [refresh] has read the preferences,
     * so that building this (with the bridge, in every process) does not touch the disk.
     */
    val waiting: StateFlow<Int> = pending.waiting

    fun keeps(uri: String): Boolean = status.value?.owners?.any { it.uri == uri } == true

    fun onStatus(bytes: ByteArray) = inventory.onStatus(bytes)

    /** Reads what the Data Layer already holds, for a phone that was away when it changed. */
    suspend fun refresh() {
        pending.refresh()
        // No watch with the companion: nothing to read, and no Data Layer call made.
        if (!link.hasWatch()) return
        catchingNonCancel {
            val items = Wearable.getDataClient(app).dataItems.await()
            try {
                items.firstOrNull { it.uri.path == WearPaths.DOWNLOAD_STATUS }?.data?.let(::onStatus)
            } finally {
                items.release()
            }
        }.onFailure { Log.i(TAG, "watch downloads not read: ${it.message}") }
    }

    /** Keep (or stop keeping) [uri] on the watch. Shown at once; the watch confirms with its next status. */
    suspend fun request(request: DownloadRequest, access: AccountLocalDataCoordinator.Access) {
        if (!app.accountLocalData.isCurrent(access)) return
        inventory.optimistic(PendingWatchDownloads.Scope(access.namespace, access.requestSession), request)
        pending.request(request, PendingWatchDownloads.Scope(access.namespace, access.requestSession))
    }

    /** What waited for the watch to come back. */
    suspend fun flushPending(nodeId: String) {
        pending.flush(nodeId)
    }

    private companion object {
        const val TAG = "WatchDownloadsRemote"
        const val KEY_PENDING = "pending"
    }
}
