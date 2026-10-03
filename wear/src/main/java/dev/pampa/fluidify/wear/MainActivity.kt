package dev.pampa.fluidify.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import dev.antigravity.fluidengine.wear.ambient.LocalFluidWearAmbient
import dev.antigravity.fluidengine.wear.ambient.rememberFluidAmbientState
import dev.antigravity.fluidengine.wear.theme.FluidWearTheme
import dev.pampa.fluidify.wear.ui.WatchRoot
import dev.pampa.fluidify.wear.ui.theme.FluidifyWearBrand

class MainActivity : ComponentActivity() {

    private val app get() = application as WearApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Here rather than in the Application: the listener service wakes the
        // process for every message from the phone, and none of those needs to
        // open WorkManager's database. The periodic job survives reboots and
        // updates on its own, so the first launch is enough; KEEP makes the
        // later ones free.
        dev.pampa.fluidify.wear.update.WatchSelfUpdateWorker.schedule(this)
        setContent {
            val ambient = rememberFluidAmbientState(this)
            CompositionLocalProvider(LocalFluidWearAmbient provides ambient) {
                FluidWearTheme(brand = FluidifyWearBrand) {
                    WatchRoot(app)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Says hello and catches up on what the Data Layer already holds. Nothing
        // is polled while the screen is up: the phone pushes changes.
        app.link.connect()
    }
}
