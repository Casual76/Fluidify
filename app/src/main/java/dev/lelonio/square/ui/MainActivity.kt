package dev.lelonio.square.ui

import dev.lelonio.square.playback.toQueueItem

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import androidx.lifecycle.lifecycleScope
import dev.lelonio.square.R
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.playback.PlaybackService
import dev.lelonio.square.playback.I06PlaybackIntents
import dev.lelonio.square.playback.I06QueueSourceMismatch
import dev.lelonio.square.playback.i06ForRequest
import dev.lelonio.square.playback.i06CurrentPlaybackAccess
import dev.lelonio.square.playback.i06PlaybackSelection
import dev.lelonio.square.playback.i06RequireEnqueueSource
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.guava.await

private const val ACTION_LISTEN = "dev.lelonio.square.LISTEN_ONLY"
private const val ACTION_TOGGLE = "dev.lelonio.square.PIP_TOGGLE"

/**
 * Owns the connection to the playback service; everything visual lives in
 * [SquareApp].
 *
 * The controller is activity-scoped rather than kept in a ViewModel because it
 * has to be released when the UI goes away — the session keeps playing without
 * it, and holding one from a backgrounded app leaks a binder connection.
 *
 * Nothing here needs Media3's unstable API (a controller, a session token and a
 * queue of items are all stable), so there is no opt-in on the file. There used
 * to be one, and an `@UnstableApi` that had slipped from this comment onto the
 * constant below it, which made that constant unstable for no reason.
 */
class MainActivity : ComponentActivity() {

    private var controller by mutableStateOf<MediaController?>(null)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controllerConnection: Job? = null
    private var playbackStarted = false
    private data class PlaybackOwner(
        val access: dev.lelonio.square.data.AccountLocalDataCoordinator.Access,
        val accountEpoch: Long,
    )
    private val playbackApp get() = application as dev.lelonio.square.SquareApplication
    private var playbackNotice: android.widget.Toast? = null
    private val playbackIntents by lazy {
        I06PlaybackIntents<PlaybackOwner, MediaController>(
            lifecycleScope,
            isOwnerCurrent = { owner -> i06CurrentPlaybackAccess(playbackApp.accountLocalData,
                owner.access, owner.accountEpoch, PlaybackService.mediaAccountEpoch) != null },
            onFailure = { failure ->
                showPlaybackFailure(if (failure is I06QueueSourceMismatch)
                    R.string.wf_i06_queue_source_mismatch else R.string.wf_i06_connection_failed)
            },
        )
    }

    private fun playbackOwner(): PlaybackOwner? {
        val access = playbackApp.accountLocalData.capture()
        if (access == null) showPlaybackFailure(R.string.wf_i06_connection_failed)
        return access?.let { PlaybackOwner(it, PlaybackService.mediaAccountEpoch) }
    }

    /** A local-to-online promotion may replace Access without changing the account lineage. */
    private fun currentPlaybackAccess(owner: PlaybackOwner): dev.lelonio.square.data.AccountLocalDataCoordinator.Access {
        return i06CurrentPlaybackAccess(playbackApp.accountLocalData, owner.access,
            owner.accountEpoch, PlaybackService.mediaAccountEpoch)
            ?: throw kotlinx.coroutines.CancellationException("Playback account changed")
    }

