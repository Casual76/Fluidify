package dev.pampa.fluidify.wear.downloads

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.lelonio.square.nativecore.NativeBridge
import dev.pampa.fluidify.wear.WearApp
import dev.pampa.fluidify.wear.standalone.PollBackoff
import dev.pampa.fluidify.wear.standalone.Route
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * What the watch does at night, on the charger.
 *
 * Kept playlists follow the phone's (tracks added there are fetched, tracks
 * removed are deleted, through [WatchDownloadWorker]'s refresh), and listens
 * heard offline during the day are reported, which needs the engine and a
 * connection for a minute. Nothing here runs off the charger: the work is the
 * kind that costs battery, and at night it costs none that matters.
 */
class NightlySyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val app get() = applicationContext as WearApp

    override suspend fun doWork(): Result {
        val downloads = app.downloads
        if (downloads.store.owners.value.isNotEmpty()) downloads.schedule()
        reportOfflineListens()
        return Result.success()
    }

    /** Starts the engine long enough for it to send the listens it kept (native events::outbox). */
    private suspend fun reportOfflineListens() {
        val outbox = File(applicationContext.cacheDir, OUTBOX)
        val standalone = app.standalone
        if (!outbox.isFile || !standalone.auth.hasCredential) return
        val route = standalone.network.acquire()
        try {
            if (route == Route.NONE) return
            if (!standalone.engine.acquire()) return
            try {
                // The listens go with the session, not with the Connect device: no need for it to be known.
                withTimeoutOrNull(CONNECT_WAIT_MS) {
                    var looks = 0
                    while (!NativeBridge.isConnected) delay(PollBackoff.delayMs(looks++))
                }
                // The outbox goes the moment the session is up; give the posts a moment to leave.
                delay(SEND_MS)
                Log.i(TAG, "offline listens reported: ${!outbox.exists()}")
            } finally {
                standalone.engine.release()
            }
        } finally {
            standalone.network.release()
        }
    }

    companion object {
        private const val TAG = "NightlySync"
        private const val WORK = "nightly-sync"
        private const val OUTBOX = "listen-outbox"
        private const val CONNECT_WAIT_MS = 40_000L
        private const val SEND_MS = 5_000L

        /** Once a day, on the charger. KEEP: scheduling again is free. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<NightlySyncWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresCharging(true)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build()
            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
            }
        }
    }
}
