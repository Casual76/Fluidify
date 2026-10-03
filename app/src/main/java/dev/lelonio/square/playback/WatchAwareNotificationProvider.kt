package dev.lelonio.square.playback

import android.app.Notification
import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList

/**
 * Media3's notification, kept on the phone when the watch asks for it.
 *
 * A Fluidify watch can show this phone's playback itself, through its own media session, and then
 * the system's copy of the phone notification on the watch is a second Fluidify on the watch face.
 * Marking the notification local-only is the documented way to keep a notification off a watch;
 * whether the watch's system media controls honour it is what the watch's developer experiment is
 * there to find out (see `WatchSurfaces`). Everything else is Media3's provider, untouched —
 * wrapped, because its methods are final.
 */
@UnstableApi
class WatchAwareNotificationProvider(
    private val inner: MediaNotification.Provider,
    private val localOnly: () -> Boolean,
) : MediaNotification.Provider {

    override fun createNotification(
        mediaSession: MediaSession,
        mediaButtonPreferences: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback,
    ): MediaNotification {
        // The provider calls back later with a redrawn notification (the artwork arrived): that
        // one has to carry the flag too.
        val marked = MediaNotification.Provider.Callback { changed -> onNotificationChangedCallback.onNotificationChanged(mark(changed)) }
        return mark(inner.createNotification(mediaSession, mediaButtonPreferences, actionFactory, marked))
    }

    override fun handleCustomCommand(session: MediaSession, action: String, extras: Bundle): Boolean =
        inner.handleCustomCommand(session, action, extras)

    private fun mark(notification: MediaNotification): MediaNotification {
        if (localOnly()) notification.notification.flags = notification.notification.flags or Notification.FLAG_LOCAL_ONLY
        return notification
    }
}
