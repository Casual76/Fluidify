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
        if (status.phase == UpdatePhase.INSTALLED || status.phase == UpdatePhase.DECLINE ||
            // Holding the install back for the music is not progress, and not worth a notification.
            (status.phase == UpdatePhase.ACCEPT && status.reason == UpdateStatus.REASON_WAITING_PLAYBACK)
        ) {
            manager.cancel(ID)
            return
        }
        ensureChannel(context, manager)
        val text = context.getString(when (status.phase) {
            UpdatePhase.AWAITING_CONFIRMATION -> R.string.update_confirm
            UpdatePhase.INSTALLING -> R.string.update_installing
            UpdatePhase.FAILED -> if (canRetry) R.string.update_retry_cached else R.string.update_failed
            else -> R.string.update_receiving
        })
        val percent = (status.progress.coerceIn(0f, 1f) * 100).toInt()
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.update_title, status.versionName))
            .setContentText(
                if (status.phase == UpdatePhase.RECEIVING) {
                    context.getString(R.string.two_parts, text, context.getString(R.string.percent_value, percent))
                } else {
                    text
                },
            )
            .setContentIntent(confirmation ?: PlayerIntents.openUpdates(context))
            .setOnlyAlertOnce(true)
            .setOngoing(status.phase == UpdatePhase.RECEIVING || status.phase == UpdatePhase.INSTALLING)
        if (status.phase == UpdatePhase.RECEIVING) notification.setProgress(100, percent, false)
        runCatching { manager.notify(ID, notification.build()) }
    }

    /**
     * "Updated to X", the first time the new version runs: quiet, and it opens the More page, where
     * what is new can be read (see WhatsNew). Not shown for an update nobody could have noticed:
     * the caller says there are no notes, and the text is then only the title.
     */
    fun showUpdated(context: Context, version: String, hasNotes: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java)
        ensureChannel(context, manager)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.updated_to, version))
            .setContentIntent(PlayerIntents.openUpdates(context))
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setAutoCancel(true)
        if (hasNotes) notification.setContentText(context.getString(R.string.updated_to_tap))
        runCatching { manager.notify(ID_UPDATED, notification.build()) }
    }

    /** The notes have been read, or are about to be shown: the notification that pointed at them goes. */
    fun cancelUpdated(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(ID_UPDATED) }
    }

    /**
     * Made once, and quiet: an update is progress to look at, not a sound and a buzz on the wrist.
     * The channel the app had before this one was made with default importance, which the system
     * does not let an app lower afterwards, so this is a new channel and the old one is removed.
     */
    private fun ensureChannel(context: Context, manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.deleteNotificationChannel(OLD_CHANNEL)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.update), NotificationManager.IMPORTANCE_LOW))
    }

    private const val CHANNEL = "watch_update_quiet"
    private const val OLD_CHANNEL = "watch_update"
    private const val ID = 0x5743
    private const val ID_UPDATED = 0x5745
}
