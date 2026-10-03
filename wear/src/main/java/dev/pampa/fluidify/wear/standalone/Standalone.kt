package dev.pampa.fluidify.wear.standalone

import android.content.Context
import dev.pampa.fluidify.wear.link.PhoneLink

/**
 * Everything the watch needs to play on its own, made when first asked for.
 *
 * None of it is touched while the watch only remotes the phone: the native
 * library is not even loaded until [engine] starts it.
 */
class Standalone(context: Context, link: PhoneLink) {
    val prefs = StandalonePrefs(context)
    val network = NetworkBroker(context, prefs)
    val router = OutputRouter(context, prefs)
    val auth = WatchAuth(context, link, prefs)
    val engine = WatchEngine(context, auth, network, prefs).apply {
        downloadRoot = java.io.File(context.filesDir, "downloads")
    }
}
