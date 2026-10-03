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
class WearApp : Application() {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val state: WatchState by lazy { WatchState(this) }
    val art: ArtStore by lazy { ArtStore(this) }
    val link: PhoneLink by lazy { PhoneLink(this, scope, state, art) }

    /** Installs builds the phone sends; see [WatchUpdater]. */
    val updater: dev.pampa.fluidify.wear.update.WatchUpdater by lazy {
        dev.pampa.fluidify.wear.update.WatchUpdater(this)
    }

    /** What the controls talk to. The phone, for now. */
    val controls: PlaybackControls by lazy { PhoneRemote(scope, state, link) }

    override fun onCreate() {
        super.onCreate()
        dev.pampa.fluidify.wear.update.WatchSelfUpdateWorker.schedule(this)
    }
}
