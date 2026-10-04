package dev.pampa.fluidify.wear.link

import android.util.Log
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import dev.pampa.fluidify.wear.WearApp
import dev.pampa.fluidify.wear.protocol.CommandAck
import dev.pampa.fluidify.wear.protocol.Hello
import dev.pampa.fluidify.wear.protocol.PlaybackSnapshot
import dev.pampa.fluidify.wear.protocol.RpcResponse
import dev.pampa.fluidify.wear.protocol.UpdateOffer
import dev.pampa.fluidify.wear.protocol.WearCodec
import dev.pampa.fluidify.wear.protocol.WearPaths
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Receives what the phone pushes: playback state, covers and replies.
 *
 * Woken by Play Services for Fluidify's own paths, with the app open or not, so
 * the watch is already up to date the moment the screen turns on. The work is
 * small and done inside the callback.
 */
class WatchListenerService : WearableListenerService() {

    private val app get() = application as WearApp

    override fun onDataChanged(events: DataEventBuffer) {
        val covers = mutableListOf<Pair<String, com.google.android.gms.wearable.Asset>>()
        events.forEach { event ->
            if (event.type != DataEvent.TYPE_CHANGED) return@forEach
            val item = event.dataItem
            val path = item.uri.path ?: return@forEach
            when {
                path == WearPaths.STATE -> item.data?.let { bytes ->
                    WearCodec.decodeOrNull(PlaybackSnapshot.serializer(), bytes)?.let {
                        if (app.state.accept(it)) app.link.onPhoneHeard()
                    }
                }
                path == WearPaths.ACCOUNT -> item.data?.let { bytes ->
                    WearCodec.decodeOrNull(dev.pampa.fluidify.wear.protocol.AccountState.serializer(), bytes)
                        ?.let { app.standalone.auth.onAccount(it) }
                }
                path.startsWith(WearPaths.ART_PREFIX) -> {
                    val key = path.removePrefix(WearPaths.ART_PREFIX)
                    val asset = runCatching { DataMapItem.fromDataItem(item).dataMap.getAsset(PhoneLink.ASSET_KEY) }.getOrNull()
                        ?: item.assets[PhoneLink.ASSET_KEY]?.let { com.google.android.gms.wearable.Asset.createFromRef(it.id) }
                    if (asset != null && !app.art.has(key)) covers += key to asset
                }
            }
        }
        if (covers.isEmpty()) return
        runBlocking {
            withTimeoutOrNull(BUDGET_MS) {
                for ((key, asset) in covers) {
                    runCatching { app.link.storeAsset(key, asset) }
                        .onFailure { Log.i(TAG, "cover $key failed: ${it.message}") }
                }
            }
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            WearPaths.UPDATE_OFFER -> WearCodec.decodeOrNull(UpdateOffer.serializer(), event.data)?.let { offer ->
                runBlocking { withTimeoutOrNull(BUDGET_MS) { app.updater.onOffer(event.sourceNodeId, offer) } }
            }
            WearPaths.ACK -> WearCodec.decodeOrNull(CommandAck.serializer(), event.data)?.let { app.link.onAck(it) }
            WearPaths.RPC_REPLY -> WearCodec.decodeOrNull(RpcResponse.serializer(), event.data)?.let {
                app.link.onPhoneHeard()
                app.link.onRpcReply(it)
            }
            WearPaths.HELLO -> WearCodec.decodeOrNull(Hello.serializer(), event.data)?.let {
                app.link.onHello(it, event.sourceNodeId)
                app.updater.onPhoneHello(event.sourceNodeId)
            }
            WearPaths.DOWNLOAD_PLAN -> WearCodec.decodeOrNull(dev.pampa.fluidify.wear.protocol.DownloadRequest.serializer(), event.data)?.let { request ->
                app.downloads.onRequest(request)
                runBlocking { withTimeoutOrNull(BUDGET_MS) { app.downloads.publish() } }
            }
            // "Continua sull'orologio" from the phone: the watch starts its engine and the
            // phone's music follows through Spotify Connect (see ActivePlayback.moveToWatch).
            // On the main thread: the watch's media controller belongs to the thread that builds
            // it, and this callback's thread is gone a few seconds after it returns.
            WearPaths.HANDOFF_TO_WATCH -> app.scope.launch { app.playback.moveToWatch(app.standalone.router.best, fromPhone = true) }
            WearPaths.AUTH_GRANT -> WearCodec.decodeOrNull(dev.pampa.fluidify.wear.protocol.AuthGrant.serializer(), event.data)
                ?.let { app.link.onAuthGrant(it) }
            // The phone signed out: so does the watch, and its own playback stops first.
            WearPaths.AUTH_LOGOUT -> app.scope.launch { app.playback.signedOut() }
        }
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        when {
            channel.path == WearPaths.UPDATE_APK ->
                runBlocking { withTimeoutOrNull(BUDGET_MS) { app.updater.onChannelOpened(channel) } }
            channel.path.startsWith(WearPaths.RPC_STREAM_PREFIX) ->
                runBlocking { withTimeoutOrNull(BUDGET_MS) { app.link.onRpcStream(channel) } }
        }
    }

    override fun onInputClosed(channel: ChannelClient.Channel, closeReason: Int, appSpecificErrorCode: Int) {
        if (channel.path != WearPaths.UPDATE_APK) return
        runBlocking { withTimeoutOrNull(INSTALL_BUDGET_MS) { app.updater.onInputClosed(channel, closeReason) } }
    }

    companion object {
        private const val TAG = "WatchListener"
        private const val BUDGET_MS = 8_000L
        private const val INSTALL_BUDGET_MS = 25_000L
    }
}
