package dev.pampa.fluidify.wear.standalone

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import dev.lelonio.square.data.Catalog
import dev.lelonio.square.nativecore.NativeBridge
import dev.lelonio.square.playback.toQueueItem
import dev.lelonio.square.ui.EXTRA_ARTIST_URI
import dev.lelonio.square.ui.EXTRA_CONTEXT_LABEL
import dev.lelonio.square.ui.EXTRA_CONTEXT_URI
import dev.pampa.fluidify.wear.link.ArtStore
import dev.pampa.fluidify.wear.link.LinkStatus
import dev.pampa.fluidify.wear.link.ReceivedSnapshot
import dev.pampa.fluidify.wear.playback.NowPlaying
import dev.pampa.fluidify.wear.playback.PlaybackControls
import dev.pampa.fluidify.wear.protocol.ContextInfo
import dev.pampa.fluidify.wear.protocol.DeviceInfo
import dev.pampa.fluidify.wear.protocol.DeviceKind
import dev.pampa.fluidify.wear.protocol.PlaybackSnapshot
import dev.pampa.fluidify.wear.protocol.PlaybackSource
import dev.pampa.fluidify.wear.protocol.RepeatMode
import dev.pampa.fluidify.wear.protocol.SleepInfo
import dev.pampa.fluidify.wear.protocol.TrackInfo
import dev.pampa.fluidify.wear.protocol.artKeyOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * The watch's own player, behind the same interface as the phone.
 *
 * A Media3 controller on [WatchPlaybackService], read into the same
 * [PlaybackSnapshot] the phone sends, so the player, the queue and every other
 * screen draw local playback without knowing it is local. The snapshot carries
 * [PlaybackSource.WATCH] and the watch as its device.
 */
