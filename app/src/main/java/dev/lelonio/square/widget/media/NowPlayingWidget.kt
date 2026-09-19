package dev.lelonio.square.widget.media

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Scale
import dev.lelonio.square.playback.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What is playing, on the home screen.
 *
 * The card itself is Pampa Widgets' — see [MediaWidgetUpdater], ported with its layout and its
 * renderer intact. What lives here is the half that had to change: that app is a widget for other
 * people's players and reads whatever session is playing through a notification listener; this one
 * *is* the player, so the state is pushed to it by [PlaybackService] and the buttons talk to the
 * session directly.
 *
 * The state is pushed rather than pulled, and it has to be. A widget is drawn by the launcher's
 * process out of what the system stored, possibly hours after this one died, so the only way for it
 * to be right is for whatever knows the truth to have written it down first.
 */
class NowPlayingWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        NowPlayingWidgetBridge.redraw(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        NowPlayingWidgetBridge.redraw(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        MediaWidgetUpdater.onDeleted(appWidgetIds)
        super.onDeleted(context, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = when (intent.action) {
            ActionTogglePlayPause -> MediaControlAction.TogglePlayPause
            ActionNext -> MediaControlAction.Next
            ActionPrevious -> MediaControlAction.Previous
            ActionRefresh -> {
                NowPlayingWidgetBridge.redraw(context)
                return
            }
            else -> return
        }
        // `goAsync` because a controller takes a moment to connect, and a receiver that returns
        // before its work is done is a receiver whose process the system is free to kill mid-command.
        val pending = goAsync()
        NowPlayingWidgetBridge.command(context, action) { pending.finish() }
    }

    companion object {
        const val ActionTogglePlayPause = "dev.lelonio.square.widget.media.action.TOGGLE_PLAY_PAUSE"
        const val ActionNext = "dev.lelonio.square.widget.media.action.NEXT"
        const val ActionPrevious = "dev.lelonio.square.widget.media.action.PREVIOUS"
        const val ActionRefresh = "dev.lelonio.square.widget.media.action.REFRESH"
    }
}

/**
 * What the playback service tells the home screen, and what the home screen asks back.
 *
 * One object because the two directions share a scope and a cover: the push needs the artwork to
 * draw, and the command needs the push to follow it.
 */
object NowPlayingWidgetBridge {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The last thing drawn, so a redraw with no new information does not have to ask the player. */
    @Volatile
    private var last: MediaPlaybackSnapshot? = null

    private val coverLock = Mutex()
    private var cachedCover: Pair<String, Bitmap?>? = null

    /** Called from the service's own player listener. */
    fun push(context: Context, player: Player) {
        val metadata = player.mediaMetadata
        val hasItem = player.currentMediaItem != null
        val artworkUri = metadata.artworkUri?.toString().orEmpty()
        val snapshot = MediaPlaybackSnapshot(
            availability = if (hasItem) {
                MediaPlaybackAvailability.Active
            } else {
                MediaPlaybackAvailability.NoSession
            },
            title = metadata.title?.toString().orEmpty(),
            artist = metadata.artist?.toString().orEmpty(),
            album = metadata.albumTitle?.toString().orEmpty(),
            mediaId = player.currentMediaItem?.mediaId.orEmpty(),
            sourceLabel = "",
            packageName = context.packageName,
            isPlaying = player.playWhenReady,
            canPlayPause = hasItem,
            canSkipNext = player.hasNextMediaItem(),
            canSkipPrevious = player.hasPreviousMediaItem(),
            artworkUri = artworkUri,
            artworkKey = artworkUri,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.duration.takeIf { it > 0L } ?: 0L,
            lastPositionUpdateTimeMs = android.os.SystemClock.elapsedRealtime(),
            playbackSpeed = if (player.playWhenReady) player.playbackParameters.speed else 0f,
        )
        render(context, snapshot)
    }

    /**
     * A made-up track, for the dev build's glass gallery.
     *
     * The card cannot otherwise be looked at on a machine with no Spotify account, and the layout
     * that matters is the one with something playing in it. Nothing in the app calls this.
     */
    internal fun preview(
        context: Context,
        title: String,
        artist: String,
        artworkUrl: String,
        playing: Boolean,
    ) {
        render(
            context,
            MediaPlaybackSnapshot(
                availability = MediaPlaybackAvailability.Active,
                title = title,
                artist = artist,
                mediaId = artworkUrl,
                packageName = context.packageName,
                isPlaying = playing,
                canPlayPause = true,
                canSkipNext = true,
                canSkipPrevious = true,
                artworkUri = artworkUrl,
                artworkKey = artworkUrl,
                positionMs = 61_000L,
                durationMs = 182_000L,
                lastPositionUpdateTimeMs = android.os.SystemClock.elapsedRealtime(),
                playbackSpeed = if (playing) 1f else 0f,
            ),
        )
    }

    /** Redraws from what was last pushed. For a resize, a restore, or a change of theme. */
    fun redraw(context: Context) {
        render(context, last ?: MediaPlaybackSnapshot(MediaPlaybackAvailability.NoSession))
    }

    private fun render(context: Context, snapshot: MediaPlaybackSnapshot) {
        val app = context.applicationContext
        scope.launch {
            val ids = runCatching {
                AppWidgetManager.getInstance(app).getAppWidgetIds(
                    ComponentName(app, NowPlayingWidget::class.java),
                )
            }.getOrNull()
            // Nothing on any home screen: there is no card to draw and no cover worth fetching.
            if (ids == null || ids.isEmpty()) return@launch
            val withCover = snapshot.copy(artwork = cover(app, snapshot.artworkUri))
            last = withCover
            runCatching {
                MediaWidgetUpdater.update(app, ids, withCover, widgetSettings(app))
            }
        }
    }

    /**
     * The sleeve, decoded once however many cards are asking.
     *
     * Behind a lock and with the last one kept: several widgets update at the same instant on a
     * change of track, and Coil's disk cache hands the editor for an entry to one writer and
     * refuses the others — so without this, some of them come back empty and draw a blank frame
     * while one draws the cover. It looks like a flaky network and is a race.
     */
    private suspend fun cover(context: Context, url: String): Bitmap? {
        if (url.isEmpty()) return null
        return coverLock.withLock {
            cachedCover?.takeIf { it.first == url }?.second
                ?: fetchCover(context, url).also { cachedCover = url to it }
        }
    }

    private suspend fun fetchCover(context: Context, url: String): Bitmap? =
        withTimeoutOrNull(COVER_TIMEOUT_MS) {
            runCatching {
                // The app's own loader: it has the disk cache the rest of the app has been filling,
                // so a cover that has been on screen is already here.
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .size(COVER_PX)
                    .scale(Scale.FILL)
                    .allowHardware(false)
                    .build()
                (context.imageLoader.execute(request).drawable
                    as? android.graphics.drawable.BitmapDrawable)?.bitmap
            }.getOrNull()
        }

    /**
     * One of the buttons.
     *
     * A controller is built here and let go again, which sounds wasteful and is the only correct
     * thing to do: a widget has no process of its own to hold one in, and a session connection is
     * also what *starts* the service when the music has been stopped long enough for it to have gone
     * away.
     */
    fun command(context: Context, action: MediaControlAction, onDone: () -> Unit) {
        val app = context.applicationContext
        scope.launch {
            try {
                val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
                val controller = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                    runCatching {
                        MediaController.Builder(app, token).buildAsync().await()
                    }.getOrNull()
                } ?: return@launch
                try {
                    when (action) {
                        MediaControlAction.TogglePlayPause ->
                            if (controller.isPlaying) controller.pause() else controller.play()

                        MediaControlAction.Next -> controller.seekToNextMediaItem()
                        MediaControlAction.Previous -> controller.seekToPreviousMediaItem()
                    }
                    // Nothing is written back from here. A controller that has only just connected
                    // does not necessarily have the session's queue yet, so what it would report in
                    // this instant is "nothing playing" — which would empty a card that was showing
                    // a track, in answer to a button press. The service's own listener pushes the
                    // moment the command lands.
                } finally {
                    controller.release()
                }
            } finally {
                onDone()
            }
        }
    }

    private const val COVER_PX = 512
    private const val COVER_TIMEOUT_MS = 5_000L
    private const val CONNECT_TIMEOUT_MS = 4_000L
}
