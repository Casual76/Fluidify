package dev.pampa.fluidify.wear.system

import android.os.SystemClock
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import dev.lelonio.square.playback.AudioLightFrame
import dev.lelonio.square.playback.AudioReactive
import dev.pampa.fluidify.wear.WearApp
import dev.pampa.fluidify.wear.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.util.UUID

/** Exists independently of the autonomous engine. Only the visible UI calls run. */
class WatchAudioLight(private val app: WearApp) {
    @Volatile private var remote: AudioLightFrame? = null
    @Volatile private var receivedAt = 0L
    fun frame(local: Boolean): AudioLightFrame? = if (local) AudioReactive.frame()
        else remote?.takeIf { SystemClock.elapsedRealtime() - receivedAt < 1000 && it.track == app.controls.nowPlaying.value.snapshot?.track?.uri }

    suspend fun run(local: Boolean) {
        if (local) {
            AudioReactive.acquire(this, lightweight = true)
            try { awaitCancellation() } finally { AudioReactive.release(this) }
        }
        if (Features.AUDIO_LIGHT !in app.link.phone.value?.features.orEmpty()) return
        val node = app.link.audioLightPhone() ?: return
        val messages = Wearable.getMessageClient(app)
        val id = UUID.randomUUID().toString()
        val receiver = AudioLightReceiver()
        val alive = java.util.concurrent.atomic.AtomicBoolean(true)
        val listener = MessageClient.OnMessageReceivedListener { event ->
            if (!alive.get() || event.sourceNodeId != node || event.path != WearPaths.AUDIO_LIGHT_FRAME) return@OnMessageReceivedListener
            val packet = WearCodec.decodeOrNull(AudioLightTelemetry.serializer(), event.data) ?: return@OnMessageReceivedListener
            val now = app.controls.nowPlaying.value
            if (!receiver.accept(packet, id, now.snapshot?.track?.uri.orEmpty(), now.positionAt(System.currentTimeMillis()),
                System.currentTimeMillis(), now.received?.clockOffsetMs ?: 0)) return@OnMessageReceivedListener
            remote = AudioLightFrame(track = packet.track, generation = packet.generation, positionMs = packet.positionMs,
                energy = packet.energy, bass = packet.bass, mids = packet.mids, treble = packet.treble)
            receivedAt = SystemClock.elapsedRealtime()
        }
        try {
            messages.addListener(listener).await()
            while (true) {
                if (app.link.audioLightPhone() != node) break
                messages.sendMessage(node, WearPaths.AUDIO_LIGHT_SUBSCRIBE,
                    WearCodec.encode(AudioLightSubscription.serializer(), AudioLightSubscription(id, true))).await()
                delay(2000)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Static progress until the next visibility/link change. */ }
        finally {
            alive.set(false) // Ignore callbacks already queued when the UI/subscription closes.
            remote = null
            withContext(NonCancellable) {
                withTimeoutOrNull(500) { runCatching {
                    messages.removeListener(listener).await()
                    messages.sendMessage(node, WearPaths.AUDIO_LIGHT_SUBSCRIBE,
                        WearCodec.encode(AudioLightSubscription.serializer(), AudioLightSubscription(id, false))).await()
                } }
            }
        }
    }
}
