package dev.lelonio.square.wear

import android.util.Log
import androidx.media3.common.C
import com.google.android.gms.wearable.Wearable
import dev.lelonio.square.SquareApplication
import dev.lelonio.square.data.RemoteConnect
import dev.lelonio.square.io.WriteWatchdog
import dev.pampa.fluidify.wear.protocol.AckErrors
import dev.pampa.fluidify.wear.protocol.DeviceInfo
import dev.pampa.fluidify.wear.protocol.DeviceList
import dev.pampa.fluidify.wear.protocol.QueueEntry
import dev.pampa.fluidify.wear.protocol.QueueWindow
import dev.pampa.fluidify.wear.protocol.RPC_MESSAGE_LIMIT
import dev.pampa.fluidify.wear.protocol.RpcMethod
import dev.pampa.fluidify.wear.protocol.RpcLimits
import dev.pampa.fluidify.wear.protocol.RpcRequest
import dev.pampa.fluidify.wear.protocol.RpcResponse
import dev.pampa.fluidify.wear.protocol.WearCodec
import dev.pampa.fluidify.wear.protocol.WearPaths
import dev.pampa.fluidify.wear.protocol.artKeyOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
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

    /** Where answers are worked out; see [onRequest]. */
    private val answers = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /**
     * Answers [request], always: within [budgetFor] the method, and with an error (or, for the
     * Home, the last one answered) when that runs out — sent even then, where a timeout used to
     * cancel the reply along with the work and the watch waited its full twelve seconds for nothing.
     */
    suspend fun onRequest(nodeId: String, request: RpcRequest) {
        // What the question asks for, held to what the phone allows: a negative count or an
        // enormous page from a watch is an exception or a lot of work here, not an answer.
        val method = RpcLimits.sanitised(request.method)
        val kind = method::class.simpleName
        // Not a child of this call: much of an answer is blocking native and network work that no
        // timeout can interrupt, and a child would hold the reply until it ended. Raced instead,
        // and left to finish on its own when it loses (a Home built late is still kept for next time).
        val work = answers.async { answer(method) }
        val response = try {
            val payload = withTimeoutOrNull(budgetFor(method)) { work.await() }
            if (payload != null || work.isCompleted) {
                RpcResponse(request.id, ok = true, payload = payload)
            } else {
                Log.w(TAG, "rpc $kind ran out of time")
                fallback(request.id, method) ?: RpcResponse(request.id, ok = false, error = "timeout")
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // The method's kind only: a search's words and the listener's playlists stay out of the log.
            Log.w(TAG, "rpc $kind failed: ${error.javaClass.simpleName}")
            // A code, not the exception's message: that stays on the phone.
            RpcResponse(request.id, ok = false, error = AckErrors.INTERNAL)
        }
        reply(nodeId, response)
    }

    /**
     * Says "I cannot answer that" to a question [onRequest] was never given because it could not be
     * read: a newer watch's method this phone has no name for. Only its [id] is known (see
     * [WearCodec.peekId]), and that is enough for the watch to stop waiting.
     */
    suspend fun onUnsupported(nodeId: String, id: Long) {
        reply(nodeId, RpcResponse(id, ok = false, error = AckErrors.UNSUPPORTED))
    }

    private suspend fun reply(nodeId: String, response: RpcResponse) {
        withContext(kotlinx.coroutines.NonCancellable) {
            val bytes = WearCodec.encode(RpcResponse.serializer(), response)
            if (bytes.size <= RPC_MESSAGE_LIMIT) {
                bridge.link.send(nodeId, WearPaths.RPC_REPLY, bytes)
            } else {
                stream(nodeId, response.id, WearCodec.gzip(bytes))
            }
        }
    }

    /** What stands in for an answer that did not come in time: the last Home, for the Home. */
    private fun fallback(id: Long, method: RpcMethod): RpcResponse? = when (method) {
        RpcMethod.Home -> library.cachedHome()?.let { RpcResponse(id, ok = true, payload = encode(dev.pampa.fluidify.wear.protocol.LibraryPage.serializer(), it)) }
        else -> null
    }

    /** Inside the watch's own timeouts (12 s for most, 20 s for a search), with room for the reply. */
    private fun budgetFor(method: RpcMethod): Long = when (method) {
        is RpcMethod.Search -> SEARCH_BUDGET_MS
        else -> ANSWER_BUDGET_MS
    }

    private suspend fun answer(method: RpcMethod): JsonElement? = when (method) {
        is RpcMethod.Queue -> encode(QueueWindow.serializer(), withContext(Dispatchers.Main.immediate) { queue(method) })
        RpcMethod.Devices -> encode(DeviceList.serializer(), devices())
        RpcMethod.Home -> encode(dev.pampa.fluidify.wear.protocol.LibraryPage.serializer(), library.home())
        is RpcMethod.Library -> encode(dev.pampa.fluidify.wear.protocol.LibraryPage.serializer(), library.section(method.section))
        is RpcMethod.Context -> encode(dev.pampa.fluidify.wear.protocol.ContextPage.serializer(), library.context(method.uri, method.offset, method.limit))
        is RpcMethod.Search -> encode(dev.pampa.fluidify.wear.protocol.LibraryPage.serializer(), library.search(method.query))
        is RpcMethod.Downloads -> encode(dev.pampa.fluidify.wear.protocol.PhoneDownloads.serializer(), phoneDownloads(method.uris))
        is RpcMethod.Liked -> encode(dev.pampa.fluidify.wear.protocol.LikedAnswer.serializer(), liked(method.uris))
    }

    private suspend fun liked(uris: List<String>): dev.pampa.fluidify.wear.protocol.LikedAnswer {
        val answers = uris.take(MAX_LIKED_QUERY).associateWith { uri -> catchingNonCancel { app.likedTracks.isLiked(uri) }.getOrNull() }
        return dev.pampa.fluidify.wear.protocol.LikedAnswer(
            liked = answers.filterValues { it == true }.keys.toList(),
            notLiked = answers.filterValues { it == false }.keys.toList(),
        )
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

    /**
     * The queue around what plays, in the order it will play: with shuffle on, ExoPlayer keeps the
     * items in their list order and plays them in another, and the watch was shown the list. Each
     * entry keeps its place in the list, which is what playing it from the watch refers to.
     */
    private fun queue(method: RpcMethod.Queue): QueueWindow {
        val player = bridge.currentPlayer ?: return QueueWindow(currentIndex = -1, total = 0, items = emptyList())
        val count = player.mediaItemCount
        if (count == 0) return QueueWindow(currentIndex = -1, total = 0, items = emptyList())
        val current = player.currentMediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: 0
        val timeline = player.currentTimeline
        val shuffle = player.shuffleModeEnabled
        val order = if (timeline.isEmpty) {
            ((current - method.before).coerceAtLeast(0)..(current + method.after).coerceAtMost(count - 1)).toList()
        } else {
            val before = generateSequence(current) { index ->
                timeline.getPreviousWindowIndex(index, androidx.media3.common.Player.REPEAT_MODE_OFF, shuffle).takeIf { it != C.INDEX_UNSET }
            }.drop(1).take(method.before).toList().reversed()
            val after = generateSequence(current) { index ->
                timeline.getNextWindowIndex(index, androidx.media3.common.Player.REPEAT_MODE_OFF, shuffle).takeIf { it != C.INDEX_UNSET }
            }.drop(1).take(method.after).toList()
            before + current + after
        }
        val items = order.filter { it in 0 until count }.map { index ->
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
        // On the main thread, where the player refreshes it too: RemoteConnect's maps are not
        // shared across threads, and a refresh from IO raced the player's.
        withContext(Dispatchers.Main.immediate) { runCatching { RemoteConnect.refresh() } }
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

    /**
     * Sends [gzipped] over a channel of its own, for an answer too big for a message.
     *
     * Closed both ways when done (closing the stream only ends one direction, and every large
     * answer used to leave a channel open until the devices disconnected), but not at once: the
     * watch reads to the end of the stream and closes its side, and the channel is closed here
     * only after that, or after a short grace; see [ChannelWrapUp]. Closed on the heels of the
     * last byte, the tail of a large answer could be lost with the channel.
     */
    private suspend fun stream(nodeId: String, id: Long, gzipped: ByteArray) {
        val channels = Wearable.getChannelClient(app)
        var wrapUp: ChannelWrapUp? = null
        var delivered = false
        try {
            val channel = channels.openChannel(nodeId, WearPaths.rpcStream(id)).await()
            wrapUp = ChannelWrapUp(channels, channel).also { it.start() }
            val raw = channels.getOutputStream(channel).await()
            WriteWatchdog(raw, STREAM_IDLE_MS, STREAM_DEADLINE_MS) { channels.close(channel) }.use { it.write(gzipped) }
            delivered = true
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "rpc stream $id failed: ${error.message}")
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) { wrapUp?.finish(delivered) }
        }
    }

    private fun <T> encode(serializer: KSerializer<T>, value: T): JsonElement =
        WearCodec.json.encodeToJsonElement(serializer, value)

    private companion object {
        const val TAG = "WearRpc"
        const val MAX_DOWNLOAD_QUERY = 200
        const val ANSWER_BUDGET_MS = 10_000L
        const val SEARCH_BUDGET_MS = 18_000L
        const val MAX_LIKED_QUERY = 20

        /** A stream that stops taking bytes for this long is a watch that went away. */
        const val STREAM_IDLE_MS = 30_000L
        const val STREAM_DEADLINE_MS = 2 * 60_000L
    }
}
