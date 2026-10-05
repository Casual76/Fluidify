package dev.lelonio.square.wear

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.antigravity.fluidengine.foundation.AvailableAppUpdate
import dev.lelonio.square.SquareApplication

/** The APK download survives activity/process recreation and is retried by WorkManager. */
class WatchUpdateDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val version = inputData.getString("version") ?: return Result.failure()
        val url = inputData.getString("url") ?: return Result.failure()
        val sha = inputData.getString("sha256") ?: return Result.failure()
        val node = inputData.getString("node") ?: return Result.failure()
        if (!sha.matches(WatchUpdateCoordinator.SHA256_HEX)) return Result.failure()
        val update = AvailableAppUpdate(version, "", "", "watch-download.apk", url, inputData.getLong("bytes", 0), sha)
        val done = (applicationContext as SquareApplication).wearBridge.updates.downloadAndOffer(update, node, inputData.getBoolean("user", false))
        return if (done) Result.success() else if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}