    private fun showPlaybackFailure(message: Int) {
        playbackNotice?.cancel()
        playbackNotice = android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG)
            .also { it.show() }
    }

    private val airPodsOpening = dev.lelonio.square.airpods.runtime.AirPodsAppOpening()

    private fun android.content.Intent.isOrdinaryAppOpening(): Boolean = when (action) {
        android.content.Intent.ACTION_MAIN, android.content.Intent.ACTION_VIEW,
        dev.lelonio.square.playback.ACTION_OPEN_PLAYER,
        ACTION_SHORTCUT_SEARCH, ACTION_SHORTCUT_LIBRARY, ACTION_SHORTCUT_RESUME,
        dev.lelonio.square.ui.settings.WatchPageRequest.ACTION -> true
        else -> false
    }

    /** Programmatic departures (including ActivityResult permission/settings/export flows)
     * must never turn their return into a fresh AirPods opening. Invalidate at the source,
     * before onPause, also fencing a profile callback already queued on another dispatcher. */
    private fun leavingForActivity() {
        airPodsOpening.externalActivityLaunched()
        ((application as dev.lelonio.square.SquareApplication).airPodsVisibility.active as?
            dev.lelonio.square.airpods.runtime.AirPodsForegroundState)?.invalidateForeground()
    }

    @Suppress("DEPRECATION")
    override fun startActivityForResult(intent: android.content.Intent, requestCode: Int, options: Bundle?) {
        leavingForActivity()
        super.startActivityForResult(intent, requestCode, options)
    }

    @Suppress("DEPRECATION")
    override fun startIntentSenderForResult(intent: android.content.IntentSender, requestCode: Int,
        fillInIntent: android.content.Intent?, flagsMask: Int, flagsValues: Int, extraFlags: Int, options: Bundle?) {
        leavingForActivity()
        super.startIntentSenderForResult(intent, requestCode, fillInIntent, flagsMask, flagsValues, extraFlags, options)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        leavingForActivity()
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        leavingForActivity()
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onPostResume() {
        super.onPostResume()
        if (airPodsOpening.resumed()) lifecycleScope.launch {
            (application as dev.lelonio.square.SquareApplication).airPodsRuntime?.appOpened()
        }
    }

    override fun onPause() {
        airPodsOpening.paused()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(AIRPODS_USER_OPENING, airPodsOpening.saveUserOpening())
        super.onSaveInstanceState(outState)
    }

    /**
     * Below Android 13 nothing applies the chosen language for us, so every
     * resource this activity reads has to come from a context that carries it.
     */
    override fun attachBaseContext(base: android.content.Context) {
        val store = (base.applicationContext as dev.lelonio.square.SquareApplication).language
        super.attachBaseContext(store.wrap(base))
    }

    /**
     * Bumped when something asks for the player to be open — the notification,
     * for now.
     *
     * A counter rather than a flag: two taps in a row are two requests, and a
     * boolean that is already true the second time would be ignored.
     */
    private var openPlayer by mutableStateOf(0)

    /**
     * A Spotify link the app was opened with.
     *
     * Carries a number for the same reason [openPlayer] does: the same link
     * twice is two requests, and a plain string would look unchanged the second
     * time.
     */
    private var link by mutableStateOf<LinkRequest?>(null)

    /**
     * The tab a launcher shortcut asked for, and how many times it has asked.
     *
     * A pair for the same reason [openPlayer] is a counter: long-pressing the
     * icon and picking Search twice is two requests, and a plain route would
     * look unchanged the second time.
     *
     * Static shortcuts cannot carry extras — the XML has no element for one — so
     * the destination rides in the action and is unpacked here.
     */
    private var shortcut by mutableStateOf<Pair<String, Int>?>(null)

    private fun takeShortcut(intent: android.content.Intent) {
        val route = when (intent.action) {
            dev.lelonio.square.ui.settings.WatchPageRequest.ACTION -> {
                // The watch-update notification: the settings, opened on the Watch page where the
                // update's state is, not left at the list of pages.
                dev.lelonio.square.ui.settings.WatchPageRequest.request()
                Routes.SETTINGS
            }
            ACTION_SHORTCUT_SEARCH -> Routes.SEARCH
            ACTION_SHORTCUT_LIBRARY -> Routes.LIBRARY
            ACTION_SHORTCUT_RESUME -> {
                // Whatever was left, playing, with the player open on it. The
                // controller may not have connected yet on a cold start, so the
                // play is asked for once it has.
                openPlayer++
                playbackOwner()?.let { owner ->
                    connectController()
                    playbackIntents.submit(owner, replacesQueue = true) { player, current ->
                        if (current()) playbackApp.accountLocalData.use(currentPlaybackAccess(owner)) {
                            if (!player.playWhenReady) player.play()
                        }
                    }
                }
                null
            }
            else -> return
        }
        // Consumed: a rotation must not do it again.
        intent.action = android.content.Intent.ACTION_MAIN
        if (route != null) shortcut = route to ((shortcut?.second ?: 0) + 1)
    }

    // SquareApp takes Media3's player types, which are still marked unstable.
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        airPodsOpening.created(savedInstanceState != null, intent?.isOrdinaryAppOpening() == true,
            savedInstanceState?.getBoolean(AIRPODS_USER_OPENING) == true)
        // Transparent bars; SquareTheme sets the icon colour, because it is the
        // only place that knows whether the app is currently light or dark.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )
        // The window's own colour, from the app's setting rather than from the
        // phone's.
        //
        // The starting window before this point is drawn by the system, which
        // has never heard of the setting: with the phone dark and the app told
        // to be light, a cold start began on black and corrected itself a frame
        // later. Repainting here costs nothing and closes that gap — everything
        // Compose draws on top is transparent down to this.
        val dark = when (
            (application as dev.lelonio.square.SquareApplication).preferences.readThemeMode()
        ) {
            dev.lelonio.square.data.AppThemeMode.Light -> false
            dev.lelonio.square.data.AppThemeMode.Dark,
            dev.lelonio.square.data.AppThemeMode.Amoled,
            -> true
            dev.lelonio.square.data.AppThemeMode.System ->
                resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        window.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(
                getColor(if (dark) R.color.page_floor_dark else R.color.page_floor_light),
            ),
        )

        if (intent?.opensPlayer() == true) {
            openPlayer++
        }
        intent?.let(::takeLink)
        intent?.let(::takeVoiceRequest)
        intent?.let(::takeShortcut)

        setContent {
            SquareApp(
                player = controller,
                onPlay = ::play,
                onEnqueue = ::enqueue,
                onEnqueueAll = ::enqueueAll,
                openPlayer = openPlayer,
                link = link,
                shortcut = shortcut,
            )
        }
    }

    /**
     * Shrinks into a floating window when leaving the app with a video playing.
     *
     * Only then: picture-in-picture on a music player with nothing to look at
     * would be a black rectangle covering whatever the user actually left to
     * do. The aspect ratio is the video's own, so the window is the picture
     * rather than the picture letterboxed inside a window.
     */
    override fun onUserLeaveHint() {
        airPodsOpening.userLeaveHint()
        super.onUserLeaveHint()
        val watching = dev.lelonio.square.backend.spotify.SpotifyVideoMode.enabled.value
        if (!watching) return
        runCatching { enterPictureInPictureMode(pipParams()) }
    }

    /**
     * The floating window, and the one button in it.
     *
     * The button is the thing YouTube puts there: keep the sound, drop the
     * picture. Pausing to close a video is the wrong end of what the listener
     * asked for — they left the app, so the music is what they are keeping.
     */
    private fun pipParams(): android.app.PictureInPictureParams {
        val size = controller?.videoSize
        val width = size?.width?.takeIf { it > 0 } ?: 16
        val height = size?.height?.takeIf { it > 0 } ?: 9

        // Play and pause first, because that is the one control anybody
        // expects a floating video to have.
        val playing = controller?.isPlaying == true
        val transport = android.app.RemoteAction(
            android.graphics.drawable.Icon.createWithResource(
                this,
                if (playing) {
                    dev.lelonio.square.R.drawable.ic_pip_pause
                } else {
                    dev.lelonio.square.R.drawable.ic_pip_play
                },
            ),
            getString(if (playing) dev.lelonio.square.R.string.pause else dev.lelonio.square.R.string.play),
            getString(if (playing) dev.lelonio.square.R.string.pause else dev.lelonio.square.R.string.play),
            android.app.PendingIntent.getBroadcast(
                this,
                1,
                android.content.Intent(ACTION_TOGGLE).setPackage(packageName),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE,
            ),
        )

        val listen = android.app.RemoteAction(
            android.graphics.drawable.Icon.createWithResource(this, dev.lelonio.square.R.drawable.ic_headphones),
            getString(dev.lelonio.square.R.string.pip_listen),
            getString(dev.lelonio.square.R.string.pip_listen),
            android.app.PendingIntent.getBroadcast(
                this,
                0,
                android.content.Intent(ACTION_LISTEN).setPackage(packageName),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE,
            ),
        )

        return android.app.PictureInPictureParams.Builder()
            .setAspectRatio(android.util.Rational(width, height))
            .setActions(listOf(transport, listen))
            .build()
    }

    /**
     * Told by the button above: the picture goes, the song stays.
     *
     * Registered while the activity is alive and not exported — nothing outside
     * this app has any business asking for it.
     */
    private val listenRequest = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == ACTION_TOGGLE) {
                controller?.let { if (it.isPlaying) it.pause() else it.play() }
                return
            }
            if (intent?.action != ACTION_LISTEN) return
            val at = controller?.currentPosition ?: 0L
            dev.lelonio.square.backend.spotify.SpotifyVideoMode.listen(at)
            // Said before finishing, and this matters: the flag is the app's,
            // not the window's, and finishing from inside a floating window
            // does not always report the window closing. Left set, the next
            // time the app was opened it drew the floating window's layout —
            // a black rectangle with a video surface and nothing else.
            dev.lelonio.square.backend.spotify.SpotifyVideoMode.setPictureInPicture(false)

            // Closing the window rather than going back to a full screen the
            // listener has already left. The music is in the service, and it
            // carries on.
            finish()
        }
    }

    /** Whether this activity is the floating window right now. */
    private var inPictureInPicture = false

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPicture = isInPictureInPictureMode
        dev.lelonio.square.backend.spotify.SpotifyVideoMode.setPictureInPicture(
            isInPictureInPictureMode,
        )
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        playbackIntents.start() // An explicit intent can arrive before the next onStart.
        airPodsOpening.newIntent(intent.isOrdinaryAppOpening())
        setIntent(intent)
        if (intent.opensPlayer()) {
            openPlayer++
        }
        takeLink(intent)
        takeVoiceRequest(intent)
        takeShortcut(intent)
    }

    /**
     * "Play X on Fluidify", from an assistant or a car.
     *
     * The words arrive as a search intent; the first track they match is
     * played, with the rest of the matches behind it. No words at all — "play
     * some music" — is a request to carry on: whatever the player holds, or
     * the last things played when it holds nothing.
     */
    private fun takeVoiceRequest(intent: android.content.Intent) {
        if (intent.action != android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) return
        val query = intent.getStringExtra(android.app.SearchManager.QUERY).orEmpty().trim()
        // Consumed: a rotation must not play it again.
        intent.action = android.content.Intent.ACTION_MAIN
        val owner = playbackOwner() ?: return
        val backend = playbackApp.activeBackend
        connectController()
        playbackIntents.submit(owner, replacesQueue = true) { player, current ->
            if (query.isEmpty()) {
                if (player.mediaItemCount > 0) {
                    if (current()) playbackApp.accountLocalData.use(currentPlaybackAccess(owner)) { player.play() }
                } else {
                    val recent = playbackApp.recentStore.tracks.value.toList()
                    if (current() && recent.isNotEmpty()) sendPlay(player, owner, recent, 0,
                        contextLabel = getString(R.string.play_again))
                }
            } else {
                playbackApp.accountLocalData.requireOnline(currentPlaybackAccess(owner))
                val results = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    backend.search(query, dev.lelonio.square.backend.SearchLabels(
                        artist = getString(R.string.artist), album = getString(R.string.album),
                        playlist = getString(R.string.playlist)))
                }
                if (current() && results.tracks.isNotEmpty()) sendPlay(player, owner, results.tracks, 0,
                    contextLabel = getString(R.string.search))
            }
        }
    }

    /**
     * The whole of link handling on this side: recognise it, and pass it on.
     *
     * A `singleTask` activity gets the second link through `onNewIntent` rather
     * than a new instance, so both ways in lead here.
     */
    private fun takeLink(intent: android.content.Intent) {
        if (intent.action != android.content.Intent.ACTION_VIEW) return
        val uri = dev.lelonio.square.data.SpotifyLink.parse(intent.data) ?: return
        link = LinkRequest(uri, (link?.n ?: 0) + 1)
    }

    private fun android.content.Intent.opensPlayer(): Boolean =
        action == dev.lelonio.square.playback.ACTION_OPEN_PLAYER ||
            getBooleanExtra(dev.lelonio.square.playback.EXTRA_OPEN_PLAYER, false)

    override fun onStart() {
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            listenRequest,
            android.content.IntentFilter(ACTION_LISTEN).apply { addAction(ACTION_TOGGLE) },
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        super.onStart()
        playbackStarted = true
        playbackIntents.start()
        connectController()
    }

    /** One bounded attempt per start or fresh user tap; no background reconnect loop. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun connectController() {
        if (!playbackStarted || controller?.isConnected == true || controllerConnection?.isActive == true) return
        playbackIntents.connecting()
        val job = lifecycleScope.launch(start = CoroutineStart.LAZY) {
            val lease = PlaybackService.connections.retain()
            var ownedFuture: ListenableFuture<MediaController>? = null
            try {
                val connected = try {
                    kotlinx.coroutines.withTimeout(CONTROLLER_CONNECT_MS) {
                        PlaybackService.connections.awaitReady()
                        val token = SessionToken(this@MainActivity, ComponentName(this@MainActivity, PlaybackService::class.java))
                        val future = MediaController.Builder(this@MainActivity, token)
                            .setListener(object : MediaController.Listener {
                                override fun onError(controller: MediaController, sessionError: androidx.media3.session.SessionError) {
                                    showPlaybackFailure(R.string.wf_i06_queue_failed)
                                }
                                override fun onDisconnected(disconnected: MediaController) {
                                    if (controller === disconnected) {
                                        controller = null
                                        controllerConnection?.cancel()
                                    }
                                }
                            }).buildAsync()
                        ownedFuture = future
                        controllerFuture = future
                        future.await()
                    }
                } catch (_: kotlinx.coroutines.TimeoutCancellationException) { null }
                  catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                  catch (_: Exception) { null }
                controller = connected
                playbackIntents.connected(connected)
                connected?.addListener(object : androidx.media3.common.Player.Listener {
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        showPlaybackFailure(R.string.wf_i06_queue_failed)
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (!inPictureInPicture) return
                        runCatching { setPictureInPictureParams(pipParams()) }
                    }
                })
                if (connected != null) kotlinx.coroutines.awaitCancellation()
            } finally {
                // Includes cancellation before connection and futures which complete after release.
                try {
                    ownedFuture?.let { future ->
                        if (controllerFuture === future) { controllerFuture = null; controller = null }
                        MediaController.releaseFuture(future)
                    }
                } finally { lease.close() }
            }
        }
        controllerConnection = job
        job.start()
    }

    override fun onStop() {
        playbackStarted = false
        airPodsOpening.stopped(isChangingConfigurations)
        runCatching { unregisterReceiver(listenRequest) }
        playbackIntents.stop()
        controller = null
        controllerConnection?.cancel()
        controllerConnection = null
        super.onStop()
    }

    /** Sends the visible list to the session, starting at the tapped track. */
    private fun play(
        tracks: List<CatalogTrack>,
        index: Int,
        contextUri: String? = null,
        asContext: Boolean = false,
        contextLabel: String = "",
        /**
         * Where in the track to start, for playback that is being picked up
         * rather than begun: bringing the music back from another device is the
         * one caller that has somewhere to resume from. Starting at zero and
         * seeking afterwards is not the same thing, and sounds like it: the
         * track begins, then jumps.
         */
        positionMs: Long = 0L,
    ) {
        if (tracks.isEmpty()) return
        val chosen = tracks.toList()
        if (index !in chosen.indices) { showPlaybackFailure(R.string.wf_i06_queue_failed); return }
        val owner = playbackOwner() ?: return
        connectController()
        playbackIntents.submit(owner, replacesQueue = true) { player, current ->
            if (current()) sendPlay(player, owner, chosen, index, contextUri, asContext, contextLabel, positionMs)
        }
    }

    private fun sendPlay(
        player: MediaController, owner: PlaybackOwner, tracks: List<CatalogTrack>, index: Int,
        contextUri: String? = null, asContext: Boolean = false, contextLabel: String = "",
        positionMs: Long = 0L,
    ) {
        check(player.isConnected)
        val access = currentPlaybackAccess(owner)
        val selection = i06PlaybackSelection(tracks.mapIndexed { at, track ->
            track.toQueueItem(contextUri, asContext, contextLabel, cycleAnchor = at == index)
                .i06ForRequest(access.requestSession)
        }, index)
        playbackApp.accountLocalData.use(currentPlaybackAccess(owner)) {
            player.setMediaItems(selection.items, selection.index, positionMs)
            player.prepare()
            player.play()
        }
    }

    /** A single track and a complete collection enter exactly the same protected FIFO. */
    private fun enqueue(track: CatalogTrack) = enqueueAll(listOf(track))

    private fun enqueueAll(tracks: List<CatalogTrack>) {
        if (tracks.isEmpty()) return
        val block = tracks.toList()
        val owner = playbackOwner() ?: return
        connectController()
        playbackIntents.submit(owner) { player, current ->
            if (!current()) return@submit
            check(player.isConnected)
            val access = currentPlaybackAccess(owner)
            val items = block.map { track ->
                track.toQueueItem(playNext = true).i06ForRequest(access.requestSession)
            }
            // Do not switch source to make an addition appear successful: that replaces playback.
            i06RequireEnqueueSource(player.currentMediaItem?.mediaId, items)
            playbackApp.accountLocalData.use(currentPlaybackAccess(owner)) {
                // One block, not N calls. The service/core places it after existing manual items.
                player.addMediaItems(items)
                // Empty-queue startup belongs to service admission. A newly connected controller
                // may still have an empty snapshot of an existing queue; never play it on a rejected add.
            }
        }
    }

    private companion object {
        const val AIRPODS_USER_OPENING = "airpods_user_opening"
        const val CONTROLLER_CONNECT_MS = 4_000L
    }
}
/** A `spotify:` URI the app was opened with, and which opening it was. */
data class LinkRequest(val uri: String, val n: Int)

/**
 * The three things a launcher offers under a long press on the icon.
 *
 * Actions rather than extras because a static shortcut cannot carry an extra:
 * `res/xml/shortcuts.xml` has an `<intent>` element with a target and an action
 * and nothing else. See `MainActivity.takeShortcut`.
 */
const val ACTION_SHORTCUT_RESUME = "dev.lelonio.square.SHORTCUT_RESUME"
const val ACTION_SHORTCUT_SEARCH = "dev.lelonio.square.SHORTCUT_SEARCH"
const val ACTION_SHORTCUT_LIBRARY = "dev.lelonio.square.SHORTCUT_LIBRARY"

