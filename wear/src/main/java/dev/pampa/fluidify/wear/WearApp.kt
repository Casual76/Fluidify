package dev.pampa.fluidify.wear

import android.app.Application
import dev.pampa.fluidify.wear.link.ArtStore
import dev.pampa.fluidify.wear.link.PhoneLink
import dev.pampa.fluidify.wear.link.WatchState
import dev.pampa.fluidify.wear.playback.PhoneRemote
import dev.pampa.fluidify.wear.playback.PlaybackControls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fluidify on the watch: the parts that outlive a screen.
 *
 * Plain lazies, as on the phone. The listener service touches these with no
 * activity alive, so nothing here may assume a screen.
 */
class WearApp : Application(), dev.lelonio.square.playback.CoreHost {
    val tileTaps by lazy {
        val prefs = getSharedPreferences("tile_clicks", MODE_PRIVATE)
        dev.pampa.fluidify.wear.system.TileTapHistory(prefs.getString("handled", "").orEmpty().split('\n').filter { it.isNotEmpty() }) {
            prefs.edit().putString("handled", it.joinToString("\n")).apply()
        }
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Whether the screen is in front. Set by the activity (see MainActivity.onStart/onStop); what
     * decides if a failure is shown on screen or, with nobody looking, in a notification.
     */
    @Volatile var uiVisible = false
    val audioLightPreferences by lazy { dev.lelonio.square.playback.AudioLightPreferences(this) }
    val audioLight by lazy { dev.pampa.fluidify.wear.system.WatchAudioLight(this) }

    /**
     * Whether the audio light may run (screen on, no power saving, animations on). One for the app:
     * each ring and the activity that feeds them used to make their own, each with a receiver and a
     * settings observer of its own to keep. Lives as long as the process, which is what its two
     * registrations cost.
     */
    val audioLightPolicy by lazy { dev.lelonio.square.playback.AudioLightPolicy(this) }

    override fun onCreate() {
        super.onCreate()
        // The first start of a version that was just installed says so, once (see WhatsNew).
        runCatching { dev.pampa.fluidify.wear.update.WhatsNew.onStart(this) }
        // Applied whenever the process starts, not only when the app is opened: the listener
        // service wakes it far more often, and a watch app never opened kept Wear's default.
        runCatching { dev.pampa.fluidify.wear.system.Bridging.apply(this, surfacePrefs.phoneNotifications) }
    }

    /**
     * The account the watch was signed in as is gone (the phone signed out, or another account
     * signed in): playback stops and the engine goes before the credential is deleted (see
     * [dev.pampa.fluidify.wear.playback.ActivePlayback.signedOut]), then what was kept for that
     * account — the library's pages, the lists' covers, the player's covers — is dropped, so the
     * next account never sees the last one's. Downloads stay; they play again only for the same
     * account.
     */
    suspend fun accountGone() {
        playback.signedOut()
        withContext(Dispatchers.IO) {
            runCatching { library.clear() }
            runCatching { thumbnails.clear() }
            runCatching { art.clear() }
        }
    }

    val surfacePrefs: dev.pampa.fluidify.wear.system.SurfacePrefs by lazy {
        dev.pampa.fluidify.wear.system.SurfacePrefs(this)
    }

    /** The icon on the watch face, the tile and the complication, kept in step with the phone. */
    val surfaces: dev.pampa.fluidify.wear.system.SystemSurfaces by lazy {
        dev.pampa.fluidify.wear.system.SystemSurfaces(this, surfacePrefs).also {
            it.mirror = dev.pampa.fluidify.wear.system.MirrorNowPlaying(this)
        }
    }

    val state: WatchState by lazy { WatchState(this).also { it.onAccepted = surfaces::onState } }
    val art: ArtStore by lazy { ArtStore(this).also { it.onStored = surfaces::onCoverStored } }
    val link: PhoneLink by lazy { PhoneLink(this, scope, state, art) }

    /**
     * Installs builds the phone sends; see [WatchUpdater].
     *
     * Told whether the watch's own player is playing, so that it never replaces the app under a
     * song. Asked of the player only if one has been built in this process: a player that was
     * never made is not playing, and asking would be what makes it.
     */
    val updater: dev.pampa.fluidify.wear.update.WatchUpdater by lazy {
        dev.pampa.fluidify.wear.update.WatchUpdater(this, playingLocally = {
            localControls.takeIf { it.isInitialized() }?.value?.nowPlaying?.value?.snapshot
                ?.let { it.track != null && (it.isPlaying || it.playWhenReady) } == true
        })
    }

    /** The phone as a player. */
    val remote: PhoneRemote by lazy { PhoneRemote(scope, state, link) }

    /** Playing on the watch itself: engine, network, outputs, sign-in. Built only when used. */
    val standalone: dev.pampa.fluidify.wear.standalone.Standalone by lazy {
        dev.pampa.fluidify.wear.standalone.Standalone(this, link)
    }

    /** What the watch keeps for offline listening; see [dev.pampa.fluidify.wear.downloads.WatchDownloads]. */
    val downloads: dev.pampa.fluidify.wear.downloads.WatchDownloads by lazy {
        dev.pampa.fluidify.wear.downloads.WatchDownloads(this)
    }

    /**
     * The watch's own player, behind the same interface as the phone.
     *
     * Held as the lazy itself (and [local] reads through it) so that [updater] can ask whether it
     * exists yet without making it.
     */
    private val localControls: Lazy<dev.pampa.fluidify.wear.standalone.LocalControls> = lazy {
        dev.pampa.fluidify.wear.standalone.LocalControls(
            this,
            scope,
            art,
            offlineTracks = { uri -> downloads.store.offlineTracks(uri) },
            fullyKept = { uri -> downloads.store.isComplete(uri) },
            prefs = standalone.prefs,
            likedLookup = { uri -> library.isLiked(uri) },
        )
    }

    val local: dev.pampa.fluidify.wear.standalone.LocalControls by localControls

    /** Whichever of the two is in front. */
    val playback: dev.pampa.fluidify.wear.playback.ActivePlayback by lazy {
        dev.pampa.fluidify.wear.playback.ActivePlayback(scope, remote, { local }, { standalone }) { uri -> downloads.store.isKept(uri) }
            .also { active ->
                // The failures nobody is looking at (a handoff from the phone, the headphones'
                // prompt) still say so. The one collector of its kind, started with the controls
                // that emit them; the screen has its own toast.
                dev.pampa.fluidify.wear.system.BackgroundErrors(this, scope, active.errors, { uiVisible }).start()
                // The watch's own session posts its own notification: the watch face follows.
                scope.launch {
                    active.mode.collect { surfaces.onModeChanged(it == dev.pampa.fluidify.wear.playback.PlaybackMode.WATCH) }
                }
                scope.launch { active.nowPlaying.collect { surfaces.onWatchState(it.snapshot) } }
            }
    }

    /** What the controls talk to: see [playback]. */
    val controls: PlaybackControls get() = playback

    /**
     * The watch has no quality setting of its own yet: the engine starts at what
     * the network can carry ([dev.pampa.fluidify.wear.standalone.WatchEngine])
     * and follows the connection from there.
     */
    override val qualityIsAutomatic: Boolean get() = true

    /** The queue screen's source: the phone's queue, or the watch's own when it is playing. */
    suspend fun queue(): Result<dev.pampa.fluidify.wear.protocol.QueueWindow> {
        if (playback.mode.value != dev.pampa.fluidify.wear.playback.PlaybackMode.WATCH) return library.queue()
        val items = local.queueItems()
        val current = local.currentIndex
        val from = (current - 3).coerceAtLeast(0)
        val to = (current + 60).coerceAtMost(items.lastIndex)
        val window = if (items.isEmpty()) emptyList() else (from..to).map { index ->
            val item = items[index]
            val artUrl = item.mediaMetadata.artworkUri?.toString()
            dev.pampa.fluidify.wear.protocol.QueueEntry(
                index = index,
                uri = item.mediaId,
                title = item.mediaMetadata.title?.toString().orEmpty(),
                artist = item.mediaMetadata.artist?.toString().orEmpty(),
                artKey = dev.pampa.fluidify.wear.protocol.artKeyOf(artUrl),
                artUrl = artUrl?.takeIf { it.startsWith("https://") },
            )
        }
        return Result.success(dev.pampa.fluidify.wear.protocol.QueueWindow(currentIndex = current, total = items.size, items = window))
    }

    /** The phone's library and state, with a copy on disk for instant screens. */
    val library: dev.pampa.fluidify.wear.library.PhoneLibrary by lazy {
        dev.pampa.fluidify.wear.library.PhoneLibrary(this, link)
    }

    /** The covers of the watch's lists, from the phone; see [dev.pampa.fluidify.wear.library.Thumbnails]. */
    val thumbnails: dev.pampa.fluidify.wear.library.Thumbnails by lazy {
        dev.pampa.fluidify.wear.library.Thumbnails(this, link, scope)
    }

    /** The glass meter's switch (debug and dev builds). */
    val glassMeter: dev.pampa.fluidify.wear.ui.debug.GlassMeterPrefs by lazy {
        dev.pampa.fluidify.wear.ui.debug.GlassMeterPrefs(this)
    }

    /** The bezel as a volume knob. */
    val volume: dev.pampa.fluidify.wear.playback.VolumeControl by lazy {
        dev.pampa.fluidify.wear.playback.VolumeControl(scope, controls)
    }
}
