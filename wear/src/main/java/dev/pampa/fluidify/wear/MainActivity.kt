package dev.pampa.fluidify.wear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import dev.antigravity.fluidengine.wear.ambient.LocalFluidWearAmbient
import dev.antigravity.fluidengine.wear.ambient.rememberFluidAmbientState
import dev.antigravity.fluidengine.wear.theme.FluidWearTheme
import dev.pampa.fluidify.wear.system.Bridging
import dev.pampa.fluidify.wear.system.PlayerIntents
import dev.pampa.fluidify.wear.ui.WatchRoot
import dev.pampa.fluidify.wear.ui.theme.FluidifyWearBrand
import kotlinx.coroutines.flow.MutableSharedFlow

class MainActivity : ComponentActivity() {

    private val app get() = application as WearApp

    /** Asks the screen to go back to the player: from the watch face icon, the tile, the complication. */
    private val showPlayer = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // The icon on the watch face is a notification underneath: draw it now if the
            // phone is already playing.
            if (granted) app.surfaces.onState(app.state.current.value)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Here rather than in the Application: the listener service wakes the
        // process for every message from the phone, and none of those needs to
        // open WorkManager's database. The periodic job survives reboots and
        // updates on its own, so the first launch is enough; KEEP makes the
        // later ones free.
        dev.pampa.fluidify.wear.update.WatchSelfUpdateWorker.schedule(this)
        dev.pampa.fluidify.wear.downloads.NightlySyncWorker.schedule(this)
        Bridging.apply(this, app.surfacePrefs.phoneNotifications)
        askForNotificationsOnce()
        setContent {
            val ambient = rememberFluidAmbientState(this)
            CompositionLocalProvider(
                LocalFluidWearAmbient provides ambient,
                dev.pampa.fluidify.wear.library.LocalThumbnails provides app.thumbnails,
            ) {
                FluidWearTheme(brand = FluidifyWearBrand) {
                    WatchRoot(app, showPlayer = showPlayer)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (PlayerIntents.wantsPlayer(intent)) showPlayer.tryEmit(Unit)
    }

    override fun onStart() {
        super.onStart()
        // Says hello and catches up on what the Data Layer already holds. Nothing
        // is polled while the screen is up: the phone pushes changes.
        app.link.connect()
    }

    /**
     * Notifications are what the watch face icon is made of. Asked once, the first
     * time the app opens; after that the switch in Altro says why it is off.
     */
    private fun askForNotificationsOnce() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return
        val prefs = getSharedPreferences("first_run", MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED_NOTIFICATIONS, false)) return
        prefs.edit { putBoolean(KEY_ASKED_NOTIFICATIONS, true) }
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private companion object {
        const val KEY_ASKED_NOTIFICATIONS = "asked_notifications"
    }
}
