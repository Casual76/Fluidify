package dev.lelonio.square.wear

import android.util.Log
import androidx.core.content.edit
import com.google.android.gms.wearable.Wearable
import dev.lelonio.square.SquareApplication
import dev.pampa.fluidify.wear.protocol.DownloadRequest
import dev.pampa.fluidify.wear.protocol.WatchDownloadOwner
import dev.pampa.fluidify.wear.protocol.WatchDownloads
import dev.pampa.fluidify.wear.protocol.WearCodec
import dev.pampa.fluidify.wear.protocol.WearPaths
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.builtins.ListSerializer

/**
 * The phone's view of the watch's downloads, and its way of asking for more.
 *
 * The watch keeps the list and does the work; the phone reads the DataItem the
 * watch keeps current ([status]) and asks with a message ([request]). A request
 * made while the watch is out of reach waits here and goes with the next hello.
 */
class WatchDownloadsRemote(private val app: SquareApplication, private val link: WearLink) {

    private val prefs = app.getSharedPreferences("watch_downloads_remote", android.content.Context.MODE_PRIVATE)
    private val _status = MutableStateFlow<WatchDownloads?>(null)

    /** The watch's last word; null until a watch with downloads has spoken. */
    val status: StateFlow<WatchDownloads?> = _status.asStateFlow()

    fun keeps(uri: String): Boolean = _status.value?.owners?.any { it.uri == uri } == true

    fun onStatus(bytes: ByteArray) {
        WearCodec.decodeOrNull(WatchDownloads.serializer(), bytes)?.let { _status.value = it }
    }

    /** Reads what the Data Layer already holds, for a phone that was away when it changed. */
    suspend fun refresh() {
        // No watch with the companion: nothing to read, and no Data Layer call made.
        if (!link.hasWatch()) return
        runCatching {
            val items = Wearable.getDataClient(app).dataItems.await()
            try {
                items.firstOrNull { it.uri.path == WearPaths.DOWNLOAD_STATUS }?.data?.let(::onStatus)
            } finally {
                items.release()
            }
        }.onFailure { Log.i(TAG, "watch downloads not read: ${it.message}") }
    }

    /** Keep (or stop keeping) [uri] on the watch. Shown at once; the watch confirms with its next status. */
    suspend fun request(request: DownloadRequest) {
        request.owner?.let { owner ->
            val current = _status.value ?: WatchDownloads()
            _status.value = current.copy(
                owners = if (request.keep) {
                    if (current.owners.any { it.uri == owner }) current.owners else current.owners + WatchDownloadOwner(owner, request.title)
                } else {
                    current.owners.filterNot { it.uri == owner }
                },
            )
        }
        val sent = link.broadcast(WearPaths.DOWNLOAD_PLAN, WearCodec.encode(DownloadRequest.serializer(), request))
        if (!sent) savePending(pending() + request)
    }

    /** What waited for the watch to come back. */
    suspend fun flushPending(nodeId: String) {
        val waiting = pending()
        if (waiting.isEmpty()) return
        val left = waiting.filterNot { link.send(nodeId, WearPaths.DOWNLOAD_PLAN, WearCodec.encode(DownloadRequest.serializer(), it)) }
        savePending(left)
    }

    private fun pending(): List<DownloadRequest> = runCatching {
        prefs.getString(KEY_PENDING, null)?.let {
            WearCodec.json.decodeFromString(ListSerializer(DownloadRequest.serializer()), it)
        }
    }.getOrNull().orEmpty()

    private fun savePending(requests: List<DownloadRequest>) {
        prefs.edit { putString(KEY_PENDING, WearCodec.json.encodeToString(ListSerializer(DownloadRequest.serializer()), requests)) }
    }

    private companion object {
        const val TAG = "WatchDownloadsRemote"
        const val KEY_PENDING = "pending"
    }
}
