package dev.lelonio.square.wear

import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import com.google.android.gms.wearable.Wearable
import dev.lelonio.square.SquareApplication
import dev.lelonio.square.playback.AudioReactive
import dev.pampa.fluidify.wear.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

class AudioLightPublisher(private val app: SquareApplication) {
    private val leases = AudioLightLeases()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sending: Job? = null
    suspend fun subscribe(node: String, request: AudioLightSubscription) = withContext(Dispatchers.Main.immediate) {
        if (request.id.length !in 1..64) return@withContext
        if (!request.enabled) { leases.request(node, request, false, SystemClock.elapsedRealtime()); return@withContext }
        val nearby = withTimeoutOrNull(1500) { Wearable.getNodeClient(app).connectedNodes.await() }
            ?.any { it.id == node && it.isNearby } == true
        if (!nearby) return@withContext
        leases.request(node, request, nearby, SystemClock.elapsedRealtime())
        if (sending?.isActive == true) return@withContext
        sending = scope.launch {
            // The phone computes all bands; only aggregates are sent to the watch.
            AudioReactive.acquire(this@AudioLightPublisher)
            try {
                var checkedAt = 0L; var near = emptySet<String>()
                while (!leases.isEmpty()) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - checkedAt >= 1000) {
                        near = withTimeoutOrNull(1000) { Wearable.getNodeClient(app).connectedNodes.await() }
                            ?.filter { it.isNearby }?.map { it.id }?.toSet().orEmpty()
                        checkedAt = now
                    }
                    val allowed = app.getSystemService(PowerManager::class.java)?.isPowerSaveMode == false &&
                        Settings.Global.getFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
                    val recipients = leases.recipients(now, near)
                    if (!allowed || recipients.isEmpty()) break
                    val f = AudioReactive.frame()
                    if (f != null) for ((node, id) in recipients) {
                        val packet = AudioLightTelemetry(id, System.nanoTime(), f.track, f.generation,
                            f.positionMs, f.energy, f.bass, f.mids, f.treble,
                            System.currentTimeMillis() - (System.nanoTime() - f.dueNs) / 1_000_000)
                        try {
                            withTimeoutOrNull(150) { Wearable.getMessageClient(app).sendMessage(node,
                                WearPaths.AUDIO_LIGHT_FRAME, WearCodec.encode(AudioLightTelemetry.serializer(), packet)).await() }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { leases.remove(node) }
                    }
                    delay(200)
                }
            } finally { AudioReactive.release(this@AudioLightPublisher); leases.clear() }
        }
    }
}
