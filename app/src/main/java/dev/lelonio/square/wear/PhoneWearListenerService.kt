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
import kotlinx.coroutines.launch
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
 *
 * Except for questions (a Home, a playlist, a token), which may take long: Play
 * Services hands messages to this service one at a time, and a Home built from
 * cold held the thread for twenty seconds or more — a Pause pressed meanwhile
 * waited behind it, the watch gave up and said so, and the phone paused much
 * later. A question now runs on its own, with the callback waiting only a moment
 * for it, so the next message is taken at once; it is answered within its own
 * budget either way. Commands stay in the callback, in order.
 */
class PhoneWearListenerService : WearableListenerService() {

    private val bridge get() = (application as SquareApplication).wearBridge

    override fun onDataChanged(events: com.google.android.gms.wearable.DataEventBuffer) {
        events.forEach { event ->
            if (event.type != com.google.android.gms.wearable.DataEvent.TYPE_CHANGED) return@forEach
            val item = event.dataItem
            when (item.uri.path) {
                WearPaths.DOWNLOAD_STATUS -> item.data?.let(bridge.watchDownloads::onStatus)
                WearPaths.WATCH -> item.data?.let(bridge::onWatchSurfaces)
            }
        }
    }

    override fun onChannelOpened(channel: com.google.android.gms.wearable.ChannelClient.Channel) {
        // The watch asking for a track; sent on a coroutine of its own, see WatchFileServer.
        when (channel.path) {
            WearPaths.DOWNLOAD_FILE -> bridge.files.serve(channel)
            WearPaths.THUMBS -> bridge.thumbs.serve(channel)
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            WearPaths.AUDIO_LIGHT_SUBSCRIBE -> {
                val request = WearCodec.decodeOrNull(dev.pampa.fluidify.wear.protocol.AudioLightSubscription.serializer(), event.data) ?: return
                // Not waited for at all: the watch renews this every two seconds, the subscription
                // check inside takes up to a second and a half on the connected-nodes lookup, and
                // Play Services hands this service one message at a time, so a Pause or a Skip sent
                // meanwhile would queue behind a light show's lease. Nothing here is answered.
                val node = event.sourceNodeId
                val publisher = (application as dev.lelonio.square.SquareApplication).audioLightPublisher
                questions.launch { publisher.subscribe(node, request) }
            }
            WearPaths.HELLO -> {
                val hello = WearCodec.decodeOrNull(Hello.serializer(), event.data) ?: return
                handle { bridge.onHello(event.sourceNodeId, hello) }
            }
            WearPaths.COMMAND -> {
                val envelope = WearCodec.decodeOrNull(CommandEnvelope.serializer(), event.data)
                if (envelope != null) {
                    handle { bridge.onCommand(event.sourceNodeId, envelope) }
                } else {
                    // A command from a newer watch, of a kind registered nowhere here: the envelope
                    // does not decode, but its id is still readable, and "I cannot do that" sent
                    // back at once beats a watch waiting out its timeout to call the phone unreachable.
                    WearCodec.peekId(event.data)?.let { id -> handle { bridge.onUnsupportedCommand(event.sourceNodeId, id) } }
                }
            }
            WearPaths.RPC -> {
                val request = WearCodec.decodeOrNull(RpcRequest.serializer(), event.data)
                if (request != null) {
                    // Bounded inside: the answer goes back within the watch's patience, whatever happens.
                    handleAside { bridge.rpc.onRequest(event.sourceNodeId, request) }
                } else {
                    // The same for a question of a kind this phone does not know.
                    WearCodec.peekId(event.data)?.let { id -> handleAside { bridge.rpc.onUnsupported(event.sourceNodeId, id) } }
                }
            }
            WearPaths.AUTH_REQUEST -> {
                val request = WearCodec.decodeOrNull(dev.pampa.fluidify.wear.protocol.AuthRequest.serializer(), event.data) ?: return
                // A refresh over the phone's own network may take longer than an ordinary request.
                handleAside { withTimeoutOrNull(AUTH_BUDGET_MS) { bridge.auth.onRequest(event.sourceNodeId, request) } }
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

    /** Runs [block] on its own, waiting for it here only [INLINE_MS]: see the class's note. */
    private fun handleAside(block: suspend () -> Unit) {
        val job = questions.launch { block() }
        runBlocking { withTimeoutOrNull(INLINE_MS) { job.join() } }
    }

    companion object {
        private const val TAG = "PhoneWearListener"

        /** Where questions run, outliving any one callback. */
        private val questions = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

        /** Most questions are answered in this long, inside the callback as before. */
        private const val INLINE_MS = 1_500L

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
