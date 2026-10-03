package dev.pampa.fluidify.wear.system

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.pampa.fluidify.wear.R
import dev.pampa.fluidify.wear.WearApp
import dev.pampa.fluidify.wear.playback.NowPlaying
import dev.pampa.fluidify.wear.playback.PlaybackControls
import dev.pampa.fluidify.wear.protocol.PlaybackSnapshot
import dev.pampa.fluidify.wear.protocol.TrackInfo

/**
 * The developer experiment: Fluidify on the watch face as a *media* entry, the way Spotify's watch
 * app is there.
 *
 * The phone is asked to keep its media notification to itself (see `WatchSurfaces`), and the watch
 * posts a media notification of its own over [PhoneMirrorPlayer], a session that mirrors the
 * phone's playback and sends its buttons back through the watch's remote. Wear builds the watch-face
 * entry from a media notification by itself; tapping it opens the player. Whether the Galaxy Watch's
 * own phone controls then step aside is exactly what this is for finding out on the watch — which
 * is why it lives behind the developer options and not in the settings.
 */
@OptIn(UnstableApi::class)
class MirrorNowPlaying(private val app: WearApp) {

    private val main = Handler(Looper.getMainLooper())
    private val manager = NotificationManagerCompat.from(app)
    private var player: PhoneMirrorPlayer? = null
    private var session: MediaSession? = null
    private var shown: String? = null

    fun show(snapshot: PlaybackSnapshot?) = main.post { showNow(snapshot) }

    fun hide() = main.post { hideNow() }

    private fun showNow(snapshot: PlaybackSnapshot?) {
        val track = snapshot?.track ?: return hideNow()
        if (!permitted()) return
        val mirror = player ?: PhoneMirrorPlayer(app.controls) { app.remote.nowPlaying.value }.also { player = it }
        mirror.refresh()
        val current = session ?: MediaSession.Builder(app, mirror)
            .setId(SESSION_ID)
            .setSessionActivity(PlayerIntents.openPlayer(app))
            .build()
            .also { session = it }

        val playing = snapshot.isPlaying || snapshot.playWhenReady
        val signature = listOf(track.uri, track.title, track.artist, track.artKey, playing).joinToString("|")
        if (signature == shown) return
        OngoingPlayback.ensureChannel(app)
        val builder = NotificationCompat.Builder(app, OngoingPlayback.CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(track.title)
            .setContentText(track.artist)
            .setLargeIcon(CoverImages.bitmap(app.art, track.artKey, COVER_PX))
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(playing)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .setContentIntent(PlayerIntents.openPlayer(app))
            .addAction(R.drawable.ic_tile_previous, app.getString(R.string.previous), TileActions.pendingIntent(app, TileActions.PREVIOUS))
            .addAction(
                if (playing) R.drawable.ic_tile_pause else R.drawable.ic_tile_play,
                app.getString(if (playing) R.string.pause else R.string.play),
                TileActions.pendingIntent(app, TileActions.TOGGLE),
            )
            .addAction(R.drawable.ic_tile_next, app.getString(R.string.next), TileActions.pendingIntent(app, TileActions.NEXT))
            .setStyle(MediaStyleNotificationHelper.MediaStyle(current).setShowActionsInCompactView(0, 1, 2))
        notify(builder)
        shown = signature
    }

    @SuppressLint("MissingPermission")
    private fun notify(builder: NotificationCompat.Builder) {
        runCatching { manager.notify(NOTIFICATION_ID, builder.build()) }
    }

    private fun hideNow() {
        if (shown == null && session == null) return
        manager.cancel(NOTIFICATION_ID)
        session?.release()
        session = null
        player?.release()
        player = null
        shown = null
    }

    private fun permitted(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val NOTIFICATION_ID = 8
        const val SESSION_ID = "phone-mirror"
        const val COVER_PX = 256
    }
}

/**
 * A player that is the phone's, seen from the watch: what [nowPlaying] says is playing, with the
 * buttons going back through [controls].
 *
 * Three items, not one: a playlist of one has no next and no previous, and a controller would grey
 * out the skip buttons. The ones either side stand for "whatever the phone plays before or after";
 * moving to them is a skip.
 */
@OptIn(UnstableApi::class)
class PhoneMirrorPlayer(
    private val controls: PlaybackControls,
    private val nowPlaying: () -> NowPlaying,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    fun refresh() = invalidateState()

    override fun getState(): State {
        val now = nowPlaying()
        val snapshot = now.snapshot
        val track = snapshot?.track
        val builder = State.Builder().setAvailableCommands(COMMANDS)
        if (track == null) return builder.setPlaybackState(Player.STATE_IDLE).build()
        val playlist = listOf(
            placeholder(PREVIOUS_UID, null),
            MediaItemData.Builder(track.uri)
                .setMediaItem(item(track))
                .setDurationUs(track.durationMs.coerceAtLeast(0) * 1_000)
                .build(),
            placeholder(NEXT_UID, snapshot.nextTrack),
        )
        return builder
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(CURRENT)
            .setPlaybackState(if (snapshot.buffering) Player.STATE_BUFFERING else Player.STATE_READY)
            .setPlayWhenReady(snapshot.isPlaying || snapshot.playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs { now.positionAt(System.currentTimeMillis()) }
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        val snapshot = nowPlaying().snapshot
        val playing = snapshot != null && (snapshot.isPlaying || snapshot.playWhenReady)
        if (playing != playWhenReady) controls.togglePlay()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when {
            mediaItemIndex > CURRENT -> controls.next()
            mediaItemIndex < CURRENT -> controls.previous()
            // "Previous" late in a song lands here as a seek to its start: the phone decides.
            seekCommand == Player.COMMAND_SEEK_TO_PREVIOUS -> controls.previous()
            else -> controls.seekTo(positionMs)
        }
        return Futures.immediateVoidFuture()
    }

    private fun item(track: TrackInfo): MediaItem = MediaItem.Builder()
        .setMediaId(track.uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .setAlbumTitle(track.album)
                .build(),
        )
        .build()

    private fun placeholder(uid: String, track: TrackInfo?): MediaItemData =
        MediaItemData.Builder(uid).setMediaItem(track?.let(::item) ?: MediaItem.Builder().setMediaId(uid).build()).build()

    private companion object {
        const val CURRENT = 1
        const val PREVIOUS_UID = "mirror:previous"
        const val NEXT_UID = "mirror:next"
        val COMMANDS: Player.Commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_TIMELINE,
            )
            .build()
    }
}
