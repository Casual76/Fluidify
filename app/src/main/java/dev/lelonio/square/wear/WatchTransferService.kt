package dev.lelonio.square.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.lelonio.square.R

/**
 * Keeps the phone awake while it sends tracks to the watch.
 *
 * A transfer is started by the watch, so the phone is usually in the background
 * when it begins, and Android may refuse a foreground service started from
 * there. That is allowed to fail: [WatchFileServer] sends anyway, and a
 * transfer cut short resumes from where it stopped on the watch's next attempt.
 * Where Android does allow it (the app is open, or exempt), this is what keeps
 * a long playlist going with the screen off.
 */
class WatchTransferService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        }.onFailure {
            Log.i(TAG, "not in the foreground: ${it.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.watch_sending))
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setSilent(true)
            .build()

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.watch_sending_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        private const val TAG = "WatchTransfer"
        private const val CHANNEL_ID = "watch_transfer"
        private const val NOTIFICATION_ID = 0x5741
        private const val ACTION_STOP = "dev.lelonio.square.wear.STOP_TRANSFER"

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, WatchTransferService::class.java)) }
                .onFailure { Log.i(TAG, "transfer service not started: ${it.message}") }
        }

        fun stop(context: Context) {
            runCatching { context.startService(Intent(context, WatchTransferService::class.java).setAction(ACTION_STOP)) }
        }
    }
}