class LocalControls(
    private val context: Context,
    private val scope: CoroutineScope,
    private val art: ArtStore,
) : PlaybackControls {

    private val _nowPlaying = MutableStateFlow(NowPlaying(null, LinkStatus.CONNECTED))
    override val nowPlaying: StateFlow<NowPlaying> = _nowPlaying.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val errors: SharedFlow<String> = _errors.asSharedFlow()

    private var controller: MediaController? = null
    private var connecting = false
    private var seq = 0L
    private var sleepJob: Job? = null
    private var sleepInfo: SleepInfo? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish(player)
    }

    /** Starts the service (and with it the engine) and binds to it. */
    fun connect() {
        if (controller != null || connecting) return
        connecting = true
        val token = SessionToken(context, ComponentName(context, WatchPlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            connecting = false
            val built = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = built
            built.addListener(listener)
            publish(built)
            pending.toList().forEach { it(built) }
            pending.clear()
        }, MoreExecutors.directExecutor())
    }

    /** Lets the service go; it stops on its own once nothing plays. */
    fun disconnect() {
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        _nowPlaying.value = NowPlaying(null, LinkStatus.CONNECTED)
    }

    val isPlaying: Boolean get() = controller?.isPlaying == true

    override fun togglePlay() = withController { if (it.isPlaying) it.pause() else it.play() }

    override fun next() = withController { it.seekToNext() }

    override fun previous() = withController { it.seekToPrevious() }

    override fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs) }

    override fun setShuffle(enabled: Boolean) = withController { it.shuffleModeEnabled = enabled }

    override fun setRepeat(mode: RepeatMode) = withController {
        it.repeatMode = when (mode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
    }

    override fun setLiked(liked: Boolean) {
        val uri = nowPlaying.value.snapshot?.track?.uri ?: return
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { NativeBridge.setLiked(uri, liked) }.isSuccess }
            if (ok) {
                likedOverride = uri to liked
                controller?.let(::publish)
            } else {
                _errors.tryEmit("like")
            }
        }
    }

    private var likedOverride: Pair<String, Boolean>? = null

    override fun playContext(contextUri: String, startTrackUri: String?, shuffle: Boolean, label: String) {
        scope.launch {
            val tracks = runCatching {
                val uris = if (contextUri.startsWith("spotify:track:")) listOf(contextUri) else Catalog.contextTrackUris(contextUri)
                Catalog.tracks(uris)
            }.getOrElse {
                Log.w(TAG, "cannot read $contextUri: ${it.message}")
                _errors.tryEmit("context")
                return@launch
            }
            if (tracks.isEmpty()) return@launch
            val items = tracks.map { it.toQueueItem(contextUri = contextUri, asContext = true, contextLabel = label) }
            val start = startTrackUri?.let { uri -> tracks.indexOfFirst { it.uri == uri } }?.takeIf { it >= 0 }
                ?: if (shuffle) tracks.indices.random() else 0
            withController {
                it.shuffleModeEnabled = shuffle
                it.setMediaItems(items, start, 0)
                it.prepare()
                it.play()
            }
        }
    }

    override fun playQueueIndex(index: Int, uri: String) = withController {
        if (index in 0 until it.mediaItemCount && it.getMediaItemAt(index).mediaId == uri) it.seekTo(index, 0)
    }

    override fun addToQueue(uri: String) {
        scope.launch {
            val track = runCatching { Catalog.tracks(listOf(uri)).firstOrNull() }.getOrNull() ?: return@launch
            withController { it.addMediaItem((it.currentMediaItemIndex + 1).coerceAtMost(it.mediaItemCount), track.toQueueItem(playNext = true)) }
        }
    }

    override fun startRadio() {
        // The phone builds a station from Spotify's recommendations over its Web API app;
        // the watch has no such app, so a station is the phone's to start.
        _errors.tryEmit("radio")
    }

    /** Moving playback elsewhere is the [dev.pampa.fluidify.wear.playback.ActivePlayback]'s job. */
    override fun transfer(deviceId: String) = Unit

    override fun setVolume(level: Float, deviceId: String?) {
        val audio = context.getSystemService(AudioManager::class.java)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (level.coerceIn(0f, 1f) * max).toInt(), 0)
        controller?.let(::publish)
    }

    override fun sleep(minutes: Int?, atTrackEnd: Boolean, cancel: Boolean) {
        sleepJob?.cancel()
        sleepInfo = null
        if (!cancel && minutes != null) {
            val endsAt = System.currentTimeMillis() + minutes * 60_000L
            sleepInfo = SleepInfo(endsAtEpochMs = endsAt)
            sleepJob = scope.launch {
                delay(minutes * 60_000L)
                controller?.pause()
                sleepInfo = null
                controller?.let(::publish)
            }
        }
        controller?.let(::publish)
    }

    private fun withController(action: (MediaController) -> Unit) {
        val current = controller
        if (current != null) {
            action(current)
        } else {
            pending += action
            connect()
        }
    }

    private fun publish(player: Player) {
        val item = player.currentMediaItem
        val now = System.currentTimeMillis()
        val artUrl = item?.mediaMetadata?.artworkUri?.toString()
        val key = artKeyOf(artUrl)
        if (key != null && artUrl != null && !art.has(key)) fetchArt(key, artUrl)
        val audio = context.getSystemService(AudioManager::class.java)
        val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
            audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val extras = item?.mediaMetadata?.extras
        val uri = item?.mediaId
        val snapshot = PlaybackSnapshot(
            seq = ++seq,
            sentAtEpochMs = now,
            source = if (item == null) PlaybackSource.NONE else PlaybackSource.WATCH,
            track = item?.let {
                TrackInfo(
                    uri = it.mediaId,
                    title = it.mediaMetadata.title?.toString().orEmpty(),
                    artist = it.mediaMetadata.artist?.toString().orEmpty(),
                    artistUri = extras?.getString(EXTRA_ARTIST_URI),
                    album = it.mediaMetadata.albumTitle?.toString(),
                    durationMs = player.duration.takeIf { d -> d > 0 } ?: (it.mediaMetadata.durationMs ?: 0),
                    artKey = key,
                    artUrl = artUrl?.takeIf { url -> url.startsWith("https://") },
                )
            },
            positionMs = player.currentPosition.coerceAtLeast(0),
            sampledAtEpochMs = now,
            speed = player.playbackParameters.speed,
            isPlaying = player.isPlaying,
            playWhenReady = player.playWhenReady,
            buffering = player.playbackState == Player.STATE_BUFFERING,
            shuffle = player.shuffleModeEnabled,
            repeat = when (player.repeatMode) {
                Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                else -> RepeatMode.OFF
            },
            liked = likedOverride?.takeIf { it.first == uri }?.second,
            hasPrevious = player.hasPreviousMediaItem() || player.currentPosition > 3_000,
            hasNext = player.hasNextMediaItem(),
            context = extras?.getString(EXTRA_CONTEXT_URI)?.let { ContextInfo(it, extras.getString(EXTRA_CONTEXT_LABEL).orEmpty()) },
            device = DeviceInfo(
                id = WATCH_DEVICE_ID,
                name = Build.MODEL ?: "Wear OS",
                kind = DeviceKind.WATCH,
                volume = volume,
                canSetVolume = true,
            ),
            sleep = sleepInfo,
        )
        _nowPlaying.value = NowPlaying(ReceivedSnapshot(snapshot, now), LinkStatus.CONNECTED)
    }

    /** The cover for the player, into the same store the phone's covers go to. */
    private fun fetchArt(key: String, url: String) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.inputStream.use { art.store(key, it.readBytes()) }
                connection.disconnect()
            }.onFailure { Log.i(TAG, "cover not fetched: ${it.message}") }
            withContext(Dispatchers.Main) { controller?.let(::publish) }
        }
    }

    /** Queue items read back for the queue screen. */
    fun queueItems(): List<MediaItem> {
        val current = controller ?: return emptyList()
        return (0 until current.mediaItemCount).map(current::getMediaItemAt)
    }

    val currentIndex: Int get() = controller?.currentMediaItemIndex ?: -1

    companion object {
        private const val TAG = "LocalControls"

        /** The id the watch uses for itself in output lists; never a Connect id. */
        const val WATCH_DEVICE_ID = "this-watch"
    }
}
