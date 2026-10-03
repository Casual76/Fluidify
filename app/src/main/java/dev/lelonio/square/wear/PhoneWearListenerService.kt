package dev.lelonio.square.wear

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import dev.lelonio.square.SquareApplication
import dev.pampa.fluidify.wear.protocol.CommandEnvelope
import dev.pampa.fluidify.wear.protocol.Hello
import dev.pampa.fluidify.wear.protocol.RpcRequest
import dev.pampa.fluidify.wear.protocol.UpdateStatus
import dev.pampa.fluidify.wear.protocol.WearCodec
import dev.pampa.fluidify.wear.protocol.WearPaths
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where the watch's messages land when Fluidify is not on screen, or not running at all.
 *
 * Play Services starts this service for a message on one of the app's paths (the
 * manifest filters on [WearPaths.PREFIX]) and keeps the process alive for the
 * duration of the callback. So the work is done inside the callback, bounded,
 * rather than handed to a scope that would be frozen with the process the
 * moment the callback returns.
 */
class PhoneWearListenerService : WearableListenerService() {

    private val bridge get() = (application as SquareApplication).wearBridge

    override fun onDataChanged(events: com.google.android.gms.wearable.DataEventBuffer) {
        events.forEach { event ->
            if (event.type != com.google.android.gms.wearable.DataEvent.TYPE_CHANGED) return@forEach
            val item = event.dataItem
            if (item.uri.path == WearPaths.DOWNLOAD_STATUS) item.data?.let(bridge.watchDownloads::onStatus)
        }
    }

    override fun onChannelOpened(channel: com.google.android.gms.wearable.ChannelClient.Channel) {
        // The watch asking for a track; sent on a coroutine of its own, see WatchFileServer.
        if (channel.path == WearPaths.DOWNLOAD_FILE) bridge.files.serve(channel)
    }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            WearPaths.HELLO -> {
                val hello = WearCodec.decodeOrNull(Hello.serializer(), event.data) ?: return
                handle { bridge.onHello(event.sourceNodeId, hello) }
            }
            WearPaths.COMMAND -> {
                val envelope = WearCodec.decodeOrNull(CommandEnvelope.serializer(), event.data) ?: return
                handle { bridge.onCommand(event.sourceNodeId, envelope) }
            }
            WearPaths.RPC -> {
                val request = WearCodec.decodeOrNull(RpcRequest.serializer(), event.data) ?: return
                handle { bridge.rpc.onRequest(event.sourceNodeId, request) }
            }
            WearPaths.AUTH_REQUEST -> {
                val request = WearCodec.decodeOrNull(dev.pampa.fluidify.wear.protocol.AuthRequest.serializer(), event.data) ?: return
                // A refresh over the phone's own network may take longer than an ordinary request.
                handle(AUTH_BUDGET_MS) { bridge.auth.onRequest(event.sourceNodeId, request) }
            }
            WearPaths.UPDATE_STATUS -> {
                val status = WearCodec.decodeOrNull(UpdateStatus.serializer(), event.data) ?: return
                bridge.updates.onStatus(event.sourceNodeId, status)
            }
            else -> Log.d(TAG, "ignored ${event.path}")
        }
    }

    private fun handle(budgetMs: Long = CALLBACK_BUDGET_MS, block: suspend () -> Unit) {
        runBlocking {
            withTimeoutOrNull(budgetMs) { block() }
                ?: Log.w(TAG, "watch request did not finish in time")
        }
    }

    companion object {
        private const val TAG = "PhoneWearListener"

        /** Play Services gives a listener callback about ten seconds; leave room to spare. */
        private const val CALLBACK_BUDGET_MS = 9_000L

        /**
         * Longer than a callback is promised, on purpose: the service is still running
         * while this blocks, and a token the watch waits for is worth the risk of the
         * system reclaiming a callback it thinks has hung. The watch gives up at 20 s.
         */
        private const val AUTH_BUDGET_MS = 18_000L
    }
}
