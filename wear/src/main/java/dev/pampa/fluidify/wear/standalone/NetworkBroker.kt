package dev.pampa.fluidify.wear.standalone

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
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
 * Observes available networks without keeping a radio on. Bluetooth is preferred.
 *
 * The one exception to "no radio kept on" is a stream that is already running over the watch's own
 * Wi-Fi: the Wi-Fi was asked for by a request, and a network nobody asks for is one the watch lets
 * go of, so while something holds this broker and the route is Wi-Fi the request stays (see
 * [keepsWifiRequest]) and a [WifiManager.WifiLock] is held next to it. Both go the moment the
 * route is anything else (Bluetooth is preferred whenever it is there) or the last hold is given back.
 */
class NetworkBroker(context: Context, private val prefs: StandalonePrefs) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val wifi: WifiManager? = context.applicationContext.getSystemService(WifiManager::class.java)
    private var wifiLock: WifiManager.WifiLock? = null
    private val lock = Any()
    private val networks = mutableMapOf<Network, Route>()
    private var holders = 0
    private var waiters = 0
    private var wifiHolders = 0
    private var observer: ConnectivityManager.NetworkCallback? = null
    private var request: ConnectivityManager.NetworkCallback? = null
    private val _route = MutableStateFlow(Route.NONE)
    val route: StateFlow<Route> = _route.asStateFlow()
    val hasCellular = context.packageManager.hasSystemFeature("android.hardware.telephony")

    /** No radio request for ordinary playback with a route. A download fallback may ask briefly for Wi-Fi. */
    suspend fun acquire(timeoutMs: Long = WIFI_WAIT_MS, preferWifi: Boolean = false): Route {
        hold(preferWifi)
        var done = false
        try {
            synchronized(lock) {
                waiters++
                if (_route.value == Route.NONE || preferWifi && _route.value != Route.WIFI) requestWifiTemporarily(timeoutMs)
            }
            val got = if (_route.value != Route.NONE && (!preferWifi || _route.value == Route.WIFI)) _route.value
                else withTimeoutOrNull(timeoutMs) { route.first { it != Route.NONE && (!preferWifi || it == Route.WIFI) } } ?: _route.value
            done = true
            return got
        } finally {
            synchronized(lock) {
                waiters--
                if (waiters == 0 && (wifiHolders == 0 || _route.value != Route.WIFI) && !keepsWifiRequest()) cancelRequest()
            }
            if (!done) release(preferWifi)
        }
    }

    /** Offline playback and a cached credential never request a radio. */
    fun hold(preferWifi: Boolean = false) = synchronized(lock) {
        holders++
        if (preferWifi) wifiHolders++
        if (observer != null) { select(); return@synchronized }
        connectivity.allNetworks.forEach { network ->
            routeOf(connectivity.getNetworkCapabilities(network))?.let { networks[network] = it }
        }
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val capabilities = connectivity.getNetworkCapabilities(network) ?: return
                onCapabilitiesChanged(network, capabilities)
            }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                synchronized(lock) {
                    if (observer !== this) return
                    routeOf(capabilities)?.let { networks[network] = it } ?: networks.remove(network)
                    select()
                }
            }
            override fun onLost(network: Network) {
                synchronized(lock) {
                    if (observer !== this) return
                    networks.remove(network)
                    select()
                }
            }
        }
        observer = cb
        runCatching { connectivity.registerNetworkCallback(NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), cb) }
            .onFailure { observer = null; Log.i(TAG, "network observation unavailable") }
        select()
    }

    fun release(preferWifi: Boolean = false) = synchronized(lock) {
        holders = (holders - 1).coerceAtLeast(0)
        if (preferWifi) wifiHolders = (wifiHolders - 1).coerceAtLeast(0)
        if (wifiHolders == 0 && waiters == 0 && !keepsWifiRequest()) cancelRequest()
        if (holders != 0) { select(); return@synchronized }
        wifiHolders = 0
        cancelRequest()
        observer?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        observer = null
        networks.clear()
        connectivity.bindProcessToNetwork(null)
        _route.value = Route.NONE
        settleWifiLock()
    }

    private fun routeOf(capabilities: NetworkCapabilities?): Route? {
        if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true) return null
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> Route.PROXY
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Route.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && prefs.allowCellular && hasCellular -> Route.CELLULAR
            else -> null
        }
    }

    /** Default playback uses Bluetooth; a finite download lease may select Wi-Fi until it completes. */
    private fun select() {
        val chosen = networks.entries.minByOrNull { rank(it.value) }
        connectivity.bindProcessToNetwork(chosen?.key?.takeUnless { it == connectivity.activeNetwork })
        _route.value = chosen?.value ?: Route.NONE
        if (_route.value != Route.NONE && wifiHolders == 0 && !keepsWifiRequest()) cancelRequest()
        settleWifiLock()
    }

    /** How much a route is wanted right now: the lower, the better. */
    private fun rank(route: Route): Int = when (route) {
        Route.PROXY -> if (wifiHolders > 0) 1 else 0
        Route.WIFI -> if (wifiHolders > 0) 0 else 1
        Route.CELLULAR -> 2
        Route.NONE -> 3
    }

    /**
     * Whether the Wi-Fi we asked for is still needed: it carries the route, and something (the
     * engine's stream, a download) still holds the broker. Cancelled as soon as a route existed,
     * it let the system drop the very Wi-Fi the music was coming over. A Bluetooth route is not
     * this case: the Bluetooth link is always there, and nothing needs the Wi-Fi radio on for it.
     */
    private fun keepsWifiRequest(): Boolean = _route.value == Route.WIFI && holders > 0

    /**
     * Holds a Wi-Fi lock for as long as the route is Wi-Fi and something holds the broker, so the
     * radio is not put to sleep between the stream's bursts. Released with the hold, which for the
     * engine is when playback has been paused long enough for its service to stop (see
     * WatchPlaybackService.IDLE_MS). Called with [lock] held.
     *
     * The low-latency mode is the only one that does anything on this API level (the others are
     * non-functional since Android 14), and the system applies it while the app is in the
     * foreground with the screen on; with the screen off the kept request above is what holds the
     * Wi-Fi up. Neither needs a permission beyond WAKE_LOCK, which the app declares.
     */
    private fun settleWifiLock() {
        val wanted = holders > 0 && _route.value == Route.WIFI
        if (wanted) {
            val held = wifiLock ?: wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, TAG)?.also {
                it.setReferenceCounted(false)
                wifiLock = it
            }
            runCatching { if (held != null && !held.isHeld) held.acquire() }
                .onFailure { Log.i(TAG, "Wi-Fi lock not taken: ${it.message}") }
        } else {
            wifiLock?.let { runCatching { if (it.isHeld) it.release() } }
        }
    }

    private fun requestWifiTemporarily(timeoutMs: Long) {
        if (request != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                synchronized(lock) {
                    if (request !== this) return
                    routeOf(connectivity.getNetworkCapabilities(network))?.let { networks[network] = it }
                    select()
                }
            }
            override fun onUnavailable() { synchronized(lock) { if (request === this) request = null } }
        }
        request = cb
        runCatching { connectivity.requestNetwork(NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb,
            timeoutMs.coerceIn(1, WIFI_WAIT_MS).toInt()) }
            .onFailure { request = null; Log.i(TAG, "temporary Wi-Fi request unavailable") }
    }

    private fun cancelRequest() {
        request?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        request = null
    }

    private companion object { const val TAG = "NetworkBroker"; const val WIFI_WAIT_MS = 12_000L }
}
