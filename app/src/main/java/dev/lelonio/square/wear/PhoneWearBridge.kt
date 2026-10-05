@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

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
import dev.lelonio.square.nativecore.NativeBridge
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private var lightPlayer: dev.lelonio.square.playback.AudioReactivePlayer? = null
    private var attached: CompletableDeferred<Player> = CompletableDeferred()
    private var publishJob: Job? = null
    private var publishDue = Long.MAX_VALUE
    private var observing: Job? = null

    /** A controller held only to wake the service for a command; see [ensurePlayer]. */
    private var waker: MediaController? = null
    private var wakerRelease: Job? = null

    /**
     * Held while the waker is made or let go. Two commands arriving together (a double tap on the
     * wrist) both used to see no waker, and each built a controller, one of which was never released.
     */
    private val wakerLock = Mutex()

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

    private val _isPlaying = MutableStateFlow(false)

    /**
     * Whether the current player is playing, for what only matters while it is (the audio light's
     * loop sleeps on this rather than spinning through a pause). False with no player.
     */
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

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

    /** Opening Watch settings re-establishes hello, including with older Wear builds. */
    suspend fun refreshWatchLink() {
        withTimeoutOrNull(6_000) {
            for (node in link.watchNodes(reachableOnly = true)) {
                if (link.awaitNearbyWatch(node.id, timeoutMs = 1_500)) {
                    link.send(node.id, WearPaths.HELLO, WearCodec.encode(Hello.serializer(), ownHello(wantsReply = true)))
                }
            }
        }
    }

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
        // The list here only changes when the account pushes a cluster update, and nothing would
        // ask for one: a watch that had just appeared stayed missing for the whole wait. One
        // request is enough to have the account answer with the current list. (The watch now asks
        // only once the account has acknowledged it, so the wait is normally already over.)
        if (RemoteConnect.devices.value.none { it.id == deviceId }) {
            withContext(Dispatchers.IO) { runCatching { NativeBridge.refreshCluster() } }
        }
        withTimeoutOrNull(DEVICE_LISTED_WAIT_MS) {
            RemoteConnect.devices.first { devices -> devices.any { it.id == deviceId } }
        }
        val done = CompletableDeferred<Boolean>()
        if (RemoteConnect.request(RemoteConnect.TransferRequest.To(deviceId, done))) {
            return withTimeoutOrNull(TRANSFER_WAIT_MS) { done.await() } ?: false
        }
        return withContext(Dispatchers.IO) { RemoteConnect.transferTo(deviceId) }
    }

    /**
     * Asks the playback service to post its notification again: for a setting that changes how it
     * is built (see [phoneMediaLocalOnly]), which otherwise waited for the next player event — never,
     * while paused.
     */
    val notificationRefresh = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The watch published its [WatchSurfaces]. */
    fun onWatchSurfaces(bytes: ByteArray) {
        val surfaces = WearCodec.decodeOrNull(dev.pampa.fluidify.wear.protocol.WatchSurfaces.serializer(), bytes) ?: return
        val changedLocalOnly = surfaces.phoneMediaLocalOnly != phoneMediaLocalOnly
        surfacePrefs.edit().putBoolean(KEY_LOCAL_ONLY, surfaces.phoneMediaLocalOnly).apply()
        if (changedLocalOnly) notificationRefresh.tryEmit(Unit)
        scope.launch { changed(urgent = true) }
    }

    /** The service redrew its notification: whether it is up is part of what the watch is told. */
    fun onNotificationUpdated() {
        // Asked of the system afresh: the point of the call is that the answer just changed.
        lastProbe = null
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
            _isPlaying.value = player.isPlaying
            if (player.isPlaying && events.contains(Player.EVENT_IS_PLAYING_CHANGED)) playbackStartedAt = System.currentTimeMillis()
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
        // The album's title names an album; for a playlist with no label (a handover from another
        // device, the car) it named the playlist after whatever song was playing, and renamed it
        // with every track. Unlabelled, a playlist keeps the name it had, or is not recorded.
        val known = app.recentContexts.contexts.value.firstOrNull { it.uri == uri }?.name
        val name = label.ifBlank {
            if (uri.startsWith("spotify:album:")) player.mediaMetadata.albumTitle?.toString().orEmpty() else known.orEmpty()
        }
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
        lightPlayer?.close()
        lightPlayer = dev.lelonio.square.playback.AudioReactivePlayer(player)
        player.addListener(listener)
        _isPlaying.value = player.isPlaying
        if (!attached.isCompleted) attached.complete(player)
        startObserving()
        changed(urgent = true)
    }

    /** The service is going away. Main thread. */
    fun detach() {
        lightPlayer?.close(); lightPlayer = null
        player?.removeListener(listener)
        player = null
        _isPlaying.value = false
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
                .collect {
                    // The library changed (a like taken back in the app, in the car): what was
                    // looked up for the current song may be out of date.
                    likedLookup = null
                    changed(urgent = true)
                }
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
    suspend fun publishNow(force: Boolean, requestedPlaying: Boolean? = null): Long {
        val now = System.currentTimeMillis()
        coalescer.markSent(now)
        publishDue = Long.MAX_VALUE
        // A publish now makes the one scheduled for later redundant: each command used to write
        // the state twice. Not when this is that scheduled one.
        publishJob?.takeIf { it !== kotlinx.coroutines.currentCoroutineContext()[Job] }?.cancel()
        if (!force && !link.hasWatch()) return seq.get()
        val snapshot = withContext(Dispatchers.Main.immediate) {
            buildSnapshot().let { built ->
                if (requestedPlaying == null) built else built.copy(
                    isPlaying = requestedPlaying && !built.buffering,
                    playWhenReady = requestedPlaying,
                )
            }
        }
        // Nothing the watch would draw differently: not written. Every snapshot carries a new seq
        // and new times, so the Data Layer never saw two as the same and sent them all.
        val previous = lastPublished
        if (!force && previous != null && sameForTheWatch(previous, snapshot)) return previous.seq
        val request = PutDataRequest.create(WearPaths.STATE)
            .setData(WearCodec.encode(PlaybackSnapshot.serializer(), snapshot))
            .setUrgent()
        link.put(request)
        lastPublished = snapshot
        sendCovers(snapshot)
        return snapshot.seq
    }

    private var lastPublished: PlaybackSnapshot? = null

    /**
     * Whether [next] says nothing [previous] did not: equal apart from the counter and the clock, and
     * with a position the watch would have worked out from [previous] anyway.
     */
    private fun sameForTheWatch(previous: PlaybackSnapshot, next: PlaybackSnapshot): Boolean {
        val aligned = previous.copy(seq = next.seq, sentAtEpochMs = next.sentAtEpochMs, sampledAtEpochMs = next.sampledAtEpochMs, positionMs = next.positionMs)
        if (aligned != next) return false
        val expected = if (previous.isPlaying && !previous.buffering) {
            previous.positionMs + ((next.sampledAtEpochMs - previous.sampledAtEpochMs) * previous.speed).toLong()
        } else {
            previous.positionMs
        }
        return kotlin.math.abs(expected - next.positionMs) < POSITION_DRIFT_MS
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
            if (key != null) artwork.ensure(key, bytes, url, urgent = true)
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
            // Whether there is a next song, not whether the player has a "next" button (it always
            // has): another device decides for itself, this one is out of songs at the queue's end.
            hasNext = elsewhere || current.hasNextMediaItem(),
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
        // Not "are notifications allowed": a media session's notification is exempt from that
        // permission on Android 13+ and shows (and reaches the watch) without it; asking said "no"
        // and put a second Fluidify on the watch face next to the system's.
        val probe = notificationProbe()
        if (probe.posted) return true
        // The listener blocked the media channel: nothing will be posted, so nothing is coming.
        if (probe.channelBlocked) return false
        // About to be: only just after a start, not for as long as a queue is loaded.
        val starting = System.currentTimeMillis() - playbackStartedAt < NOTIFICATION_GRACE_MS
        return starting && current != null && current.mediaItemCount > 0 &&
            current.playbackState != Player.STATE_IDLE && current.playbackState != Player.STATE_ENDED
    }

    /** When the player last started playing; see [mediaNotificationShowing]. */
    private var playbackStartedAt = 0L

    /** What the system said about the media notification, and when it was asked. */
    private class NotificationProbe(val posted: Boolean, val channelBlocked: Boolean, val askedAtMs: Long)

    private var lastProbe: NotificationProbe? = null

    /**
     * Whether the media notification is posted and whether its channel is blocked, asked of the
     * system (two binder calls, on the main thread) at most once per [NOTIFICATION_PROBE_TTL_MS].
     * A snapshot is built for every publish, and a burst of them (a skip is several events) asked
     * the notification manager every time. The service's own word that it redrew its notification
     * ([onNotificationUpdated]) drops the cached answer, so the one that matters is never stale.
     */
    private fun notificationProbe(): NotificationProbe {
        val now = android.os.SystemClock.elapsedRealtime()
        lastProbe?.takeIf { now - it.askedAtMs < NOTIFICATION_PROBE_TTL_MS }?.let { return it }
        val manager = app.getSystemService(android.app.NotificationManager::class.java)
        val posted = runCatching {
            manager?.activeNotifications?.any { it.notification.extras.containsKey(android.app.Notification.EXTRA_MEDIA_SESSION) } == true
        }.getOrDefault(false)
        val channelBlocked = !posted && runCatching {
            manager?.getNotificationChannel(androidx.media3.session.DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID)
        }.getOrNull()?.importance == android.app.NotificationManager.IMPORTANCE_NONE
        return NotificationProbe(posted, channelBlocked, now).also { lastProbe = it }
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
            var answer = withContext(Dispatchers.IO) { app.likedTracks.isLiked(uri) }
            // Null is also "someone else is asking right now" (the car, the app): asked again once
            // that has had time to land, rather than left as an empty heart for the whole song.
            if (answer == null) {
                delay(LIKED_RETRY_MS)
                answer = withContext(Dispatchers.IO) { app.likedTracks.isLiked(uri) }
            }
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
        sentAtEpochMs = System.currentTimeMillis(),
    )

    /** A command from the watch at [nodeId]. Applies it and acknowledges it. */
    suspend fun onCommand(nodeId: String, envelope: CommandEnvelope) {
        link.noteWatchSeen()
        val requestedPlaying = withContext(Dispatchers.Main.immediate) {
            when (envelope.command) {
                Command.Play -> true
                Command.Pause -> false
                // Read from the player itself: a whole snapshot (a notification lookup, a
                // device read, a queue peek) was built only to look at two booleans.
                Command.TogglePlay -> player?.takeIf { it.currentMediaItem != null }
                    ?.let { current -> !(current.isPlaying || current.playWhenReady) } ?: true
                else -> null
            }
        }
        val result = catchingNonCancel { withContext(Dispatchers.Main.immediate) { apply(envelope.command) } }
        val failure = result.exceptionOrNull()
        // The detail stays here, and only its kind: an exception's message can carry a URI or a
        // token, and it is not the watch's to read. The watch is sent a stable code.
        if (failure != null) Log.w(TAG, "command ${envelope.command::class.simpleName} failed: ${failure.javaClass.simpleName}")
        val error = if (failure != null) AckErrors.INTERNAL else result.getOrNull()
        // Player callbacks can arrive after the acknowledgement. Its snapshot must already carry
        // the accepted play/pause state so the wrist does not briefly bounce back to the old one.
        val appliedSeq = if (error == null) withContext(Dispatchers.Main.immediate) { publishNow(force = true, requestedPlaying) } else null
        val ack = CommandAck(id = envelope.id, ok = error == null, error = error, appliedSeq = appliedSeq, sentAtEpochMs = System.currentTimeMillis())
        link.send(nodeId, WearPaths.ACK, WearCodec.encode(CommandAck.serializer(), ack))
    }

    /**
     * A command whose type this phone does not know (a newer watch's) arrived: only its [id] could
     * be read. Answered with [AckErrors.UNSUPPORTED] so the watch takes the command back at once
     * and says so, rather than waiting out its timeout and reporting an unreachable phone.
     */
    suspend fun onUnsupportedCommand(nodeId: String, id: Long) {
        link.noteWatchSeen()
        val ack = CommandAck(id = id, ok = false, error = AckErrors.UNSUPPORTED, sentAtEpochMs = System.currentTimeMillis())
        link.send(nodeId, WearPaths.ACK, WearCodec.encode(CommandAck.serializer(), ack))
    }

    /** Applies [command]; returns an error string, or null when it was applied. Main thread. */
    private suspend fun apply(command: Command): String? {
        when (command) {
            is Command.SetLiked -> {
                // A write to the account, which needs the engine's session: a phone the watch
                // woke has none until its service is up.
                if (!engineReady(ENGINE_COMMAND_WAIT_MS)) return AckErrors.LIKE
                val written = catchingNonCancel { app.likedTracks.set(command.uri, command.liked) }
                    .onFailure { Log.w(TAG, "like not written: ${it.message}") }
                if (written.isFailure) return AckErrors.LIKE
                if (likedLookup?.first == command.uri) likedLookup = command.uri to command.liked
                return null
            }
            is Command.AddToPlaylist -> {
                if (!engineReady(ENGINE_COMMAND_WAIT_MS)) return AckErrors.PLAYLIST
                val written = withContext(Dispatchers.IO) {
                    catchingNonCancel { NativeBridge.addToPlaylist(command.playlistUri, command.trackUri) }
                }.onFailure { Log.w(TAG, "not added to the playlist: ${it.message}") }
                return if (written.isSuccess) null else AckErrors.PLAYLIST
            }
            is Command.SetVolume -> {
                ConnectVolume.set(app, command.level, command.deviceId)
                changed(urgent = true)
                return null
            }
            is Command.Transfer -> {
                val here = command.deviceId == PHONE_DEVICE_ID || RemoteConnect.isThisPhone(command.deviceId)
                if (here) {
                    // False when nothing is listening for it (the playback service is not up):
                    // said as a failure rather than acknowledged with nothing happening.
                    if (!RemoteConnect.request(RemoteConnect.TransferRequest.Here)) {
                        wakePlayback()
                        withTimeoutOrNull(LISTENER_WAIT_MS) {
                            while (!RemoteConnect.request(RemoteConnect.TransferRequest.Here)) delay(ENGINE_POLL_MS)
                            true
                        } ?: return AckErrors.TRANSFER
                    }
                    return null
                }
                return if (transferTo(command.deviceId)) null else AckErrors.TRANSFER
            }
            is Command.SetPinned -> {
                if (app.pinnedPlaylists.isPinned(command.uri) != command.pinned) app.pinnedPlaylists.toggle(command.uri)
                return null
            }
            is Command.SleepTimer -> {
                // Read once: a property of a class from another module cannot be smart-cast.
                val minutes = command.minutes
                when {
                    command.cancel -> dev.lelonio.square.playback.SleepTimer.cancel()
                    command.atTrackEnd -> dev.lelonio.square.playback.SleepTimer.atEndOfTrack()
                    minutes != null -> dev.lelonio.square.playback.SleepTimer.inMinutes(minutes)
                    else -> return AckErrors.BAD_TIMER
                }
                return null
            }
            else -> Unit
        }
        val player = ensurePlayer() ?: return AckErrors.PHONE_UNAVAILABLE
        // These read the catalogue through the engine's session, which a phone the watch has just
        // woken does not have yet: waited for, within the watch's patience, then tried anyway.
        if (command is Command.PlayContext || command is Command.AddToQueue || command is Command.StartRadio) {
            engineReady(ENGINE_COMMAND_WAIT_MS)
        }
        when (command) {
            Command.Play -> playWhenLoaded(player)
            Command.Pause -> player.pause()
            Command.TogglePlay -> if (player.isPlaying || player.playWhenReady) player.pause() else playWhenLoaded(player)
            Command.Next -> if (!dev.lelonio.square.playback.PlaybackTransport.skip(player, forward = true)) {
                return if (player.mediaItemCount > 0 && !RemoteConnect.elsewhereActive.value) AckErrors.END_OF_QUEUE else AckErrors.NOTHING_TO_SKIP
            }
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
                    ?: return AckErrors.NOT_IN_QUEUE
                player.seekTo(index, 0)
                playWhenLoaded(player)
            }
            is Command.StartRadio -> {
                val tree = browseTree ?: return AckErrors.PHONE_UNAVAILABLE
                // Answered once the station is playing or known not to be, not before.
                if (!tree.startRadioNow(player)) return AckErrors.RADIO
            }
            is Command.PlayContext -> {
                val tracks = withContext(Dispatchers.IO) { app.spotifyBackend.tracksOf(command.contextUri) }
                if (tracks.isEmpty()) return AckErrors.EMPTY
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
                } ?: return AckErrors.NOT_FOUND
                player.addMediaItem(track.toQueueItem(playNext = true))
            }
            else -> return AckErrors.UNSUPPORTED
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
     * Wakes the playback service — and with it the engine, which it starts on creation — the way
     * the watch's commands do: by binding to it, which a process the listener service woke may do,
     * where starting it may be refused. True when the service is up.
     */
    suspend fun wakePlayback(): Boolean = withContext(Dispatchers.Main.immediate) { ensurePlayer() != null }

    /**
     * The phone signed out: the watch is told, so it signs out too and stops playing as that
     * account. Before, only a backend method nobody called did this, and the watch went on streaming
     * and downloading as the old account until a screen next said hello.
     */
    fun signedOut() {
        scope.launch { catchingNonCancel { auth.onSignedOut() }.onFailure { Log.w(TAG, "watch not told of the sign-out: ${it.message}") } }
    }

    /**
     * Waits, at most [budgetMs], for the engine to have a session: what a write to the account (a
     * like, a playlist) and a read of the library need. Wakes the playback service for it, as the
     * watch's commands do; inside a budget, because the watch is waiting for the answer and an
     * answer that comes after its timeout is no answer at all.
     */
    suspend fun engineReady(budgetMs: Long): Boolean {
        fun connected() = runCatching { NativeBridge.isConnected }.getOrDefault(false)
        if (connected()) return true
        return withTimeoutOrNull(budgetMs) {
            wakePlayback()
            // Asked as well, for a service that is up with its engine stopped; a background start
            // can be refused, and binding above is what counts.
            runCatching { dev.lelonio.square.playback.PlaybackService.connect(app) }
            while (!connected()) delay(ENGINE_POLL_MS)
            true
        } ?: false
    }

    /**
     * The service's player, waking the service if it is not running.
     *
     * Waking is done by connecting a controller, which binds the service and
     * runs its onCreate, which calls [attach]. The controller is let go shortly
     * after: holding the binding would keep the service, and the engine with it,
     * alive after the listener has stopped, which is not the remote's call to make.
     *
     * One controller at a time, whoever asks: the check for an existing one and the build of a new
     * one are a single step under [wakerLock], because building suspends and a second command
     * arriving meanwhile saw "none" and made another.
     */
    private suspend fun ensurePlayer(): Player? {
        player?.let { return it }
        wakerLock.withLock {
            if (waker == null) {
                val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
                waker = catchingNonCancel { MediaController.Builder(app, token).buildAsync().await() }
                    .onFailure { Log.w(TAG, "cannot wake the playback service", it) }
                    .getOrNull()
            }
            wakerRelease?.cancel()
            wakerRelease = scope.launch {
                delay(WAKER_HOLD_MS)
                wakerLock.withLock {
                    waker?.release()
                    waker = null
                }
            }
        }
        return withTimeoutOrNull(ATTACH_WAIT_MS) { attached.await() }
    }

    companion object {
        private const val KEY_LOCAL_ONLY = "phone_media_local_only"
        private const val DEVICE_LISTED_WAIT_MS = 3_000L
        private const val TRANSFER_WAIT_MS = 6_000L
        private const val TAG = "PhoneWearBridge"
        const val PHONE_DEVICE_ID = "phone"
        private const val QUEUE_WAIT_MS = 4_000L
        private const val ATTACH_WAIT_MS = 8_000L
        private const val WAKER_HOLD_MS = 30_000L

        /** How long a command that writes to the account waits for the engine: inside the watch's ack timeout. */
        private const val ENGINE_COMMAND_WAIT_MS = 7_000L
        private const val ENGINE_POLL_MS = 200L
        private const val LIKED_RETRY_MS = 2_000L

        /** How far a position may stray from the extrapolated one and still be "the same". */
        private const val POSITION_DRIFT_MS = 1_500L

        /** How long after a start the notification counts as on its way. */
        private const val NOTIFICATION_GRACE_MS = 3_000L

        /** How long what the system said about the media notification is trusted; see [notificationProbe]. */
        private const val NOTIFICATION_PROBE_TTL_MS = 1_000L

        /** For a playback service just woken to start listening for a transfer. */
        private const val LISTENER_WAIT_MS = 2_000L

        /** What this phone build can do for a watch. Grows with each milestone. */
        val PHONE_FEATURES: Set<String> = setOf(
            dev.pampa.fluidify.wear.protocol.Features.AUDIO_LIGHT,
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
