package dev.pampa.fluidify.wear.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.pampa.fluidify.wear.WearApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** PackageInstaller wakes this receiver even if the screen and app process were stopped. */
class WatchInstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as WearApp
        app.scope.launch(Dispatchers.IO) {
            try { app.updater.onInstallResult(intent) } finally { pending.finish() }
        }
    }
}
