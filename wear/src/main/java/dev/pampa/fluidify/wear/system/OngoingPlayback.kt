package dev.pampa.fluidify.wear.system

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import dev.pampa.fluidify.wear.R
import dev.pampa.fluidify.wear.protocol.PlaybackSnapshot

/**
 * The little Fluidify mark on the watch face while the phone plays.
 *
 * Wear calls it an ongoing activity: a silent notification the system turns
 * into an icon on the watch face and an entry at the top of the app list. A
 * tap opens the player; nothing opens by itself. This is what Spotify's watch
 * app does when the phone starts playing, and what the user asked for.
 *
 * It lives as long as the music does. While playing, the notification is told
 * to expire a little after the song would end, so a phone that dies mid-song
 * does not leave a mark behind; paused, it goes ten minutes later unless the
 * music comes back.
 */
class OngoingPlayback(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    @Volatile
    private var shown: String? = null

    fun update(snapshot: PlaybackSnapshot?, enabled: Boolean, nowMs: Long = System.currentTimeMillis()) {
        val track = snapshot?.track
        val playing = snapshot != null && (snapshot.isPlaying || snapshot.playWhenReady)
        val stale = snapshot != null && !playing && nowMs - snapshot.sentAtEpochMs > PAUSE_GRACE_MS
        if (!enabled || track == null || stale || !permitted()) {
            if (shown != null || track == null || !enabled) cancel()
            return
        }
        val signature = listOf(track.uri, track.title, track.artist, playing).joinToString("|")
        if (signature == shown) return

        ensureChannel()
        val open = PlayerIntents.openPlayer(context)
        val timeout = if (playing) {
            val remaining = (track.durationMs - snapshot.positionMs).coerceAtLeast(0)
            remaining + PAUSE_GRACE_MS
        } else {
            PAUSE_GRACE_MS
        }
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(track.title)
            .setContentText(track.artist)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            // The phone already has its own notification for this; never send ours back.
            .setLocalOnly(true)
            .setContentIntent(open)
            .setTimeoutAfter(timeout)

        val status = Status.Builder()
            .addTemplate(if (playing) "#title#" else context.getString(R.string.ongoing_paused, "#title#"))
            .addPart("title", Status.TextPart(track.title))
            .build()
        OngoingActivity.Builder(context, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_notification)
            .setTouchIntent(open)
            .setStatus(status)
            .setTitle(track.title)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .build()
            .apply(context)

        runCatching { manager.notify(NOTIFICATION_ID, builder.build()) }
            .onSuccess { shown = signature }
    }

    fun cancel() {
        manager.cancel(NOTIFICATION_ID)
        shown = null
    }

    private fun permitted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureChannel() {
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        if (system.getNotificationChannel(CHANNEL) != null) return
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.channel_now_playing), NotificationManager.IMPORTANCE_LOW)
            .apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
        system.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL = "now_playing"
        const val NOTIFICATION_ID = 7
        const val PAUSE_GRACE_MS = 10 * 60_000L
    }
}
