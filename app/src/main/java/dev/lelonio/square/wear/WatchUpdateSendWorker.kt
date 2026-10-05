package dev.lelonio.square.wear

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dev.lelonio.square.SquareApplication
import kotlinx.coroutines.CancellationException

/** The Bluetooth send stays alive after the download worker or settings screen has gone. */
class WatchUpdateSendWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val node = inputData.getString("node") ?: return Result.failure()
        val updates = (applicationContext as SquareApplication).wearBridge.updates
        try { setForeground(getForegroundInfo()) } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* A background foreground-service refusal must not lose the cached APK. */ }
        try {
            val result = updates.sendPrepared(node)
            return if (result) Result.success() else Result.failure()
        } finally {
            // WorkManager removes its foreground notification on completion. Keep the final
            // confirmation/failure available after that removal without holding a service open.
            updates.onSendWorkerFinished()
        }
    }
    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        WatchUpdateNotifications.ID,
        // Not the raw state: in a process just started it is still "idle", and a send in the
        // foreground must not announce that as a failure. See foregroundState.
        WatchUpdateNotifications.notification(applicationContext,
            (applicationContext as SquareApplication).wearBridge.updates.foregroundState()),
        if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
    )
}
