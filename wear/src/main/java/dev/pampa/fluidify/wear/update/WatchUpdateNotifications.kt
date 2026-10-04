package dev.pampa.fluidify.wear.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import dev.pampa.fluidify.wear.R
import dev.pampa.fluidify.wear.protocol.UpdatePhase
import dev.pampa.fluidify.wear.protocol.UpdateStatus
import dev.pampa.fluidify.wear.system.PlayerIntents

internal object WatchUpdateNotifications {
    fun show(context: Context, status: UpdateStatus, confirmation: PendingIntent? = null, canRetry: Boolean = false) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (status.phase == UpdatePhase.INSTALLED || status.phase == UpdatePhase.DECLINE) {
            manager.cancel(ID)
            return
        }
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.update), NotificationManager.IMPORTANCE_DEFAULT))
        val text = context.getString(when (status.phase) {
            UpdatePhase.AWAITING_CONFIRMATION -> R.string.update_confirm
            UpdatePhase.INSTALLING -> R.string.update_installing
            UpdatePhase.FAILED -> if (canRetry) R.string.update_retry_cached else R.string.update_failed
            else -> R.string.update_receiving
        })
        val percent = (status.progress.coerceIn(0f, 1f) * 100).toInt()
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${context.getString(R.string.update)} ${status.versionName}")
            .setContentText(if (status.phase == UpdatePhase.RECEIVING) "$text · $percent%" else text)
            .setContentIntent(confirmation ?: PlayerIntents.openUpdates(context))
            .setOnlyAlertOnce(true)
            .setOngoing(status.phase == UpdatePhase.RECEIVING || status.phase == UpdatePhase.INSTALLING)
        if (status.phase == UpdatePhase.RECEIVING) notification.setProgress(100, percent, false)
        runCatching { manager.notify(ID, notification.build()) }
    }
    private const val CHANNEL = "watch_update"
    private const val ID = 0x5743
}
