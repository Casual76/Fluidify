package dev.lelonio.square.wear

import android.content.ComponentName
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.android.gms.wearable.PutDataRequest
import dev.lelonio.square.BuildConfig
import dev.lelonio.square.SquareApplication
import dev.lelonio.square.data.RemoteConnect
import dev.lelonio.square.playback.PlaybackService
import dev.lelonio.square.playback.toQueueItem
import dev.pampa.fluidify.wear.protocol.AckErrors
import dev.pampa.fluidify.wear.protocol.Command
import dev.pampa.fluidify.wear.protocol.CommandAck
import dev.pampa.fluidify.wear.protocol.CommandEnvelope
import dev.pampa.fluidify.wear.protocol.ContextInfo
import dev.pampa.fluidify.wear.protocol.DeviceInfo
import dev.pampa.fluidify.wear.protocol.DeviceKind
import dev.pampa.fluidify.wear.protocol.Hello
import dev.pampa.fluidify.wear.protocol.PlaybackSnapshot
import dev.pampa.fluidify.wear.protocol.PlaybackSource
import dev.pampa.fluidify.wear.protocol.RepeatMode
import dev.pampa.fluidify.wear.protocol.SleepInfo
import dev.pampa.fluidify.wear.protocol.Role
import dev.pampa.fluidify.wear.protocol.TrackInfo
import dev.pampa.fluidify.wear.protocol.WearCodec
import dev.pampa.fluidify.wear.protocol.WearPaths
import dev.pampa.fluidify.wear.protocol.artKeyOf
import dev.pampa.fluidify.wear.protocol.logic.StateCoalescer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * The phone half of the watch remote.
 *
 * Sits beside [PlaybackService], not inside a screen, because the watch has to
 * work with the phone in a pocket and the app's UI long gone. The service hands
 * it whatever player is current ([attach]); from then on every change that
 * matters to a remote — a new song, play or pause, a seek, shuffle, repeat, the
 * heart, the device the music is coming out of — is folded into one
 * [PlaybackSnapshot] and written to the Data Layer, and nothing is written in
 * between. The position in particular is never streamed: the watch extrapolates
 * it (see [dev.pampa.fluidify.wear.protocol.logic.PositionExtrapolator]).
 *
 * Commands from the watch arrive through [PhoneWearListenerService] and are
 * applied to the same player the app's own buttons use, so a skip from the wrist
 * is indistinguishable from a skip on the phone — including when the phone is
 * controlling another Spotify Connect device, which the player already mirrors.
 *
 * With no watch running the companion, nothing here does anything.
 */
