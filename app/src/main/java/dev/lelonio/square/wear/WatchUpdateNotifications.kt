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
import kotlin.math.abs

internal object WatchUpdateNotifications {
    const val ID = 0x5744
    private const val CHANNEL = "watch_update_progress"

    /** A progress update is shown when the bar has moved this far (in percent)... */
    private const val MIN_STEP_PERCENT = 5

    /** ...or when this long has passed since the last one. */
    private const val MIN_INTERVAL_MS = 500L

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
            is WatchUpdateCoordinator.State.Installed -> R.string.watch_update_installed
            is WatchUpdateCoordinator.State.Failed -> when (state.reason) {
                "watch-not-nearby" -> R.string.watch_update_connection_missing
                "offer-send-failed" -> R.string.watch_update_offer_failed
                else -> R.string.watch_update_failed
            }
        }, when (state) {
            is WatchUpdateCoordinator.State.UpToDate -> state.version
            is WatchUpdateCoordinator.State.Available -> state.version
            is WatchUpdateCoordinator.State.Downloading -> state.version
            is WatchUpdateCoordinator.State.Sending -> state.version
            is WatchUpdateCoordinator.State.Offered -> state.version
            is WatchUpdateCoordinator.State.Installing -> state.version
            is WatchUpdateCoordinator.State.Installed -> state.version
            is WatchUpdateCoordinator.State.Failed -> state.reason
            else -> ""
        })
        val progress = progressOf(state)
        val working = state is WatchUpdateCoordinator.State.Sending || state is WatchUpdateCoordinator.State.Downloading ||
            state is WatchUpdateCoordinator.State.Installing || state is WatchUpdateCoordinator.State.Offered
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(context.getString(R.string.watch_update))
            .setContentText(text + if (progress != null) " · ${(progress * 100).toInt()}%" else "")
            .setOnlyAlertOnce(true).setSilent(true).setOngoing(working)
            // The action MainActivity reads as "show the Watch page of the settings", not just the
            // settings: that is where the update's state is, and what the person came to see.
            .setContentIntent(PendingIntent.getActivity(context, ID,
                Intent(context, MainActivity::class.java).setAction("dev.pampa.fluidify.WATCH_UPDATES"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        if (working) notification.setProgress(100, ((progress ?: 0f) * 100).toInt(), progress == null)
        return notification.build()
    }

    private fun progressOf(state: WatchUpdateCoordinator.State): Float? = when (state) {
        is WatchUpdateCoordinator.State.Downloading -> state.progress
        is WatchUpdateCoordinator.State.Sending -> state.progress
        else -> null
    }

    fun show(context: Context, state: WatchUpdateCoordinator.State) {
        if (state is WatchUpdateCoordinator.State.Idle || state is WatchUpdateCoordinator.State.UpToDate ||
            state is WatchUpdateCoordinator.State.Checking || state is WatchUpdateCoordinator.State.Available) return
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
