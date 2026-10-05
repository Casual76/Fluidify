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
import dev.pampa.fluidify.wear.standalone.HeadphonesPrompt
import dev.pampa.fluidify.wear.system.FirstRunPrefs
import dev.pampa.fluidify.wear.system.PlayerIntents
import dev.pampa.fluidify.wear.ui.WatchRoot
import dev.pampa.fluidify.wear.ui.theme.FluidifyWearBrand
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val app get() = application as WearApp

    /**
     * What the screen is asked to show: the player, from the watch face icon, the tile or the
     * complication, or the question the tile's heart asks. Held until the screen has handled it,
     * not dropped when no one is listening yet: an activity Android had destroyed is recreated by
     * the very tap that carries the request, and it arrives before the screen exists.
     */
    private val requests = MutableStateFlow<PlayerIntents.Request?>(null)

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
        askForNotificationsOnce()
        dev.pampa.fluidify.wear.ui.debug.FrameLog.attach(this)
        // Opened by a tap that asks for something (a fresh start, or recreated after Android let it
        // go): the intent is here, not in onNewIntent. Not on a recreation of the same screen.
        if (savedInstanceState == null) requests.value = requestFrom(intent)
        setContent {
            val ambient = rememberFluidAmbientState(this)
            CompositionLocalProvider(
                LocalFluidWearAmbient provides ambient,
                dev.pampa.fluidify.wear.library.LocalThumbnails provides app.thumbnails,
            ) {
                FluidWearTheme(brand = FluidifyWearBrand) {
                    WatchRoot(app, requests = requests, onRequestHandled = { requests.value = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestFrom(intent)?.let { requests.value = it }
    }

    /**
     * What the screen is to show for [intent]. The headphones prompt's button also asks for the
     * music to move, done here, where the app is in front, rather than from a receiver — and only
     * when the token it carries is the prompt's own: this activity is open to every app, and an
     * intent with the right action must not be enough to start playback. Without the token, or
     * with one already spent, it is just a request for the player.
     */
    private fun requestFrom(intent: Intent?): PlayerIntents.Request? {
        val request = PlayerIntents.requestOf(intent)
        if (request != PlayerIntents.Request.LISTEN_HERE) return request
        if (HeadphonesPrompt.claim(this, intent?.getStringExtra(PlayerIntents.EXTRA_LISTEN_TOKEN))) {
            HeadphonesPrompt.listen(app)
        }
        return PlayerIntents.Request.PLAYER
    }

    override fun onStart() {
        super.onStart()
        app.uiVisible = true
        dev.pampa.fluidify.wear.system.BackgroundErrors.clear(this)
        // Says hello and catches up on what the Data Layer already holds. Nothing
        // is polled while the screen is up: the phone pushes changes.
        app.link.connect()
        app.link.watchReachability()
    }

    override fun onStop() {
        app.uiVisible = false
        app.link.unwatchReachability()
        super.onStop()
    }

    /**
     * Notifications are what the watch face icon is made of. Asked once, the first
     * time the app opens; after that the switch in Altro says why it is off.
     */
    private fun askForNotificationsOnce() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return
        val prefs = getSharedPreferences(FirstRunPrefs.NAME, MODE_PRIVATE)
        if (prefs.getBoolean(FirstRunPrefs.KEY_ASKED_NOTIFICATIONS, false)) return
        prefs.edit { putBoolean(FirstRunPrefs.KEY_ASKED_NOTIFICATIONS, true) }
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
