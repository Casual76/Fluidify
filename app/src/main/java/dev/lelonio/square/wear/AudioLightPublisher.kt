package dev.lelonio.square.wear

import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.google.android.gms.wearable.Wearable
import dev.lelonio.square.SquareApplication
import dev.lelonio.square.playback.AudioReactive
import dev.pampa.fluidify.wear.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await

/**
 * Sends the watch the loudness of what is playing, for its light show, while the watch asks for it.
 *
 * The watch renews a short lease every two seconds ([subscribe]); while any lease is alive a loop
 * measures and sends, five times a second. Everything here runs off the main thread: the loop is
 * pure bookkeeping and Data Layer calls, and on the main thread it competed with the UI for
 * nothing.
 *
 * The loop does not spin when there is nothing to send. While nothing is playing it sleeps on the
 * bridge's playing state ([PhoneWearBridge.isPlaying]) and wakes the moment playback starts; while
 * the phone is in power-saving mode (or has animations switched off) it sends nothing but keeps
 * the leases, so that the watch's next renewal finds them alive and the light picks up the moment
 * the mode ends. Dropping the leases, as this once did, made the watch notice the silence and
 * subscribe again from scratch every two seconds for as long as the mode lasted.
 */
class AudioLightPublisher(private val app: SquareApplication) {

    /** Not thread-safe, and touched from the listener's threads and the loop: always under [leaseLock]. */
    private val leases = AudioLightLeases()
    private val leaseLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The loop, while there is one; read and set under [leaseLock]. */
    private var sending: Job? = null

    suspend fun subscribe(node: String, request: AudioLightSubscription) {
        if (request.id.length !in 1..64) return
        if (!request.enabled) {
            synchronized(leaseLock) { leases.request(node, request, false, SystemClock.elapsedRealtime()) }
            return
        }
        val nearby = catchingNonCancel {
            withTimeoutOrNull(NODE_LOOKUP_MS) { Wearable.getNodeClient(app).connectedNodes.await() }
        }.getOrNull()?.any { it.id == node && it.isNearby } == true
        if (!nearby) return
        synchronized(leaseLock) {
            leases.request(node, request, nearby, SystemClock.elapsedRealtime())
            // Decided under the same lock the loop ends under, so a lease can never be added just
            // after the loop has decided it is finished and been left with nobody to serve it.
            if (sending?.isActive != true) sending = scope.launch { publishWhileLeased() }
        }
    }

    private suspend fun publishWhileLeased() {
        // The phone computes all bands; only aggregates are sent to the watch.
        var acquired = false
        var limited = false
        var checkedAt = 0L
        var near = emptySet<String>()
        try {
            while (true) {
                val now = SystemClock.elapsedRealtime()
                if (now - checkedAt >= NEAR_CHECK_MS) {
                    near = nearbyNodes()
                    checkedAt = now
                }
                val recipients = synchronized(leaseLock) {
                    leases.recipients(now, near).also { if (it.isEmpty()) sending = null }
                }
                if (recipients.isEmpty()) break

                if (!lightAllowed()) {
                    if (!limited) {
                        limited = true
                        Log.i(TAG, "audio light paused (power saving or animations off); leases kept")
                    }
                    if (acquired) {
                        AudioReactive.release(this)
                        acquired = false
                    }
                    delay(LIMITED_RECHECK_MS)
                    continue
                }
                if (limited) {
                    limited = false
                    Log.i(TAG, "audio light resumed")
                }

                // Nothing to measure while nothing plays: wait for playback to start, looking at
                // the leases again every so often (the wait is not for ever, they expire).
                if (!app.wearBridge.isPlaying.value) {
                    withTimeoutOrNull(IDLE_RECHECK_MS) { app.wearBridge.isPlaying.first { it } }
                    continue
                }
                if (!acquired) {
                    AudioReactive.acquire(this)
                    acquired = true
                }

                val frame = AudioReactive.frame()
                if (frame != null) for ((node, id) in recipients) {
                    val packet = AudioLightTelemetry(id, System.nanoTime(), frame.track, frame.generation,
                        frame.positionMs, frame.energy, frame.bass, frame.mids, frame.treble,
                        System.currentTimeMillis() - (System.nanoTime() - frame.dueNs) / 1_000_000)
                    try {
                        withTimeoutOrNull(SEND_TIMEOUT_MS) {
                            Wearable.getMessageClient(app).sendMessage(node,
                                WearPaths.AUDIO_LIGHT_FRAME, WearCodec.encode(AudioLightTelemetry.serializer(), packet)).await()
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        // The lease goes, and the watch asks again if it still wants the light.
                        Log.i(TAG, "audio light to a watch stopped: ${error.javaClass.simpleName}")
                        synchronized(leaseLock) { leases.remove(node) }
                    }
                }
                delay(FRAME_INTERVAL_MS)
            }
        } finally {
            if (acquired) AudioReactive.release(this)
        }
    }

    /** The watches in Bluetooth range right now, as far as the Data Layer says within a second. */
    private suspend fun nearbyNodes(): Set<String> =
        catchingNonCancel {
            withTimeoutOrNull(NODE_LOOKUP_MS) { Wearable.getNodeClient(app).connectedNodes.await() }
        }.getOrNull()?.filter { it.isNearby }?.map { it.id }?.toSet().orEmpty()

    /** False in power-saving mode and with animations switched off: the light is an effect, not a function. */
    private fun lightAllowed(): Boolean =
        app.getSystemService(PowerManager::class.java)?.isPowerSaveMode == false &&
            Settings.Global.getFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

    private companion object {
        const val TAG = "AudioLightPublisher"
        const val NODE_LOOKUP_MS = 1_000L
        const val NEAR_CHECK_MS = 1_000L
        const val FRAME_INTERVAL_MS = 200L
        const val SEND_TIMEOUT_MS = 150L

        /** Re-check the leases this often while nothing plays; a lease lasts five seconds. */
        const val IDLE_RECHECK_MS = 5_000L

        /** How often to see whether power saving has ended; the watch's renewals keep the leases alive. */
        const val LIMITED_RECHECK_MS = 1_000L
    }
}