class PhoneWearBridge(private val app: SquareApplication) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val link = WearLink(app)
    private val artwork = WearArtworkSender(app, link)

    /** Keeps the watch app up to date from here; see [WatchUpdateCoordinator]. */
    val updates = WatchUpdateCoordinator(app, link, scope)
    private val coalescer = StateCoalescer()
    private val seq = AtomicLong(System.currentTimeMillis())

    private var player: Player? = null
    private var attached: CompletableDeferred<Player> = CompletableDeferred()
    private var publishJob: Job? = null
    private var publishDue = Long.MAX_VALUE
    private var observing: Job? = null

    /** A controller held only to wake the service for a command; see [ensurePlayer]. */
    private var waker: MediaController? = null
    private var wakerRelease: Job? = null

    /** The service's browse tree, for the radio; set by PlaybackService. */
    var browseTree: dev.lelonio.square.playback.MediaBrowseTree? = null

    /** Answers the watch's questions (queue, devices, library). */
    val rpc = WearRpcHandler(app, this)

    /** Signs the watch's own engine in, and out with the phone; see [WearAuthGranter]. */
    val auth = WearAuthGranter(app, link)

    /** Sends the watch the tracks it keeps; see [WatchFileServer]. */
    val files = WatchFileServer(app)

    /** Small covers for the watch's lists; see [WatchThumbServer]. */
    val thumbs = WatchThumbServer(app)

    /** The watch's downloads as the phone sees them, and the way to ask for more. */
    val watchDownloads = WatchDownloadsRemote(app, link)

    /** A watch that can play by itself has said hello. */
    val canHandoff: kotlinx.coroutines.flow.StateFlow<Boolean> = updates.watch
        .map { it?.hello?.features?.contains(dev.pampa.fluidify.wear.protocol.Features.HANDOFF) == true }
        .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)

    /**
     * "Continua sull'orologio": the watch starts its own engine, becomes a Spotify
     * Connect device, and asks this phone to transfer to it — queue and position go
     * over the way they go to any speaker. True when a watch was told.
     */
    suspend fun handoffToWatch(): Boolean = link.broadcast(WearPaths.HANDOFF_TO_WATCH, ByteArray(0))

    /** The current player, for the RPC handler. Main thread. */
    internal val currentPlayer: Player? get() = player

    private val surfacePrefs = app.getSharedPreferences("wear_surfaces", android.content.Context.MODE_PRIVATE)

    /**
     * Whether the watch asked for this phone's media notification to stay on the phone (an
     * experiment the watch's developer options turn on; see [WatchSurfaces]). Read by the
     * notification provider every time it builds the notification.
     */
    val phoneMediaLocalOnly: Boolean get() = surfacePrefs.getBoolean(KEY_LOCAL_ONLY, false)

    /**
     * Sends the account's playback to [deviceId] and says whether it went.
     *
     * Waits a moment for the device to be in the account's list first: a watch whose engine has
     * just started is known to Spotify a few seconds later, and a transfer sent before that is
     * the 404 the tests found. Through the playback service when it runs, which republishes the
     * queue as something the device can resolve first; directly otherwise.
     */
    private suspend fun transferTo(deviceId: String): Boolean {
        withTimeoutOrNull(DEVICE_LISTED_WAIT_MS) {
            RemoteConnect.devices.first { devices -> devices.any { it.id == deviceId } }
        }
        val done = CompletableDeferred<Boolean>()
        if (RemoteConnect.request(RemoteConnect.TransferRequest.To(deviceId, done))) {
            return withTimeoutOrNull(TRANSFER_WAIT_MS) { done.await() } ?: false
        }
        return withContext(Dispatchers.IO) { RemoteConnect.transferTo(deviceId) }
    }

    /** The watch published its [WatchSurfaces]. */
    fun onWatchSurfaces(bytes: ByteArray) {
        val surfaces = WearCodec.decodeOrNull(dev.pampa.fluidify.wear.protocol.WatchSurfaces.serializer(), bytes) ?: return
        surfacePrefs.edit().putBoolean(KEY_LOCAL_ONLY, surfaces.phoneMediaLocalOnly).apply()
        scope.launch { changed(urgent = true) }
    }

    /** The service redrew its notification: whether it is up is part of what the watch is told. */
    fun onNotificationUpdated() {
        val showing = mediaNotificationShowing(player)
        if (showing == lastNotificationShowing) return
        lastNotificationShowing = showing
        changed(urgent = true)
    }

    private var lastNotificationShowing: Boolean? = null

    /** The answer for a track the shared set does not know about, while it is current. */
    private var likedLookup: Pair<String, Boolean?>? = null
    private var likedJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val urgent = events.containsAny(
                Player.EVENT_MEDIA_ITEM_TRANSITION,
                Player.EVENT_IS_PLAYING_CHANGED,
                Player.EVENT_PLAY_WHEN_READY_CHANGED,
                Player.EVENT_POSITION_DISCONTINUITY,
                Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                Player.EVENT_REPEAT_MODE_CHANGED,
                Player.EVENT_MEDIA_METADATA_CHANGED,
                Player.EVENT_TIMELINE_CHANGED,
                Player.EVENT_PLAYBACK_PARAMETERS_CHANGED,
            )
            changed(urgent)
            if (player.isPlaying && events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED)) {
                rememberContext(player)
            }
        }
    }

    /**
     * Notes the playlist or album this is playing from, for the watch's Home ("what was I
     * listening to"): whoever started it, the phone, the watch, the car.
     */
    private fun rememberContext(player: Player) {
        val item = player.currentMediaItem ?: return
        val extras = player.mediaMetadata.extras ?: item.mediaMetadata.extras ?: return
        val uri = extras.getString(dev.lelonio.square.ui.EXTRA_CONTEXT_URI) ?: return
        if (!dev.lelonio.square.data.RecentContextsStore.isRememberable(uri)) return
        val label = extras.getString(dev.lelonio.square.ui.EXTRA_CONTEXT_LABEL).orEmpty()
        val name = label.ifBlank { player.mediaMetadata.albumTitle?.toString().orEmpty() }
        // An album's cover is the track's; a playlist's is its own, looked up when the Home is built.
        val art = if (uri.startsWith("spotify:album:")) player.mediaMetadata.artworkUri?.toString()?.takeIf { it.startsWith("https://") } else null
        app.recentContexts.record(uri, name, art)
    }

    // --- The service's side -------------------------------------------------------

    /** The service has a (new) player. Main thread. */
    fun attach(player: Player) {
        if (this.player === player) return
        this.player?.removeListener(listener)
        this.player = player
        player.addListener(listener)
        if (!attached.isCompleted) attached.complete(player)
        startObserving()
        changed(urgent = true)
    }

    /** The service is going away. Main thread. */
    fun detach() {
        player?.removeListener(listener)
        player = null
        attached = CompletableDeferred()
        observing?.cancel()
        observing = null
        publishJob?.cancel()
        // The last word: nothing is playing here any more.
        scope.launch { publishNow(force = false) }
    }

    /** Re-publishes when something outside the player changes what the watch should show. */
    private fun startObserving() {
        if (observing?.isActive == true) return
        observing = scope.launch {
            combine(
                app.likedTracks.state,
                RemoteConnect.elsewhereActive,
                RemoteConnect.devices,
                dev.lelonio.square.playback.SleepTimer.endsAt,
                dev.lelonio.square.playback.SleepTimer.atTrackEnd,
            ) { liked, elsewhere, devices, sleepAt, sleepEnd -> listOf(liked, elsewhere, devices, sleepAt, sleepEnd) }
                .distinctUntilChanged()
                .collect { changed(urgent = true) }
        }
    }

    // --- Publishing -----------------------------------------------------------------

    private fun changed(urgent: Boolean) {
        val now = System.currentTimeMillis()
        val due = coalescer.offer(now, urgent)
        if (publishJob?.isActive == true && publishDue <= due) return
        publishJob?.cancel()
        publishDue = due
        publishJob = scope.launch {
            delay((due - System.currentTimeMillis()).coerceAtLeast(0))
            publishNow(force = false)
        }
    }

    /**
     * Writes the current state to the Data Layer now. Returns the snapshot's seq.
     *
     * @param force write even if no watch is known to have the companion, which
     *   is the case right after a hello from a watch the cache has not caught up with.
     */
    suspend fun publishNow(force: Boolean): Long {
        val now = System.currentTimeMillis()
        coalescer.markSent(now)
        publishDue = Long.MAX_VALUE
        if (!force && !link.hasWatch()) return seq.get()
        val snapshot = withContext(Dispatchers.Main.immediate) { buildSnapshot() }
        val request = PutDataRequest.create(WearPaths.STATE)
            .setData(WearCodec.encode(PlaybackSnapshot.serializer(), snapshot))
            .setUrgent()
        link.put(request)
        sendCovers(snapshot)
        return snapshot.seq
    }

    private fun sendCovers(snapshot: PlaybackSnapshot) {
        val current = player ?: return
        val item = current.currentMediaItem
        val key = snapshot.track?.artKey
        val metadata = current.mediaMetadata
        val url = metadata.artworkUri?.toString() ?: item?.mediaMetadata?.artworkUri?.toString()
        val bytes = metadata.artworkData
        val nextKey = snapshot.nextArtKey
        val nextUrl = nextItem(current)?.mediaMetadata?.artworkUri?.toString()
        scope.launch(Dispatchers.IO) {
            if (key != null) artwork.ensure(key, bytes, url)
            if (nextKey != null && nextKey != key) artwork.ensure(nextKey, null, nextUrl)
        }
    }

    private fun nextItem(player: Player) =
        player.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET }?.let { player.getMediaItemAt(it) }

    private fun buildSnapshot(): PlaybackSnapshot {
        val now = System.currentTimeMillis()
        val current = player
        val item = current?.currentMediaItem
        val device = currentDevice()
        if (current == null || item == null) {
            return PlaybackSnapshot(seq = seq.incrementAndGet(), sentAtEpochMs = now, device = device)
        }
        val metadata = current.mediaMetadata
        val extras = metadata.extras ?: item.mediaMetadata.extras
        val uri = item.mediaId
        val artUrl = (metadata.artworkUri ?: item.mediaMetadata.artworkUri)?.toString()
        val duration = current.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        val elsewhere = RemoteConnect.elsewhereActive.value
        return PlaybackSnapshot(
            seq = seq.incrementAndGet(),
            sentAtEpochMs = now,
            source = if (elsewhere) PlaybackSource.CONNECT_REMOTE else PlaybackSource.PHONE,
            track = TrackInfo(
                uri = uri,
                title = (metadata.title ?: item.mediaMetadata.title)?.toString().orEmpty(),
                artist = (metadata.artist ?: item.mediaMetadata.artist)?.toString().orEmpty(),
                artistUri = extras?.getString(dev.lelonio.square.ui.EXTRA_ARTIST_URI),
                album = metadata.albumTitle?.toString(),
                albumUri = albumFor(uri),
                durationMs = duration,
                artKey = artKeyOf(artUrl),
                artUrl = artUrl?.takeIf { it.startsWith("https://") },
            ),
            positionMs = current.currentPosition.coerceAtLeast(0),
            sampledAtEpochMs = now,
            speed = current.playbackParameters.speed,
            isPlaying = current.isPlaying,
            playWhenReady = current.playWhenReady,
            buffering = current.playbackState == Player.STATE_BUFFERING,
            shuffle = current.shuffleModeEnabled,
            repeat = when (current.repeatMode) {
                Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                else -> RepeatMode.OFF
            },
            liked = likedFor(uri),
            hasPrevious = current.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS),
            hasNext = current.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT),
            context = extras?.getString(dev.lelonio.square.ui.EXTRA_CONTEXT_URI)?.let { contextUri ->
                ContextInfo(contextUri, extras.getString(dev.lelonio.square.ui.EXTRA_CONTEXT_LABEL).orEmpty())
            },
            device = device,
            sleep = sleepInfo(),
            offline = dev.lelonio.square.playback.OfflineMode.active.value,
            nextArtKey = artKeyOf(nextItem(current)?.mediaMetadata?.artworkUri?.toString()),
            systemMediaControls = mediaNotificationShowing(current).also { lastNotificationShowing = it },
            nextTrack = nextItem(current)?.let { next ->
                val nextArt = next.mediaMetadata.artworkUri?.toString()
                TrackInfo(
                    uri = next.mediaId,
                    title = next.mediaMetadata.title?.toString().orEmpty(),
                    artist = next.mediaMetadata.artist?.toString().orEmpty(),
                    artKey = artKeyOf(nextArt),
                    artUrl = nextArt?.takeIf { it.startsWith("https://") },
                )
            }?.takeIf { it.title.isNotEmpty() },
        )
    }

    /**
     * Whether this phone's media notification is up — which is what the watch's system media
     * controls show — or about to be, for a player that has just been given something to play.
     *
     * The second half matters: the snapshot for a fresh start is built before the session has
     * posted its notification, and a watch told "not up" for that half second would put its own
     * entry on the watch face and take it away again on every play.
     */
    private fun mediaNotificationShowing(current: Player?): Boolean {
        if (!androidx.core.app.NotificationManagerCompat.from(app).areNotificationsEnabled()) return false
        val manager = app.getSystemService(android.app.NotificationManager::class.java)
        val posted = runCatching {
            manager?.activeNotifications?.any { it.notification.extras.containsKey(android.app.Notification.EXTRA_MEDIA_SESSION) } == true
        }.getOrDefault(false)
        if (posted) return true
        return current != null && current.mediaItemCount > 0 &&
            current.playbackState != Player.STATE_IDLE && current.playbackState != Player.STATE_ENDED
    }

    private fun sleepInfo(): SleepInfo? {
        val endsAt = dev.lelonio.square.playback.SleepTimer.endsAt.value
        val atEnd = dev.lelonio.square.playback.SleepTimer.atTrackEnd.value
        if (endsAt == null && !atEnd) return null
        return SleepInfo(endsAtEpochMs = endsAt, atTrackEnd = atEnd)
    }

    /** The heart for [uri]: known, looked up once, or unknown (null) while the lookup runs. */
    private fun likedFor(uri: String): Boolean? {
        if (!uri.startsWith("spotify:track:")) return null
        if (uri in app.likedTracks.state.value) return true
        likedLookup?.takeIf { it.first == uri }?.let { return it.second }
        likedLookup = uri to null
        likedJob?.cancel()
        likedJob = scope.launch {
            val answer = withContext(Dispatchers.IO) { app.likedTracks.isLiked(uri) }
            if (likedLookup?.first == uri) {
                likedLookup = uri to answer
                changed(urgent = true)
            }
        }
        return null
    }

    /**
     * The album [uri] is on, for the watch's "go to album". No queue item says
     * which album it is (the phone's own screens never needed to), so it is
     * read once per track from the track's metadata and the snapshot sent again.
     */
    private fun albumFor(uri: String): String? {
        if (!uri.startsWith("spotify:track:")) return null
        albumLookup?.takeIf { it.first == uri }?.let { return it.second }
        albumLookup = uri to null
        albumJob?.cancel()
        albumJob = scope.launch {
            val album = withContext(Dispatchers.IO) {
                runCatching { dev.lelonio.square.data.Catalog.tracks(listOf(uri)).firstOrNull()?.albumUri }.getOrNull()
            }
            if (albumLookup?.first == uri && album != null) {
                albumLookup = uri to album
                changed(urgent = false)
            }
        }
        return null
    }

    private var albumLookup: Pair<String, String?>? = null
    private var albumJob: kotlinx.coroutines.Job? = null

    internal fun currentDevice(): DeviceInfo {
        val active = RemoteConnect.devices.value.firstOrNull { it.active }
        if (RemoteConnect.elsewhereActive.value && active != null && !active.isThisPhone) {
            return DeviceInfo(
                id = active.id,
                name = active.name,
                kind = kindOf(active.type),
                isThisPhone = false,
                volume = active.volume / 65535f,
                canSetVolume = true,
            )
        }
        val audio = app.getSystemService(AudioManager::class.java)
        val max = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 1
        val volume = audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
        return DeviceInfo(
            id = PHONE_DEVICE_ID,
            name = phoneName(),
            kind = DeviceKind.PHONE,
            isThisPhone = true,
            volume = volume.toFloat() / max,
            canSetVolume = true,
        )
    }

    private fun phoneName(): String =
        runCatching {
            android.provider.Settings.Global.getString(app.contentResolver, android.provider.Settings.Global.DEVICE_NAME)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL

    // --- The watch's side -------------------------------------------------------------

    /** A watch introduced itself. */
    suspend fun onHello(nodeId: String, hello: Hello) {
        link.noteWatchSeen()
        updates.onWatchHello(nodeId, hello)
        if (hello.wantsReply) {
            link.send(nodeId, WearPaths.HELLO, WearCodec.encode(Hello.serializer(), ownHello(wantsReply = false)))
        }
        withContext(Dispatchers.Main.immediate) { publishNow(force = true) }
        // Who is signed in, so a watch that missed a sign-out still hears of it.
        auth.publishAccount()
        // Download requests made while the watch was away.
        watchDownloads.flushPending(nodeId)
    }

    fun ownHello(wantsReply: Boolean): Hello = Hello(
        role = Role.PHONE,
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE.toLong(),
        features = PHONE_FEATURES,
        buildType = BuildConfig.BUILD_TYPE,
        certSha256 = WearLink.certificateSha256(app),
        abis = Build.SUPPORTED_ABIS.toList(),
        sdk = Build.VERSION.SDK_INT,
        wantsReply = wantsReply,
    )

    /** A command from the watch at [nodeId]. Applies it and acknowledges it. */
    suspend fun onCommand(nodeId: String, envelope: CommandEnvelope) {
        link.noteWatchSeen()
        val result = runCatching { withContext(Dispatchers.Main.immediate) { apply(envelope.command) } }
        val error = result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
            ?: result.getOrNull()
        val appliedSeq = if (error == null) withContext(Dispatchers.Main.immediate) { publishNow(force = true) } else null
        val ack = CommandAck(id = envelope.id, ok = error == null, error = error, appliedSeq = appliedSeq)
        link.send(nodeId, WearPaths.ACK, WearCodec.encode(CommandAck.serializer(), ack))
    }

    /** Applies [command]; returns an error string, or null when it was applied. Main thread. */
    private suspend fun apply(command: Command): String? {
        when (command) {
            is Command.SetLiked -> {
                withContext(Dispatchers.IO) { app.likedTracks.set(command.uri, command.liked) }
                if (likedLookup?.first == command.uri) likedLookup = command.uri to command.liked
                return null
            }
            is Command.SetVolume -> {
                ConnectVolume.set(app, command.level, command.deviceId)
                changed(urgent = true)
                return null
            }
            is Command.Transfer -> {
                val here = command.deviceId == PHONE_DEVICE_ID || RemoteConnect.isThisPhone(command.deviceId)
                if (here) {
                    RemoteConnect.request(RemoteConnect.TransferRequest.Here)
                    return null
                }
                return if (transferTo(command.deviceId)) null else AckErrors.TRANSFER
            }
            is Command.SetPinned -> {
                if (app.pinnedPlaylists.isPinned(command.uri) != command.pinned) app.pinnedPlaylists.toggle(command.uri)
                return null
            }
            is Command.SleepTimer -> {
                when {
                    command.cancel -> dev.lelonio.square.playback.SleepTimer.cancel()
                    command.atTrackEnd -> dev.lelonio.square.playback.SleepTimer.atEndOfTrack()
                    command.minutes != null -> dev.lelonio.square.playback.SleepTimer.inMinutes(command.minutes!!)
                    else -> return "bad-timer"
                }
                return null
            }
            else -> Unit
        }
        val player = ensurePlayer() ?: return "phone-unavailable"
        when (command) {
            Command.Play -> playWhenLoaded(player)
            Command.Pause -> player.pause()
            Command.TogglePlay -> if (player.isPlaying || player.playWhenReady) player.pause() else playWhenLoaded(player)
            Command.Next -> if (!dev.lelonio.square.playback.PlaybackTransport.skip(player, forward = true)) return AckErrors.NOTHING_TO_SKIP
            Command.Previous -> if (!dev.lelonio.square.playback.PlaybackTransport.skip(player, forward = false)) return AckErrors.NOTHING_TO_SKIP
            is Command.SeekTo -> player.seekTo(command.positionMs.coerceAtLeast(0))
            is Command.SetShuffle -> player.shuffleModeEnabled = command.enabled
            is Command.SetRepeat -> player.repeatMode = when (command.mode) {
                RepeatMode.ONE -> Player.REPEAT_MODE_ONE
                RepeatMode.ALL -> Player.REPEAT_MODE_ALL
                RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            }
            is Command.PlayQueueIndex -> {
                // The index is the watch's, read from a window that may be a moment old: only
                // jump when it still points at the track the person tapped.
                val index = command.index.takeIf { it in 0 until player.mediaItemCount && player.getMediaItemAt(it).mediaId == command.uri }
                    ?: (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == command.uri }
                    ?: return "not-in-queue"
                player.seekTo(index, 0)
                playWhenLoaded(player)
            }
            is Command.StartRadio -> {
                val tree = browseTree ?: return "phone-unavailable"
                tree.startRadio(player)
            }
            is Command.PlayContext -> {
                val tracks = withContext(Dispatchers.IO) { app.spotifyBackend.tracksOf(command.contextUri) }
                if (tracks.isEmpty()) return "empty"
                val start = command.startTrackUri?.let { uri -> tracks.indexOfFirst { it.uri == uri } }?.takeIf { it >= 0 } ?: 0
                player.setMediaItems(
                    tracks.map { it.toQueueItem(command.contextUri, asContext = true, contextLabel = command.label) },
                    start,
                    0,
                )
                player.shuffleModeEnabled = command.shuffle
                player.prepare()
                player.play()
            }
            is Command.AddToQueue -> {
                val track = withContext(Dispatchers.IO) {
                    dev.lelonio.square.data.Catalog.tracks(listOf(command.uri)).firstOrNull()
                } ?: return "not-found"
                player.addMediaItem(track.toQueueItem(playNext = true))
            }
            else -> return "unsupported"
        }
        return null
    }

    /**
     * Play, after the restored queue has had a moment to arrive.
     *
     * A service woken by the watch restores the saved queue asynchronously; a
     * play() issued before it lands has nothing to play and is lost.
     */
    private suspend fun playWhenLoaded(player: Player) {
        withTimeoutOrNull(QUEUE_WAIT_MS) {
            while (player.mediaItemCount == 0) delay(100)
        }
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }

    /**
     * The service's player, waking the service if it is not running.
     *
     * Waking is done by connecting a controller, which binds the service and
     * runs its onCreate, which calls [attach]. The controller is let go shortly
     * after: holding the binding would keep the service, and the engine with it,
     * alive after the listener has stopped, which is not the remote's call to make.
     */
    /**
     * Wakes the playback service — and with it the engine, which it starts on creation — the way
     * the watch's commands do: by binding to it, which a process the listener service woke may do,
     * where starting it may be refused. True when the service is up.
     */
    suspend fun wakePlayback(): Boolean = withContext(Dispatchers.Main.immediate) { ensurePlayer() != null }

    private suspend fun ensurePlayer(): Player? {
        player?.let { return it }
        if (waker == null) {
            val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
            waker = runCatching { MediaController.Builder(app, token).buildAsync().await() }
                .onFailure { Log.w(TAG, "cannot wake the playback service", it) }
                .getOrNull()
        }
        wakerRelease?.cancel()
        wakerRelease = scope.launch {
            delay(WAKER_HOLD_MS)
            waker?.release()
            waker = null
        }
        return withTimeoutOrNull(ATTACH_WAIT_MS) { attached.await() }
    }

    companion object {
        private const val KEY_LOCAL_ONLY = "phone_media_local_only"
        private const val DEVICE_LISTED_WAIT_MS = 3_000L
        private const val TRANSFER_WAIT_MS = 4_000L
        private const val TAG = "PhoneWearBridge"
        const val PHONE_DEVICE_ID = "phone"
        private const val QUEUE_WAIT_MS = 4_000L
        private const val ATTACH_WAIT_MS = 8_000L
        private const val WAKER_HOLD_MS = 30_000L

        /** What this phone build can do for a watch. Grows with each milestone. */
        val PHONE_FEATURES: Set<String> = setOf(
            dev.pampa.fluidify.wear.protocol.Features.VOLUME,
            dev.pampa.fluidify.wear.protocol.Features.DEVICES,
            dev.pampa.fluidify.wear.protocol.Features.QUEUE,
            dev.pampa.fluidify.wear.protocol.Features.SLEEP,
            dev.pampa.fluidify.wear.protocol.Features.LIBRARY,
            dev.pampa.fluidify.wear.protocol.Features.UPDATE_PUSH,
            dev.pampa.fluidify.wear.protocol.Features.AUTH,
            dev.pampa.fluidify.wear.protocol.Features.DOWNLOADS,
        )

        /** librespot's device type names, as Spotify Connect reports them. */
        fun kindOf(type: String): DeviceKind = when (type.lowercase()) {
            "smartphone" -> DeviceKind.PHONE
            "computer", "chromebook" -> DeviceKind.COMPUTER
            "tablet" -> DeviceKind.TABLET
            "speaker", "audiodongle", "avr", "homething", "castaudio" -> DeviceKind.SPEAKER
            "tv", "stb", "castvideo", "gameconsole" -> DeviceKind.TV
            "automobile", "carthing" -> DeviceKind.CAR
            "smartwatch" -> DeviceKind.WATCH
            else -> DeviceKind.OTHER
        }
    }
}
