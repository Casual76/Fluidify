package dev.lelonio.square.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import dev.lelonio.square.ui.MainActivity
import dev.lelonio.square.ui.settings.WatchPageRequest

/**
 * Keeps the process from being killed while the phone sends tracks to the watch.
 *
 * It is a foreground service and nothing more: its notification is what tells Android that the
 * app is doing work somebody is waiting for, so that a long playlist is not cut off when the app
 * leaves the screen. It holds no wake lock and does not keep the CPU or the screen on; what keeps
 * the transfer alive is being in the foreground.
 *
 * A transfer is started by the watch, so the phone is usually in the background
 * when it begins, and Android may refuse a foreground service started from
 * there. That is allowed to fail: [WatchFileServer] sends anyway, and a
 * transfer cut short resumes from where it stopped on the watch's next attempt.
 * Where Android does allow it (the app is open, or exempt), this is what keeps
 * a long playlist going with the screen off.
 *
 * The notification opens the Watch page of the settings, where the watch's downloads are. It has
 * no determinate progress and no Cancel on purpose: the service is only told that a transfer has
 * started and that the last one has ended ([WatchFileServer] counts them), and knows nothing of
 * how far along any is, and the transfers belong to the watch, which asks for each track and
 * would only ask again, so there is nothing here to cancel that would stay cancelled.
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
            // Left running in the background rather than stopped: a service started with
            // startForegroundService and stopped before it ever reached the foreground is the
            // "did not then call startForeground" crash. [stop] ends it when the send does.
            Log.i(TAG, "not in the foreground: ${it.message}")
        }
        return START_NOT_STICKY
    }

    /**
     * Android 15's limit on data-sync services: past it the service has a few seconds to stop or
     * the app is killed. The send goes on without it, and resumes on the watch's next pass if the
     * phone then sleeps.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.i(TAG, "foreground time used up")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.watch_sending))
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setSilent(true)
            // The same request the update notification makes: the settings, opened on the Watch
            // page (see MainActivity), where what the watch keeps is listed.
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    NOTIFICATION_ID,
                    Intent(this, MainActivity::class.java).setAction(WatchPageRequest.ACTION),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
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
