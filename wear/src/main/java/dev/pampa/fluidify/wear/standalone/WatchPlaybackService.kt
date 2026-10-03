package dev.pampa.fluidify.wear.standalone

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.lelonio.square.data.Catalog
import dev.lelonio.square.nativecore.NativeBridge
import dev.lelonio.square.playback.LibrespotPlayer
import dev.lelonio.square.playback.PlayQueue
import dev.pampa.fluidify.wear.WearApp
import dev.pampa.fluidify.wear.system.PlayerIntents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The watch playing on its own: the shared librespot player behind a media
 * session, for as long as the watch is the one playing.
 *
 * A fraction of the phone's PlaybackService, on purpose. No other backends, no
 * local files, no queue saved across days: the watch plays what it was handed
 * (a playlist picked on the wrist, or the phone's queue moved over through
 * Spotify Connect) and goes away when it stops. After [IDLE_MS] paused the
 * service stops itself, which takes the Connect device off the account and
 * lets the Wi-Fi go; nothing of the engine stays running in the background.
 *
 * Media3 posts the media notification while it plays, which is also what
 * Wear turns into the icon on the watch face for local playback.
 */
class WatchPlaybackService : MediaSessionService() {

    private val app get() = application as WearApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var output: WearAudioOutput
    private lateinit var player: LibrespotPlayer
    private var session: MediaSession? = null

    /** Whether this service holds a lease on the engine; see WatchEngine.acquire. */
    @Volatile private var leased = false

    /** Which context and track were adopted last, so a repeat of the same event does nothing. */
    private var adopted: String? = null

    private val idleStop = Runnable {
        if (!player.isPlaying) {
            Log.i(TAG, "idle, letting the engine go")
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        output = WearAudioOutput()
        player = LibrespotPlayer(
            this,
            Looper.getMainLooper(),
            PlayQueue(),
            { _, _ -> },
            output::fadeOutThen,
            output::fadeIn,
            {},
            {},
        )
        // Spotify Connect brought something the queue does not hold (the phone moved
        // its music here): read what the engine is playing and make it the queue.
        player.onUnknownTrack = { uri -> scope.launch { adoptPlayingTrack(uri) } }
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                handler.removeCallbacks(idleStop)
                if (!isPlaying) handler.postDelayed(idleStop, IDLE_MS)
            }
        })
        // Where the listener said, and again whenever they say somewhere else or the
        // headphones come and go.
        scope.launch {
            kotlinx.coroutines.flow.combine(app.standalone.router.chosen, app.standalone.router.outputs) { _, _ -> }
                .collect { output.preferredDevice = app.standalone.router.deviceFor(app.standalone.router.best) }
        }
        session = MediaSession.Builder(this, player)
            .setSessionActivity(PlayerIntents.openPlayer(this))
            .build()
        scope.launch { leased = app.standalone.engine.acquire(player, output) }
        handler.postDelayed(idleStop, IDLE_MS)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        handler.removeCallbacks(idleStop)
        session?.release()
        session = null
        player.release()
        val engine = app.standalone.engine
        val player = player
        if (leased) {
            // Off the main thread's lifetime: the service is going, the lease still has to.
            app.scope.launch { engine.release(player) }
        }
        output.release()
        scope.cancel()
        super.onDestroy()
    }

    /** The phone's PlaybackService does the same thing for a transfer from a computer; this is its short form. */
    private suspend fun adoptPlayingTrack(uri: String) {
        val here = withContext(Dispatchers.IO) {
            runCatching { JSONObject(NativeBridge.playingHere()) }.getOrNull()
        }
        val contextUri = here?.optString("contextUri").orEmpty()
            .takeIf { it.startsWith("spotify:") && !it.startsWith("spotify:web-api") }
        val key = "$contextUri|$uri"
        if (adopted == key) return
        adopted = key
        val uris = buildList {
            val array = here?.optJSONArray("tracks")
            for (i in 0 until (array?.length() ?: 0)) add(array!!.getString(i))
        }.ifEmpty { contextUri?.let { runCatching { Catalog.contextTrackUris(it) }.getOrNull() }.orEmpty() }
        val tracks = runCatching { Catalog.tracks(uris.ifEmpty { listOf(uri) }) }.getOrDefault(emptyList())
        val index = tracks.indexOfFirst { it.uri == uri }
        if (index < 0) return
        player.adopt(
            tracks = tracks.map { track ->
                PlayQueue.Track(
                    uri = track.uri,
                    title = track.name,
                    artist = track.artist,
                    artistUri = track.artistUri,
                    artists = track.artists,
                    durationMs = track.durationMs,
                    artworkUri = track.artworkUrl?.let(android.net.Uri::parse),
                )
            },
            index = index,
            positionMs = 0L,
            contextUri = contextUri,
            contextLabel = "",
            playing = true,
        )
    }

    private companion object {
        const val TAG = "WatchPlayback"

        /** Two minutes paused and the watch stops being a Connect device. */
        const val IDLE_MS = 120_000L
    }
}
