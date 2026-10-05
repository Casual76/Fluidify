package dev.pampa.fluidify.wear.system

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.pampa.fluidify.wear.R
import dev.pampa.fluidify.wear.ui.common.ErrorMessages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * The failures nobody is looking at.
 *
 * Commands that fail are said by a toast in the app (see WatchRoot), and a shared flow without a
 * replay gives them to whoever is collecting at that moment. A handoff the phone asked for, or the
 * headphones' prompt, can fail with the screen off and the app closed: no collector, and the
 * failure was never seen by anyone. With the screen not in front the reason goes into one quiet
 * notification instead, which opens the app on the player; the next failure replaces it.
 *
 * The one collector of its kind, started with the controls that emit them (see WearApp.playback).
 *
 * @param errors the codes of failed commands, as the controls say them.
 * @param uiVisible whether the screen is in front, and so showing them itself.
 */
class BackgroundErrors(
    private val context: Context,
    private val scope: CoroutineScope,
    private val errors: SharedFlow<String>,
    private val uiVisible: () -> Boolean,
) {

    fun start() {
        scope.launch {
            errors.collect { code ->
                if (!uiVisible()) post(context, code)
            }
        }
    }

    companion object {
        private const val CHANNEL = "errors"
        private const val ID = 10

        /** Gone by itself after a couple of minutes: what failed is not news for long. */
        private const val TIMEOUT_MS = 2 * 60_000L

        /** Says what failed, quietly, with a tap that opens the app. */
        fun post(context: Context, code: String) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val system = context.getSystemService(NotificationManager::class.java)
            if (system.getNotificationChannel(CHANNEL) == null) {
                system.createNotificationChannel(
                    NotificationChannel(CHANNEL, context.getString(R.string.errors_channel), NotificationManager.IMPORTANCE_LOW),
                )
            }
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(ErrorMessages.textFor(code)))
                .setContentIntent(PlayerIntents.openPlayer(context))
                .setAutoCancel(true)
                .setLocalOnly(true)
                .setOnlyAlertOnce(true)
                .setTimeoutAfter(TIMEOUT_MS)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .build()
            runCatching { NotificationManagerCompat.from(context).notify(ID, notification) }
        }

        /** The screen is in front and tells for itself: whatever was left here is old. */
        fun clear(context: Context) {
            runCatching { NotificationManagerCompat.from(context).cancel(ID) }
        }
    }
}
