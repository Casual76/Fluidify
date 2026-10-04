package dev.pampa.fluidify.wear.standalone

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    private val _route = MutableStateFlow(Route.NONE)

    /** Guards [holders], [callback] and [bound]: the engine, the download queue and the system's callbacks all reach them. */
    private val lock = Any()

    /**
     * How many hold the network: the engine while it plays, the download queue
     * while it fetches. Each [acquire] (or [hold]) is paired with one [release];
     * the network goes only when the last one lets go.
     */
    private var holders = 0

    /**
     * The one request for a better network, shared by every holder. Before, each acquire dropped
     * the request in flight and made its own, so the engine and the download queue asking a moment
     * apart cancelled each other's Wi-Fi.
     */
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** The network the process is bound to now; null between a loss and the next one. */
    private val bound = MutableStateFlow<Network?>(null)

    val route: StateFlow<Route> = _route.asStateFlow()

    /** Whether this watch has a mobile radio at all; the onboarding offers it only then. */
    val hasCellular: Boolean
        get() = context.packageManager.hasSystemFeature("android.hardware.telephony")

    /**
     * Asks for the best network there is, waiting up to [timeoutMs] for Wi-Fi to come up.
     * Returns what the music will travel over.
     *
     * The hold is taken first and given back if the wait is cancelled (a worker stopped by
     * WorkManager, a service going): a cancelled acquire used to keep the Wi-Fi request alive
     * until the process died.
     */
    suspend fun acquire(timeoutMs: Long = WIFI_WAIT_MS): Route {
        hold()
        var done = false
        try {
            val now = _route.value
            if (now == Route.WIFI || now == Route.CELLULAR) {
                done = true
                return now
            }
            val granted = withTimeoutOrNull(timeoutMs) { bound.first { it != null } }
            val route = granted?.let(::routeOf) ?: fallback()
            synchronized(lock) { if (holders > 0 && bound.value == granted) _route.value = route }
            Log.i(TAG, "streaming over $route")
            done = true
            return route
        } finally {
            if (!done) release()
        }
    }

    /**
     * Takes a hold and asks for a network without waiting for it: for an engine that starts on
     * whatever there is and moves to Wi-Fi when it comes. Paired with one [release], like [acquire].
     */
    fun hold() {
        synchronized(lock) {
            holders++
            if (callback == null) register()
            if (_route.value == Route.NONE) _route.value = bound.value?.let(::routeOf) ?: fallback()
        }
    }

    /** Lets go of the network; the last holder returns the process to the system's default. */
    fun release() {
        synchronized(lock) {
            holders = (holders - 1).coerceAtLeast(0)
            if (holders == 0) drop()
        }
    }

    /** Under [lock]. */
    private fun drop() {
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null
        bound.value = null
        connectivity.bindProcessToNetwork(null)
        _route.value = Route.NONE
    }

    /** Under [lock]. */
    private fun register() {
        val transports = buildList {
            add(NetworkCapabilities.TRANSPORT_WIFI)
            if (prefs.allowCellular && hasCellular) add(NetworkCapabilities.TRANSPORT_CELLULAR)
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .apply { transports.forEach { addTransportType(it) } }
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                synchronized(lock) {
                    if (callback !== this) return
                    if (!connectivity.bindProcessToNetwork(network)) return
                    bound.value = network
                    // Also after a loss: the Wi-Fi coming back is the route again, not the proxy.
                    _route.value = routeOf(network)
                }
            }

            override fun onLost(network: Network) {
                synchronized(lock) {
                    if (callback !== this || bound.value != network) return
                    // The radio went away under the music: back to the proxy, which the engine's
                    // reconnect will find by itself.
                    connectivity.bindProcessToNetwork(null)
                    bound.value = null
                    _route.value = fallback()
                }
            }
        }
        callback = cb
        runCatching { connectivity.requestNetwork(request, cb) }.onFailure {
            Log.w(TAG, "network request refused: ${it.message}")
            callback = null
        }
    }

    private fun routeOf(network: Network): Route =
        if (connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) Route.WIFI else Route.CELLULAR

    private fun fallback(): Route = if (proxyAvailable()) Route.PROXY else Route.NONE

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
