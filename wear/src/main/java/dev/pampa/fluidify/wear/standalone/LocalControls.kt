package dev.pampa.fluidify.wear.standalone

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
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
import dev.pampa.fluidify.wear.playback.ActivePlayback
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
import kotlinx.coroutines.withTimeoutOrNull
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
    /** A kept playlist's tracks from the watch's own disk, for when there is no session to read it. */
    private val offlineTracks: (String) -> List<dev.lelonio.square.data.CatalogTrack> = { emptyList() },
    /**
     * Whether every track of a kept playlist is on the watch's disk. Such a playlist is read from
     * the disk first (see [loadTracks]); it reads files, so it is asked off the main thread.
     */
    private val fullyKept: (String) -> Boolean = { false },
    /** Where the last thing played here is remembered; see [resumeLast]. */
    private val prefs: StandalonePrefs? = null,
    /** Whether a song is in Liked Songs, asked of the phone; null when it cannot say. */
    private val likedLookup: suspend (String) -> Boolean? = { null },
) : PlaybackControls {

    /** How the watch appears in its own device row: the same name as in the account's list. */
    private val watchName: String by lazy { WatchName.of(context) }

    private val _nowPlaying = MutableStateFlow(NowPlaying(null, LinkStatus.CONNECTED))
    override val nowPlaying: StateFlow<NowPlaying> = _nowPlaying.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val errors: SharedFlow<String> = _errors.asSharedFlow()

    private var controller: MediaController? = null
    private var connecting = false

    /** The controller being built, so a disconnect can cancel it; see [connect]. */
    private var building: ListenableFuture<MediaController>? = null

    /**
     * Bumped each time the controller is let go. A controller that finishes building under an older
     * number was asked for before the disconnect, and nobody wants it any more.
     */
    private var generation = 0
    private var seq = 0L
    private var sleepJob: Job? = null
    private var sleepInfo: SleepInfo? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish(player)

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            main.removeCallbacks(idle)
            if (!isPlaying) main.postDelayed(idle, IDLE_MS)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (sleepAtTrackEnd && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) endSleep()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (sleepAtTrackEnd && playbackState == Player.STATE_ENDED) endSleep()
        }
    }

    /**
     * Where the watch's player was when it went to sleep; see [rest]. Its queue is put back from
     * here the next time anything asks for the player.
     */
    private data class ResumePoint(
        val contextUri: String?,
        val label: String,
        val trackUri: String,
        val positionMs: Long,
        val shuffle: Boolean,
    )

    private var resume: ResumePoint? = null

    private val idle = Runnable { rest() }

    /** "At the end of this song": what the sleep timer waits for on the watch's own player. */
    private var sleepAtTrackEnd = false

    private val audio: AudioManager by lazy { context.getSystemService(AudioManager::class.java) }

    /** The music volume last published, in the system's steps: a change of anything else is not news. */
    private var publishedVolumeSteps = -1

    /**
     * System volume changes (the side buttons, the system's own slider) shown on the player.
     *
     * Watches the system settings, whose volume keys are named per output device
     * (`volume_music_speaker`, `volume_music_bt_a2dp`, …), so no single address covers them; every
     * other setting that changes is let through unread, and a volume change that did not move the
     * music stream (another stream's) publishes nothing.
     */
    private val volumeObserver = object : android.database.ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: android.net.Uri?) {
            if (uri != null && uri.lastPathSegment?.startsWith(MUSIC_VOLUME_SETTING) != true) return
            val steps = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (steps == publishedVolumeSteps) return
            controller?.let(::publish)
        }
    }

    private val main = Handler(Looper.getMainLooper())

    /** True while the watch's player is bound. */
    val isConnected: Boolean get() = controller != null || connecting

    /**
     * Starts the service (and with it the engine) and binds to it.
     *
     * Always on the main thread: a Media3 controller belongs to the looper it is built on, and
     * every call from another one throws. Callers on a listener's thread are moved here.
     */
    fun connect() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(::connect)
            return
        }
        if (controller != null || connecting) return
        connecting = true
        val asked = generation
        val token = SessionToken(context, ComponentName(context, WatchPlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        building = future
        future.addListener({
            if (asked != generation) {
                // disconnect() or stop() ran while this one was still being built. Assigned now, it
                // would be bound to the service for good, with nothing left that knows to let it go
                // (and a stop() would find the service bound again a moment after it stopped it).
                runCatching { future.get() }.getOrNull()?.release()
                return@addListener
            }
            building = null
            val built = runCatching { future.get() }
                .onFailure { Log.w(TAG, "the watch's player did not bind: ${it.message}") }
                .getOrNull()
            if (built == null) {
                connecting = false
                // What was asked of the player (play, next, a like) goes with it: say so, rather than
                // leaving a button that did nothing.
                if (pending.isNotEmpty()) _errors.tryEmit(ActivePlayback.ENGINE_FAILED)
                pending.clear()
                return@addListener
            }
            controller = built
            built.addListener(listener)
            runCatching { context.contentResolver.registerContentObserver(android.provider.Settings.System.CONTENT_URI, true, volumeObserver) }
            val point = resume
            resume = null
            if (point == null) {
                connecting = false
                publish(built)
                runPending(built)
            } else {
                // Back from a rest: the queue as it was, at the second it was left, before
                // whatever woke it (play, most likely) runs on it.
                scope.launch {
                    restore(built, point)
                    connecting = false
                    publish(built)
                    runPending(built)
                }
            }
        }, MoreExecutors.directExecutor())
    }

    private fun runPending(player: MediaController) {
        val actions = pending.toList()
        pending.clear()
        actions.forEach { it(player) }
    }

    private suspend fun restore(player: MediaController, point: ResumePoint) {
        val tracks = loadTracks(point.contextUri ?: point.trackUri) ?: return
        val index = tracks.indexOfFirst { it.uri == point.trackUri }.takeIf { it >= 0 } ?: return
        val items = tracks.map { it.toQueueItem(contextUri = point.contextUri ?: point.trackUri, asContext = true, contextLabel = point.label) }
        player.shuffleModeEnabled = point.shuffle
        player.setMediaItems(items, index, point.positionMs)
        player.prepare()
    }

    /**
     * Paused for [IDLE_MS]: lets the service go, and with it the engine, the Wi-Fi it held and the
     * watch's place in the account's device list. Before, the controller stayed bound for as long
     * as the watch was the player, so the service never stopped and none of that ever went.
     *
     * The song stays on screen, paused, and the place in it is kept: play brings the engine back
     * and goes on from the same second, as if it had never left.
     */
    private fun rest() {
        val current = controller ?: return
        if (current.isPlaying || current.playWhenReady) return
        val item = current.currentMediaItem
        if (item == null) {
            disconnect()
            return
        }
        val extras = item.mediaMetadata.extras
        resume = ResumePoint(
            contextUri = extras?.getString(EXTRA_CONTEXT_URI),
            label = extras?.getString(EXTRA_CONTEXT_LABEL).orEmpty(),
            trackUri = item.mediaId,
            positionMs = current.currentPosition.coerceAtLeast(0),
            shuffle = current.shuffleModeEnabled,
        )
        Log.i(TAG, "paused for a while: letting the engine go")
        releaseController()
        // The service has been idle as long; with nothing bound it now stops, and the engine goes.
    }

    /** Lets the service go; it stops on its own once nothing plays. */
    fun disconnect() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(::disconnect)
            return
        }
        resume = null
        releaseController()
        _nowPlaying.value = NowPlaying(null, LinkStatus.CONNECTED)
    }

    private fun releaseController() {
        main.removeCallbacks(idle)
        runCatching { context.contentResolver.unregisterContentObserver(volumeObserver) }
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        // A controller still being built is cancelled, or released the moment it is ready; the
        // listener in [connect] also checks the generation, for the one that finishes first.
        generation++
        building?.let { runCatching { MediaController.releaseFuture(it) } }
        building = null
        connecting = false
        pending.clear()
    }

    val isPlaying: Boolean get() = controller?.isPlaying == true

    /** Stops for good: what plays stops, the controller goes and so does the service, now. */
    fun stop() {
        resume = null
        controller?.let {
            it.pause()
            it.clearMediaItems()
        }
        pending.clear()
        disconnect()
        runCatching { context.stopService(android.content.Intent(context, WatchPlaybackService::class.java)) }
    }

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
            val ok = engineReady() && withContext(Dispatchers.IO) { runCatching { NativeBridge.setLiked(uri, liked) }.isSuccess }
            if (ok) {
                likedOverride = uri to liked
                controller?.let(::publish)
            } else {
                _errors.tryEmit("like")
            }
        }
    }

    private var likedOverride: Pair<String, Boolean>? = null

    /** The song whose heart was asked of the phone, so a run of publishes asks once. */
    private var likedAsked: String? = null

    /**
     * Whether the engine has a session, waking it for a write when the player is resting: a like
     * or a playlist needs the account, and a rested watch has let its engine go.
     */
    private suspend fun engineReady(): Boolean {
        // Asked of the engine off the main thread, and less and less often while it starts.
        suspend fun connected() = withContext(Dispatchers.IO) { runCatching { NativeBridge.isConnected }.getOrDefault(false) }
        if (connected()) return true
        if (controller == null) withController { }
        return withTimeoutOrNull(ENGINE_WAKE_MS) {
            var looks = 0
            while (!connected()) delay(PollBackoff.delayMs(looks++))
            true
        } ?: false
    }

    /** Through the watch's own engine, which is running whenever this is the player in front. */
    override suspend fun addToPlaylist(playlistUri: String, trackUri: String): Boolean {
        val written = if (!engineReady()) {
            Result.failure(IllegalStateException("the engine did not start"))
        } else {
            withContext(Dispatchers.IO) { runCatching { NativeBridge.addToPlaylist(playlistUri, trackUri) } }
        }
            .onFailure { Log.w(TAG, "not added to the playlist: ${it.message}") }
        if (written.isFailure) _errors.tryEmit(dev.pampa.fluidify.wear.protocol.AckErrors.PLAYLIST)
        return written.isSuccess
    }

    override fun playContext(contextUri: String, startTrackUri: String?, shuffle: Boolean, label: String) {
        prefs?.let {
            it.lastContext = contextUri
            it.lastContextLabel = label
        }
        // Something new to play: what was left before a rest is not coming back.
        resume = null
        scope.launch {
            val tracks = loadTracks(contextUri) ?: run {
                Log.w(TAG, "cannot read $contextUri, and nothing of it is kept here")
                _errors.tryEmit(if (contextUri.startsWith("spotify:station:")) dev.pampa.fluidify.wear.protocol.AckErrors.RADIO else dev.pampa.fluidify.wear.protocol.AckErrors.CONTEXT)
                return@launch
            }
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

    /**
     * A playlist's tracks: from the watch's own downloads when the playlist is kept whole or there
     * is no network at all, through the session otherwise, and from the downloads as the last
     * resort. Null when none has it. That is how a run with no phone and no Wi-Fi still plays the
     * playlist.
     *
     * The disk comes first for a playlist that is all there: the online call costs a round trip
     * (and, with the phone away, its whole timeout) to learn what the disk already knows, and the
     * nightly sync keeps the kept list current. The sidecars are read off the main thread.
     */
    private suspend fun loadTracks(contextUri: String): List<dev.lelonio.square.data.CatalogTrack>? {
        val preferDisk = withContext(Dispatchers.IO) { fullyKept(contextUri) } || !hasNetwork()
        if (preferDisk) {
            val kept = withContext(Dispatchers.IO) { offlineTracks(contextUri) }
            if (kept.isNotEmpty()) return kept
        }
        val online = runCatching {
            val uris = if (contextUri.startsWith("spotify:track:")) listOf(contextUri) else Catalog.contextTrackUris(contextUri)
            Catalog.tracks(uris)
        }.getOrNull()?.takeIf { it.isNotEmpty() }
        if (online != null) return online
        if (preferDisk) return null
        return withContext(Dispatchers.IO) { offlineTracks(contextUri) }.takeIf { it.isNotEmpty() }
    }

    /** Whether the system has any network to offer; a watch with none does not wait for the online call. */
    private fun hasNetwork(): Boolean =
        runCatching { context.getSystemService(ConnectivityManager::class.java).activeNetwork != null }.getOrDefault(true)

    /** Plays again what the watch last played on its own, from the top; nothing when it never did. */
    fun resumeLast() {
        val uri = prefs?.lastContext ?: return
        playContext(uri, label = prefs.lastContextLabel)
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
        // A station is a context like any other to the access point: `spotify:station:track:…`,
        // which Spotify fills with the radio for that song — the official client's own button,
        // and nothing the watch's engine cannot read for itself. Without a session there is no
        // station to read, and playContext says so.
        val track = _nowPlaying.value.snapshot?.track ?: run {
            _errors.tryEmit(dev.pampa.fluidify.wear.protocol.AckErrors.RADIO)
            return
        }
        val id = track.uri.substringAfterLast(':')
        playContext(
            contextUri = "spotify:station:track:$id",
            startTrackUri = null,
            shuffle = false,
            label = context.getString(dev.pampa.fluidify.wear.R.string.radio_of, track.title),
        )
    }

    /** Moving playback elsewhere is the [dev.pampa.fluidify.wear.playback.ActivePlayback]'s job. */
    override fun transfer(deviceId: String) = Unit

    override fun setVolume(level: Float, deviceId: String?) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (level.coerceIn(0f, 1f) * max).toInt(), 0)
        controller?.let(::publish)
    }

    override fun sleep(minutes: Int?, atTrackEnd: Boolean, cancel: Boolean) {
        sleepJob?.cancel()
        sleepInfo = null
        sleepAtTrackEnd = false
        if (!cancel && atTrackEnd) {
            // Until the song changes by itself, or the queue ends: see the listener.
            sleepAtTrackEnd = true
            sleepInfo = SleepInfo(atTrackEnd = true)
        }
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

    private fun endSleep() {
        sleepAtTrackEnd = false
        sleepInfo = null
        controller?.pause()
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
        val volumeSteps = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        publishedVolumeSteps = volumeSteps
        val volume = volumeSteps.toFloat() / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val extras = item?.mediaMetadata?.extras
        val uri = item?.mediaId
        if (uri != null && uri.startsWith("spotify:track:") && likedOverride?.first != uri && likedAsked != uri) askLiked(uri)
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
            hasPrevious = player.hasPreviousMediaItem() || player.currentPosition > RESTART_AFTER_MS,
            hasNext = player.hasNextMediaItem(),
            context = extras?.getString(EXTRA_CONTEXT_URI)?.let { ContextInfo(it, extras.getString(EXTRA_CONTEXT_LABEL).orEmpty()) },
            device = DeviceInfo(
                id = WATCH_DEVICE_ID,
                name = watchName,
                kind = DeviceKind.WATCH,
                volume = volume,
                canSetVolume = true,
            ),
            sleep = sleepInfo,
        )
        _nowPlaying.value = NowPlaying(ReceivedSnapshot(snapshot, now), LinkStatus.CONNECTED)
    }

    /**
     * The heart of a song playing here: the watch has no way to read Liked Songs by itself, the
     * phone does. Without it the heart was always empty in watch playback, and taking a like back
     * — which asks first — could never be offered.
     */
    private fun askLiked(uri: String) {
        likedAsked = uri
        scope.launch {
            val liked = runCatching { likedLookup(uri) }.getOrNull() ?: return@launch
            if (likedOverride?.first == uri) return@launch
            likedOverride = uri to liked
            controller?.let(::publish)
        }
    }

    /** A cover that could not be had: how many times in a row, and when it may be tried again. */
    private class ArtFailure(val key: String, val attempts: Int, val retryAtMs: Long)

    private var artFailure: ArtFailure? = null

    /** The covers being fetched now, so a run of publishes starts one fetch per cover. */
    private val artInFlight = HashSet<String>()

    /**
     * The cover for the player, into the same store the phone's covers go to.
     *
     * Every publish asks for a cover the store does not have, and a fetch used to end with a publish:
     * offline, or with a cover the server refused or the store rejected, that was a loop with no
     * pause. A failure is now remembered for the song's cover, and the next try waits twice as long
     * as the last (see [ArtRetry]); another song's cover starts afresh.
     * Only the main thread touches this state: the download alone runs elsewhere.
     */
    private fun fetchArt(key: String, url: String) {
        val failure = artFailure?.takeIf { it.key == key }
        if (failure != null && SystemClock.elapsedRealtime() < failure.retryAtMs) return
        if (!artInFlight.add(key)) return
        scope.launch {
            try {
                val stored = withContext(Dispatchers.IO) { download(key, url) }
                if (stored) {
                    artFailure = null
                    controller?.let(::publish)
                } else {
                    val attempts = (failure?.attempts ?: 0) + 1
                    artFailure = ArtFailure(key, attempts, SystemClock.elapsedRealtime() + ArtRetry.waitMs(attempts))
                }
            } finally {
                artInFlight.remove(key)
            }
        }
    }

    /** Whether the cover is in the store when this is done; blocking, so off the main thread. */
    private fun download(key: String, url: String): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.inputStream.use { art.store(key, it.readBytes()) }
            // The store takes no bytes it cannot read as an image, and says nothing: ask it.
            art.has(key)
        } catch (failed: Exception) {
            Log.i(TAG, "cover not fetched: ${failed.message}")
            false
        } finally {
            connection?.disconnect()
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

        /** Paused this long, the watch lets its engine go; see [rest]. The service's own idle stop. */
        private const val IDLE_MS = WatchPlaybackService.IDLE_MS

        /** How long a write waits for a resting engine to come back. */
        private const val ENGINE_WAKE_MS = 15_000L

        /** Past this far into a song, "previous" starts it again rather than going back. */
        private const val RESTART_AFTER_MS = 3_000L

        /** The prefix of the system's per-device music volume settings. */
        private const val MUSIC_VOLUME_SETTING = "volume_music"
    }
}
