package dev.pampa.fluidify.wear.update

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.antigravity.fluidengine.foundation.AppUpdateInstallState
import dev.antigravity.fluidengine.net.EngineHttp
import dev.antigravity.fluidengine.update.AndroidAppUpdateInstaller
import dev.antigravity.fluidengine.update.EngineAppUpdater
import dev.antigravity.fluidengine.update.UpdateSource
import dev.pampa.fluidify.wear.BuildConfig
import kotlinx.coroutines.flow.takeWhile
import java.util.concurrent.TimeUnit
import dev.lelonio.square.update.WatchApkValidation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The watch updating itself, for when the phone is not around to do it.
 *
 * The phone is the normal route (it notices on every hello and sends the build
 * over Bluetooth). This covers the other case: a watch that has not seen its
 * phone for a week checks `manifest-wear.json` itself, on the charger, and
 * installs what it finds. With the phone seen recently it does nothing, so the
 * same build is never fetched twice over two radios.
 */
class WatchSelfUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val updates = WatchUpdater(applicationContext)
        if (!updates.autoUpdate) return Result.success()
        val phoneSeenAt = applicationContext.getSharedPreferences("phone_link", Context.MODE_PRIVATE)
            .getLong(KEY_PHONE_SEEN, 0)
        if (System.currentTimeMillis() - phoneSeenAt < PHONE_RECENT_MS) return Result.success()

        val http = EngineHttp(userAgent = "Fluidify-Wear/${BuildConfig.VERSION_NAME}")
        val installer = AndroidAppUpdateInstaller(applicationContext, http)
        val updater = EngineAppUpdater(
            http = http,
            source = UpdateSource(manifestUrl = WEAR_MANIFEST_URL, applicationId = applicationContext.packageName),
            installer = installer,
        )
        val update = updater.check(BuildConfig.VERSION_NAME).getOrElse {
            Log.i(TAG, "self-update check failed: ${it.message}")
            return Result.retry()
        } ?: return Result.success()
        if (!update.sha256.matches(Regex("[a-fA-F0-9]{64}"))) return Result.failure()
        val apk = runCatching { installer.download(update) }.getOrElse { return Result.retry() }
        if (withContext(Dispatchers.IO) { WatchApkValidation.reject(applicationContext, apk, update.version, update.sha256) } != null) return Result.failure()

        var failed = false
        installer.installFile(apk, update.version, sha256 = update.sha256)
            .takeWhile { state ->
                when (state) {
                    is AppUpdateInstallState.Error -> {
                        Log.w(TAG, "self-update failed: ${state.message}")
                        failed = true
                        false
                    }
                    is AppUpdateInstallState.Installed, is AppUpdateInstallState.AwaitingUserAction -> false
                    else -> true
                }
            }
            .collect { }
        return if (failed) Result.retry() else Result.success()
    }

    companion object {
        private const val TAG = "WatchSelfUpdate"
        private const val WORK = "watch-self-update"
        const val KEY_PHONE_SEEN = "phone_seen_at"
        private const val PHONE_RECENT_MS = 7L * 24 * 60 * 60_000L

        const val WEAR_MANIFEST_URL =
            "https://raw.githubusercontent.com/Casual76/Fluidify/master/manifest-wear.json"

        /** Every three days, on the charger, with any network the watch has. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WatchSelfUpdateWorker>(3, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresCharging(true)
                        .setRequiresBatteryNotLow(true)
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
