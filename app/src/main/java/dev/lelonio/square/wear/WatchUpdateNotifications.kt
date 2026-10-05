package dev.lelonio.square.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import dev.lelonio.square.R
import dev.lelonio.square.ui.MainActivity
import dev.lelonio.square.ui.settings.WatchPageRequest
import kotlin.math.abs

internal object WatchUpdateNotifications {
    const val ID = 0x5744
    private const val CHANNEL = "watch_update_progress"

    /** A progress update is shown when the bar has moved this far (in percent)... */
    private const val MIN_STEP_PERCENT = 5

    /** ...or when this long has passed since the last one. */
    private const val MIN_INTERVAL_MS = 500L

    /** "Watch updated to X" clears itself after this long. */
    private const val INSTALLED_TIMEOUT_MS = 10 * 60_000L

    /** A failure waits longer, because it asks for something. */
    private const val FAILED_TIMEOUT_MS = 60 * 60_000L

    @Volatile private var channelCreated = false

    /** What was last posted: which state, how far along, and when. */
    private class Shown(val state: Class<*>, val percent: Int?, val atMs: Long)

    @Volatile private var lastShown: Shown? = null

    /**
     * The channel is made once per process, not on every notification: a progress bar updates
     * about a hundred times a transfer, and each update asked the notification manager to
     * (re)create a channel that is there already.
     */
    private fun ensureChannel(context: Context) {
        if (channelCreated) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.watch_update), NotificationManager.IMPORTANCE_LOW))
        channelCreated = true
    }

    fun notification(context: Context, state: WatchUpdateCoordinator.State): Notification {
        ensureChannel(context)
        val text = context.getString(when (state) {
            WatchUpdateCoordinator.State.Idle -> R.string.watch_update_idle
            WatchUpdateCoordinator.State.Checking -> R.string.watch_update_checking
            is WatchUpdateCoordinator.State.UpToDate -> R.string.watch_update_current
            is WatchUpdateCoordinator.State.Available -> R.string.watch_update_available
            is WatchUpdateCoordinator.State.Downloading -> R.string.watch_update_downloading
            is WatchUpdateCoordinator.State.Sending -> R.string.watch_update_sending
            is WatchUpdateCoordinator.State.Offered -> R.string.watch_update_offered
            is WatchUpdateCoordinator.State.Installing -> R.string.watch_update_installing
            is WatchUpdateCoordinator.State.AwaitingConfirmation -> R.string.watch_update_confirm
            is WatchUpdateCoordinator.State.WaitingForPlayback -> R.string.watch_update_waiting_playback
            // Not "Installed %1$s", which is the Watch page's line: a notification is read by
            // someone who has been elsewhere, and says what happened to the watch.
            is WatchUpdateCoordinator.State.Installed -> R.string.watch_update_installed_notification
            is WatchUpdateCoordinator.State.AutoUpdateOff -> R.string.watch_update_auto_off
            // The sentence for the reason, shared with the Watch page; never the raw token.
            is WatchUpdateCoordinator.State.Failed -> watchUpdateFailureRes(state.reason)
        }, when (state) {
            is WatchUpdateCoordinator.State.UpToDate -> state.version
            is WatchUpdateCoordinator.State.Available -> state.version
            is WatchUpdateCoordinator.State.Downloading -> state.version
            is WatchUpdateCoordinator.State.Sending -> state.version
            is WatchUpdateCoordinator.State.Offered -> state.version
            is WatchUpdateCoordinator.State.Installing -> state.version
            is WatchUpdateCoordinator.State.Installed -> state.version
            else -> ""
        })
        val progress = progressOf(state)
        val working = state is WatchUpdateCoordinator.State.Sending || state is WatchUpdateCoordinator.State.Downloading ||
            state is WatchUpdateCoordinator.State.Installing || state is WatchUpdateCoordinator.State.Offered
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(context.getString(R.string.watch_update))
            .setContentText(
                if (progress != null) context.getString(R.string.watch_update_progress, text, (progress.coerceIn(0f, 1f) * 100).toInt()) else text,
            )
            .setOnlyAlertOnce(true).setSilent(true).setOngoing(working)
            // The action MainActivity reads as "show the Watch page of the settings", not just the
            // settings: that is where the update's state is, and what the person came to see.
            .setContentIntent(PendingIntent.getActivity(context, ID,
                Intent(context, MainActivity::class.java).setAction(WatchPageRequest.ACTION),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        if (working) notification.setProgress(100, ((progress ?: 0f) * 100).toInt(), progress == null)
        when (state) {
            // The good news is for a glance, not for keeping: it clears itself.
            is WatchUpdateCoordinator.State.Installed ->
                notification.setAutoCancel(true).setTimeoutAfter(INSTALLED_TIMEOUT_MS)
            // The bad news stays a good while longer, with the way out in it: a button that tries
            // again, so that nobody has to open the app to press the row that is the same thing.
            is WatchUpdateCoordinator.State.Failed -> notification.setAutoCancel(true).setTimeoutAfter(FAILED_TIMEOUT_MS)
                .addAction(0, context.getString(R.string.retry), retryIntent(context))
            else -> Unit
        }
        return notification.build()
    }

    /** The "Retry" button: a broadcast to [WatchUpdateRetryReceiver], which is not exported. */
    private fun retryIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        ID,
        Intent(context, WatchUpdateRetryReceiver::class.java).setAction(WatchUpdateRetryReceiver.ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun progressOf(state: WatchUpdateCoordinator.State): Float? = when (state) {
        is WatchUpdateCoordinator.State.Downloading -> state.progress
        is WatchUpdateCoordinator.State.Sending -> state.progress
        else -> null
    }

    fun show(context: Context, state: WatchUpdateCoordinator.State) {
        if (state is WatchUpdateCoordinator.State.Idle || state is WatchUpdateCoordinator.State.UpToDate ||
            state is WatchUpdateCoordinator.State.Checking || state is WatchUpdateCoordinator.State.Available ||
            // Told on the page, where it can be acted on; a waiting install is not worth a buzz.
            state is WatchUpdateCoordinator.State.WaitingForPlayback || state is WatchUpdateCoordinator.State.AutoUpdateOff
        ) return
        if (!worthShowing(state)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        runCatching { manager.notify(ID, notification(context, state)) }
    }

    /**
     * Whether [state] is different enough from what is on screen to post again.
     *
     * A transfer reports every percent, about a hundred posts to the notification manager (each a
     * binder call, each a redraw of the shade) for a bar nobody can read at that resolution. A
     * change of kind (sending to installing, anything to failed) is always posted, and so is a
     * state with no bar; within one bar, a step of [MIN_STEP_PERCENT] or half a second.
     */
    private fun worthShowing(state: WatchUpdateCoordinator.State): Boolean {
        val now = SystemClock.elapsedRealtime()
        val percent = progressOf(state)?.let { (it.coerceIn(0f, 1f) * 100).toInt() }
        val previous = lastShown
        val skip = previous != null && percent != null && previous.percent != null &&
            previous.state == state::class.java &&
            abs(percent - previous.percent) < MIN_STEP_PERCENT && now - previous.atMs < MIN_INTERVAL_MS
        if (skip) return false
        lastShown = Shown(state::class.java, percent, now)
        return true
    }
}
