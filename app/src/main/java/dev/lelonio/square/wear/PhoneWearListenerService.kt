package dev.lelonio.square.wear

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import dev.lelonio.square.SquareApplication
import dev.pampa.fluidify.wear.protocol.CommandEnvelope
import dev.pampa.fluidify.wear.protocol.Hello
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
            WearPaths.UPDATE_STATUS -> {
                val status = WearCodec.decodeOrNull(UpdateStatus.serializer(), event.data) ?: return
                bridge.updates.onStatus(event.sourceNodeId, status)
            }
            else -> Log.d(TAG, "ignored ${event.path}")
        }
    }

    private fun handle(block: suspend () -> Unit) {
        runBlocking {
            withTimeoutOrNull(CALLBACK_BUDGET_MS) { block() }
                ?: Log.w(TAG, "watch request did not finish in time")
        }
    }

    companion object {
        private const val TAG = "PhoneWearListener"

        /** Play Services gives a listener callback about ten seconds; leave room to spare. */
        private const val CALLBACK_BUDGET_MS = 9_000L
    }
}
