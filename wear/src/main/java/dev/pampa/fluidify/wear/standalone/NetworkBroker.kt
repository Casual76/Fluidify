package dev.pampa.fluidify.wear.standalone

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/** How the watch is reaching the internet for the music. */
enum class Route {
    /** Its own Wi-Fi, asked for and granted. */
    WIFI,

    /** Its own mobile data, when it has some and the listener allowed it. */
    CELLULAR,

    /** Through the phone over Bluetooth: always there while paired, slow, and the phone's data. */
    PROXY,

    /** Nothing at all. */
    NONE,
}

/**
 * Gets the watch a network fit for streaming and lets it go when done.
 *
 * Wear OS hands an app the Bluetooth link to the phone unless it asks for more.
 * That link carries a 96 kbps stream, barely, and a download not at all, so the
 * broker asks for Wi-Fi first — and waits for it, because a Galaxy Watch keeps
 * its Wi-Fi off until something wants it and needs a few seconds to join. Mobile
 * data is asked for only when the watch has it and the listener said yes (off
 * by default, see [StandalonePrefs.allowCellular]). Whatever is granted is bound
 * to the whole process, so the Rust engine's own sockets and DNS go the same
 * way as Kotlin's. When nothing better comes, the proxy is what is left.
 *
 * Released after the music stops, never held: a requested Wi-Fi keeps the radio
 * on, and on a watch the radio is the battery.
 */
class NetworkBroker(private val context: Context, private val prefs: StandalonePrefs) {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null
    private val _route = MutableStateFlow(Route.NONE)

    val route: StateFlow<Route> = _route.asStateFlow()

    /** Whether this watch has a mobile radio at all; the onboarding offers it only then. */
    val hasCellular: Boolean
        get() = context.packageManager.hasSystemFeature("android.hardware.telephony")

    /**
     * Asks for the best network there is, waiting up to [timeoutMs] for Wi-Fi to come up.
     * Returns what the music will travel over.
     */
    suspend fun acquire(timeoutMs: Long = WIFI_WAIT_MS): Route {
        if (_route.value == Route.WIFI || _route.value == Route.CELLULAR) return _route.value
        val transports = buildList {
            add(NetworkCapabilities.TRANSPORT_WIFI)
            if (prefs.allowCellular && hasCellular) add(NetworkCapabilities.TRANSPORT_CELLULAR)
        }
        val granted = request(transports, timeoutMs)
        val route = when {
            granted == null -> if (proxyAvailable()) Route.PROXY else Route.NONE
            connectivity.getNetworkCapabilities(granted)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> Route.WIFI
            else -> Route.CELLULAR
        }
        _route.value = route
        Log.i(TAG, "streaming over $route")
        return route
    }

    /** Lets the requested network go and returns the process to the system's default. */
    fun release() {
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null
        connectivity.bindProcessToNetwork(null)
        _route.value = Route.NONE
    }

    private suspend fun request(transports: List<Int>, timeoutMs: Long): Network? {
        release()
        val available = CompletableDeferred<Network>()
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .apply { transports.forEach { addTransportType(it) } }
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (connectivity.bindProcessToNetwork(network)) available.complete(network)
            }

            override fun onLost(network: Network) {
                // The radio went away under the music: back to the proxy, which the engine's
                // reconnect will find by itself.
                connectivity.bindProcessToNetwork(null)
                _route.value = if (proxyAvailable()) Route.PROXY else Route.NONE
            }
        }
        callback = cb
        runCatching { connectivity.requestNetwork(request, cb) }.onFailure {
            Log.w(TAG, "network request refused: ${it.message}")
            callback = null
            return null
        }
        return withTimeoutOrNull(timeoutMs) { available.await() }
    }

    /** The system's default network, which on a paired watch is the Bluetooth proxy. */
    private fun proxyAvailable(): Boolean {
        val active = connectivity.activeNetwork ?: return false
        return connectivity.getNetworkCapabilities(active)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private companion object {
        const val TAG = "NetworkBroker"

        /** Long enough for a Galaxy Watch to switch its Wi-Fi on and join; short enough to give up on. */
        const val WIFI_WAIT_MS = 12_000L
    }
}
