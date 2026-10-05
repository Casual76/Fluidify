package dev.pampa.fluidify.wear.system

import android.os.SystemClock
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import dev.lelonio.square.playback.AudioLightFrame
import dev.lelonio.square.playback.AudioReactive
import dev.pampa.fluidify.wear.WearApp
import dev.pampa.fluidify.wear.protocol.AudioLightReceiver
import dev.pampa.fluidify.wear.protocol.AudioLightSubscription
import dev.pampa.fluidify.wear.protocol.AudioLightTelemetry
import dev.pampa.fluidify.wear.protocol.Features
import dev.pampa.fluidify.wear.protocol.WearCodec
import dev.pampa.fluidify.wear.protocol.WearPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Exists independently of the autonomous engine. Only the visible UI calls run. */
class WatchAudioLight(private val app: WearApp) {
    @Volatile private var remote: AudioLightFrame? = null
    @Volatile private var receivedAt = 0L

    private val _arrivals = MutableStateFlow(0L)

    /**
     * Counts the frames that arrive from the phone. A screen with nothing to draw waits on this
     * for the next one instead of looking every so often (frames from the watch's own engine have
     * no such signal, and are looked at; see [frame]).
     */
    val arrivals: StateFlow<Long> = _arrivals.asStateFlow()

    fun frame(local: Boolean): AudioLightFrame? = if (local) {
        AudioReactive.frame()
    } else {
        remote?.takeIf {
            SystemClock.elapsedRealtime() - receivedAt < FRAME_FRESH_MS && it.track == app.controls.nowPlaying.value.snapshot?.track?.uri
        }
    }

    suspend fun run(local: Boolean) {
        if (local) {
            AudioReactive.acquire(this, lightweight = true)
            try {
                awaitCancellation()
            } finally {
                AudioReactive.release(this)
            }
        }
        if (Features.AUDIO_LIGHT !in app.link.phone.value?.features.orEmpty()) return
        val node = app.link.reachablePhone() ?: return
        val messages = Wearable.getMessageClient(app)
        val id = UUID.randomUUID().toString()
        val receiver = AudioLightReceiver()
        val alive = AtomicBoolean(true)
        val listener = MessageClient.OnMessageReceivedListener { event ->
            if (!alive.get() || event.sourceNodeId != node || event.path != WearPaths.AUDIO_LIGHT_FRAME) return@OnMessageReceivedListener
            val packet = WearCodec.decodeOrNull(AudioLightTelemetry.serializer(), event.data) ?: return@OnMessageReceivedListener
            val now = app.controls.nowPlaying.value
            val accepted = receiver.accept(
                packet,
                id,
                now.snapshot?.track?.uri.orEmpty(),
                now.positionAt(System.currentTimeMillis()),
                System.currentTimeMillis(),
                now.received?.clockOffsetMs ?: 0,
            )
            if (!accepted) return@OnMessageReceivedListener
            remote = AudioLightFrame(
                track = packet.track,
                generation = packet.generation,
                positionMs = packet.positionMs,
                energy = packet.energy,
                bass = packet.bass,
                mids = packet.mids,
                treble = packet.treble,
            )
            receivedAt = SystemClock.elapsedRealtime()
            _arrivals.value++
        }
        try {
            messages.addListener(listener).await()
            // The phone keeps the subscription alive for as long as it is renewed, which is what
            // this loop is for; the check that the phone is still there is a lookup in what the
            // link already knows, not a round trip to Play Services every two seconds.
            while (app.link.stillPhone(node)) {
                messages.sendMessage(node, WearPaths.AUDIO_LIGHT_SUBSCRIBE, subscription(id, true)).await()
                delay(RENEW_MS)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Static progress until the next visibility/link change.
        } finally {
            alive.set(false) // Ignore callbacks already queued when the UI/subscription closes.
            remote = null
            withContext(NonCancellable) {
                withTimeoutOrNull(UNSUBSCRIBE_WAIT_MS) {
                    runCatching {
                        messages.removeListener(listener).await()
                        messages.sendMessage(node, WearPaths.AUDIO_LIGHT_SUBSCRIBE, subscription(id, false)).await()
                    }
                }
            }
        }
    }

    private fun subscription(id: String, enabled: Boolean): ByteArray =
        WearCodec.encode(AudioLightSubscription.serializer(), AudioLightSubscription(id, enabled))

    private companion object {
        /** A frame older than this is the phone gone quiet, not music to draw. */
        const val FRAME_FRESH_MS = 1_000L

        /** How often the phone is told the watch still wants frames: its lease is short. */
        const val RENEW_MS = 2_000L
        const val UNSUBSCRIBE_WAIT_MS = 500L
    }
}
