package dev.pampa.fluidify.wear.system

import android.content.Context
import android.util.Log
import androidx.wear.phone.interactions.notifications.BridgingConfig
import androidx.wear.phone.interactions.notifications.BridgingManager

/**
 * Whether the phone's Fluidify notifications also show up on the watch.
 *
 * Wear mirrors a phone app's notifications unless that app's watch half says
 * otherwise. With the watch app installed, the mirrored media notification can
 * sit next to the watch's own icon and tile: two ways to reach the same player.
 * The switch lives in Altro; the system remembers the choice, so it is applied
 * when it changes and once at launch, never per message.
 */
object Bridging {

    fun apply(context: Context, enabled: Boolean) {
        runCatching {
            BridgingManager.fromContext(context).setConfig(BridgingConfig.Builder(context, enabled).build())
        }.onFailure { Log.i("Bridging", "bridging config not applied: ${it.message}") }
    }
}
