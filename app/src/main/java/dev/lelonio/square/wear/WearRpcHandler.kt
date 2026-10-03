package dev.lelonio.square.wear

import android.util.Log
import androidx.media3.common.C
import com.google.android.gms.wearable.Wearable
import dev.lelonio.square.SquareApplication
import dev.lelonio.square.data.RemoteConnect
import dev.pampa.fluidify.wear.protocol.DeviceInfo
import dev.pampa.fluidify.wear.protocol.DeviceList
import dev.pampa.fluidify.wear.protocol.QueueEntry
import dev.pampa.fluidify.wear.protocol.QueueWindow
import dev.pampa.fluidify.wear.protocol.RPC_MESSAGE_LIMIT
import dev.pampa.fluidify.wear.protocol.RpcMethod
import dev.pampa.fluidify.wear.protocol.RpcRequest
import dev.pampa.fluidify.wear.protocol.RpcResponse
import dev.pampa.fluidify.wear.protocol.WearCodec
import dev.pampa.fluidify.wear.protocol.WearPaths
import dev.pampa.fluidify.wear.protocol.artKeyOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement

/**
 * Answers the watch's questions.
 *
 * Each answer is computed from what the phone already has in memory or on disk
 * wherever possible (the queue is the player's own timeline, the devices are the
 * Connect cluster the engine already follows), so a question from the wrist costs
 * the phone no network unless it genuinely needs one. Answers too large for a
 * message go back over a channel.
 */
class WearRpcHandler(private val app: SquareApplication, private val bridge: PhoneWearBridge) {

    private val library by lazy { WearLibrarySource(app) }

    suspend fun onRequest(nodeId: String, request: RpcRequest) {
        val response = runCatching { RpcResponse(request.id, ok = true, payload = answer(request.method)) }
            .getOrElse { error ->
                Log.w(TAG, "rpc ${request.method} failed", error)
                RpcResponse(request.id, ok = false, error = error.message ?: error.javaClass.simpleName)
            }
        val bytes = WearCodec.encode(RpcResponse.serializer(), response)
        if (bytes.size <= RPC_MESSAGE_LIMIT) {
            bridge.link.send(nodeId, WearPaths.RPC_REPLY, bytes)
        } else {
            stream(nodeId, request.id, WearCodec.gzip(bytes))
        }
    }

    private suspend fun answer(method: RpcMethod): JsonElement? = when (method) {
        is RpcMethod.Queue -> encode(QueueWindow.serializer(), withContext(Dispatchers.Main.immediate) { queue(method) })
        RpcMethod.Devices -> encode(DeviceList.serializer(), devices())
        RpcMethod.Home -> encode(dev.pampa.fluidify.wear.protocol.LibraryPage.serializer(), library.home())
        is RpcMethod.Library -> encode(dev.pampa.fluidify.wear.protocol.LibraryPage.serializer(), library.section(method.section))
        is RpcMethod.Context -> encode(dev.pampa.fluidify.wear.protocol.ContextPage.serializer(), library.context(method.uri, method.offset, method.limit))
        is RpcMethod.Search -> encode(dev.pampa.fluidify.wear.protocol.LibraryPage.serializer(), library.search(method.query))
        is RpcMethod.Downloads -> encode(dev.pampa.fluidify.wear.protocol.PhoneDownloads.serializer(), phoneDownloads(method.uris))
    }

    /**
     * What the phone has of these tracks: each one's sidecar (format, file id, key),
     * read from the native store without any network. The watch uses it to decide how
     * to fetch a track (see TransportPlanner) and, with the key, to fetch it from the
     * CDN without asking Spotify for a key of its own.
     */
    private suspend fun phoneDownloads(uris: List<String>) = withContext(Dispatchers.IO) {
        // The store may not have been told where it lives yet: this can run in a process
        // the listener service woke, with no engine started.
        runCatching { dev.lelonio.square.nativecore.NativeBridge.setDownloadRoot(app.downloads.root.absolutePath) }
        dev.pampa.fluidify.wear.protocol.PhoneDownloads(
            uris.take(MAX_DOWNLOAD_QUERY).map { uri ->
                val sidecar = runCatching { dev.lelonio.square.nativecore.NativeBridge.downloadState(uri) }.getOrNull()
                dev.pampa.fluidify.wear.protocol.PhoneDownload(uri, sidecar?.takeIf { it != "null" })
            },
        )
    }

    private fun queue(method: RpcMethod.Queue): QueueWindow {
        val player = bridge.currentPlayer ?: return QueueWindow(currentIndex = -1, total = 0, items = emptyList())
        val count = player.mediaItemCount
        val current = player.currentMediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: 0
        val from = (current - method.before).coerceAtLeast(0)
        val to = (current + method.after).coerceAtMost(count - 1)
        val items = if (count == 0) emptyList() else (from..to).map { index ->
            val item = player.getMediaItemAt(index)
            val art = item.mediaMetadata.artworkUri?.toString()
            QueueEntry(
                index = index,
                uri = item.mediaId,
                title = item.mediaMetadata.title?.toString().orEmpty(),
                artist = item.mediaMetadata.artist?.toString().orEmpty(),
                artKey = artKeyOf(art),
                artUrl = art?.takeIf { it.startsWith("https://") },
            )
        }
        return QueueWindow(currentIndex = current, total = count, items = items)
    }

    private suspend fun devices(): DeviceList {
        // The cluster the engine follows; a refresh first, so a speaker switched on a
        // moment ago is in the list.
        withContext(Dispatchers.IO) { runCatching { RemoteConnect.refresh() } }
        val phone = withContext(Dispatchers.Main.immediate) { bridge.currentDevice() }
        val others = RemoteConnect.devices.value.filterNot { it.isThisPhone }.map { device ->
            DeviceInfo(
                id = device.id,
                name = device.name,
                kind = PhoneWearBridge.kindOf(device.type),
                isThisPhone = false,
                volume = device.volume / 65535f,
                canSetVolume = true,
            )
        }
        val thisPhone = if (phone.isThisPhone) phone else phone.copy(
            id = PhoneWearBridge.PHONE_DEVICE_ID,
            name = android.os.Build.MODEL,
            isThisPhone = true,
        )
        val activeId = if (RemoteConnect.elsewhereActive.value) {
            RemoteConnect.devices.value.firstOrNull { it.active && !it.isThisPhone }?.id
        } else {
            PhoneWearBridge.PHONE_DEVICE_ID
        }
        return DeviceList(devices = listOf(thisPhone.copy(id = PhoneWearBridge.PHONE_DEVICE_ID)) + others, activeId = activeId)
    }

    private suspend fun stream(nodeId: String, id: Long, gzipped: ByteArray) {
        runCatching {
            val channels = Wearable.getChannelClient(app)
            val channel = channels.openChannel(nodeId, WearPaths.rpcStream(id)).await()
            channels.getOutputStream(channel).await().use { it.write(gzipped) }
        }.onFailure { Log.w(TAG, "rpc stream $id failed", it) }
    }

    private fun <T> encode(serializer: KSerializer<T>, value: T): JsonElement =
        WearCodec.json.encodeToJsonElement(serializer, value)

    private companion object {
        const val TAG = "WearRpc"
        const val MAX_DOWNLOAD_QUERY = 200
    }
}
