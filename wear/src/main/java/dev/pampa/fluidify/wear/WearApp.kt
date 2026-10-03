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

/**
 * Fluidify on the watch: the parts that outlive a screen.
 *
 * Plain lazies, as on the phone. The listener service touches these with no
 * activity alive, so nothing here may assume a screen.
 */
class WearApp : Application(), dev.lelonio.square.playback.CoreHost {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val surfacePrefs: dev.pampa.fluidify.wear.system.SurfacePrefs by lazy {
        dev.pampa.fluidify.wear.system.SurfacePrefs(this)
    }

    /** The icon on the watch face, the tile and the complication, kept in step with the phone. */
    val surfaces: dev.pampa.fluidify.wear.system.SystemSurfaces by lazy {
        dev.pampa.fluidify.wear.system.SystemSurfaces(this, surfacePrefs)
    }

    val state: WatchState by lazy { WatchState(this).also { it.onAccepted = surfaces::onState } }
    val art: ArtStore by lazy { ArtStore(this).also { it.onStored = surfaces::onCoverStored } }
    val link: PhoneLink by lazy { PhoneLink(this, scope, state, art) }

    /** Installs builds the phone sends; see [WatchUpdater]. */
    val updater: dev.pampa.fluidify.wear.update.WatchUpdater by lazy {
        dev.pampa.fluidify.wear.update.WatchUpdater(this)
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

    /** The watch's own player, behind the same interface as the phone. */
    val local: dev.pampa.fluidify.wear.standalone.LocalControls by lazy {
        dev.pampa.fluidify.wear.standalone.LocalControls(
            this,
            scope,
            art,
            offlineTracks = { uri -> downloads.store.offlineTracks(uri) },
            prefs = standalone.prefs,
        )
    }

    /** Whichever of the two is in front. */
    val playback: dev.pampa.fluidify.wear.playback.ActivePlayback by lazy {
        dev.pampa.fluidify.wear.playback.ActivePlayback(scope, remote, local, { standalone }) { uri -> downloads.store.isKept(uri) }
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

    /** The glass meter's switch (debug and dev builds). */
    val glassMeter: dev.pampa.fluidify.wear.ui.debug.GlassMeterPrefs by lazy {
        dev.pampa.fluidify.wear.ui.debug.GlassMeterPrefs(this)
    }

    /** The bezel as a volume knob. */
    val volume: dev.pampa.fluidify.wear.playback.VolumeControl by lazy {
        dev.pampa.fluidify.wear.playback.VolumeControl(scope, controls)
    }
}
