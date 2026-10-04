package dev.lelonio.square.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.lelonio.square.R
import dev.lelonio.square.ui.MainActivity

internal object WatchUpdateNotifications {
    const val ID = 0x5744
    private const val CHANNEL = "watch_update_progress"
    fun notification(context: Context, state: WatchUpdateCoordinator.State): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.watch_update), NotificationManager.IMPORTANCE_LOW))
        val text = context.getString(when (state) {
            is WatchUpdateCoordinator.State.Downloading -> R.string.watch_update_downloading
            is WatchUpdateCoordinator.State.Sending -> R.string.watch_update_sending
            is WatchUpdateCoordinator.State.Offered -> R.string.watch_update_offered
            is WatchUpdateCoordinator.State.Installing -> R.string.watch_update_installing
            is WatchUpdateCoordinator.State.AwaitingConfirmation -> R.string.watch_update_confirm
            is WatchUpdateCoordinator.State.Installed -> R.string.watch_update_installed
            else -> R.string.watch_update_failed
        }, when (state) {
            is WatchUpdateCoordinator.State.Downloading -> state.version
            is WatchUpdateCoordinator.State.Sending -> state.version
            is WatchUpdateCoordinator.State.Offered -> state.version
            is WatchUpdateCoordinator.State.Installing -> state.version
            is WatchUpdateCoordinator.State.Installed -> state.version
            is WatchUpdateCoordinator.State.Failed -> state.reason
            else -> ""
        })
        val progress = when (state) {
            is WatchUpdateCoordinator.State.Downloading -> state.progress
            is WatchUpdateCoordinator.State.Sending -> state.progress
            else -> null
        }
        val working = state is WatchUpdateCoordinator.State.Sending || state is WatchUpdateCoordinator.State.Downloading ||
            state is WatchUpdateCoordinator.State.Installing || state is WatchUpdateCoordinator.State.Offered
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(context.getString(R.string.watch_update))
            .setContentText(text + if (progress != null) " · ${(progress * 100).toInt()}%" else "")
            .setOnlyAlertOnce(true).setSilent(true).setOngoing(working)
            .setContentIntent(PendingIntent.getActivity(context, ID,
                Intent(context, MainActivity::class.java).setAction("dev.pampa.fluidify.WATCH_UPDATES"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        if (working) notification.setProgress(100, ((progress ?: 0f) * 100).toInt(), progress == null)
        return notification.build()
    }
    fun show(context: Context, state: WatchUpdateCoordinator.State) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (state is WatchUpdateCoordinator.State.Idle || state is WatchUpdateCoordinator.State.UpToDate ||
            state is WatchUpdateCoordinator.State.Checking || state is WatchUpdateCoordinator.State.Available) return
        runCatching { manager.notify(ID, notification(context, state)) }
    }
}
