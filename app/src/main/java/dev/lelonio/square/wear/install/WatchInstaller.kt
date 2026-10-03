package dev.lelonio.square.wear.install

import android.content.Context
import android.util.Log
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetAddress

/**
 * Installs the watch app from the phone, over Wear OS's wireless debugging.
 *
 * The steps a person would take at a computer with `adb`, done here:
 * find the watch on the network (or take the address they type), pair with
 * the six-digit code the watch shows, connect, stream the APK into
 * `pm install` (with this app as the installer of record, so later updates
 * install without a prompt), let the app install its own updates
 * (`appops REQUEST_INSTALL_PACKAGES`, which Wear OS has no screen for), and
 * open it.
 *
 * Nothing here can harm the watch: every step either works or stops with a
 * reason, and the PC script (tools/install-wear.ps1) remains for when it does
 * not.
 */
class WatchInstaller(private val context: Context) {

    sealed interface Step {
        data object Idle : Step
        data object Pairing : Step
        data object Connecting : Step
        data class Downloading(val progress: Float?) : Step
        data object Installing : Step
        data object Finishing : Step
        data object Done : Step
        data class Failed(val reason: Reason, val detail: String = "") : Step
    }

    enum class Reason { WRONG_CODE, NOT_FOUND, REFUSED, NO_APK, NO_RELEASE, INSTALL_FAILED }

    /** What the watch announced on the network: where to pair and where to connect. */
    data class Found(val host: String? = null, val pairingPort: Int? = null, val connectPort: Int? = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _step = MutableStateFlow<Step>(Step.Idle)
    val step: StateFlow<Step> = _step.asStateFlow()

    private val _found = MutableStateFlow(Found())
    val found: StateFlow<Found> = _found.asStateFlow()

    private var pairingMdns: AdbMdns? = null
    private var connectMdns: AdbMdns? = null
    private var multicast: WifiManager.MulticastLock? = null
    private val nsd by lazy { context.getSystemService(NsdManager::class.java) }
    private val nsdListeners = mutableListOf<NsdManager.DiscoveryListener>()

    /**
     * Listens for the watch's pairing and connection services while the wizard is open.
     *
     * Twice over, and with the Wi-Fi's multicast lock held. Without the lock many phones —
     * Samsung's among them — drop the multicast packets mDNS answers arrive in, and the watch is
     * invisible to the phone while a computer on the same network sees it: exactly what the tests
     * found. Two listeners because they fail differently: libadb's own resolver, and the system's
     * NsdManager.
     */
    fun startDiscovery() {
        stopDiscovery()
        multicast = runCatching {
            context.applicationContext.getSystemService(WifiManager::class.java)
                ?.createMulticastLock(MULTICAST_TAG)
                ?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }.getOrNull()
        pairingMdns = AdbMdns(context, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { host: InetAddress?, port: Int ->
            if (host != null && port > 0) onPairing(host.hostAddress, port)
        }.also { it.start() }
        connectMdns = AdbMdns(context, AdbMdns.SERVICE_TYPE_TLS_CONNECT) { host: InetAddress?, port: Int ->
            if (host != null && port > 0) onConnect(host.hostAddress, port)
        }.also { it.start() }
        discoverWithNsd(NSD_PAIRING) { host, port -> onPairing(host, port) }
        discoverWithNsd(NSD_CONNECT) { host, port -> onConnect(host, port) }
    }

    fun stopDiscovery() {
        runCatching { pairingMdns?.stop() }
        runCatching { connectMdns?.stop() }
        pairingMdns = null
        connectMdns = null
        nsdListeners.forEach { listener -> runCatching { nsd?.stopServiceDiscovery(listener) } }
        nsdListeners.clear()
        runCatching { multicast?.takeIf { it.isHeld }?.release() }
        multicast = null
    }

    private fun onPairing(host: String?, port: Int) {
        if (host == null) return
        _found.value = _found.value.copy(host = host, pairingPort = port)
    }

    private fun onConnect(host: String?, port: Int) {
        if (host == null) return
        _found.value = _found.value.copy(host = _found.value.host ?: host, connectPort = port)
    }

    private fun discoverWithNsd(type: String, onFound: (String?, Int) -> Unit) {
        val manager = nsd ?: return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) = resolve(manager, info, onFound, attempt = 0)
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.i(TAG, "NSD discovery of $serviceType failed: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        runCatching { manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onSuccess { nsdListeners += listener }
    }

    /**
     * Resolves one announced service to its address. The old API resolves one service at a
     * time and says so with FAILURE_ALREADY_ACTIVE; a watch announces both of its services at
     * once, so the second is simply asked again a moment later.
     */
    @Suppress("DEPRECATION")
    private fun resolve(manager: NsdManager, info: NsdServiceInfo, onFound: (String?, Int) -> Unit, attempt: Int) {
        runCatching {
            manager.resolveService(
                info,
                object : NsdManager.ResolveListener {
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        if (resolved.port > 0) onFound(resolved.host?.hostAddress, resolved.port)
                    }

                    override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) {
                        if (errorCode != NsdManager.FAILURE_ALREADY_ACTIVE || attempt >= RESOLVE_ATTEMPTS) return
                        scope.launch {
                            kotlinx.coroutines.delay(RESOLVE_RETRY_MS)
                            resolve(manager, failed, onFound, attempt + 1)
                        }
                    }
                },
            )
        }
    }

    /**
     * The whole install. [pairingPort] and [code] are what the watch shows under
     * "Pair new device"; [connectPort] is the one under "Wireless debugging"
     * (found by discovery when it is not typed). [apk] supplies the file: the
     * release from the store, or one picked on the phone.
     */
    fun install(
        host: String,
        pairingPort: Int,
        code: String,
        connectPort: Int?,
        apk: suspend ((Float?) -> Unit) -> Result<File>,
    ) {
        scope.launch {
            val adb = WatchAdb(context)
            try {
                _step.value = Step.Pairing
                val paired = runCatching { adb.pair(host, pairingPort, code.trim()) }.getOrElse { error ->
                    Log.i(TAG, "pairing failed: ${error.message}")
                    false
                }
                if (!paired) {
                    _step.value = Step.Failed(Reason.WRONG_CODE)
                    return@launch
                }

                _step.value = Step.Connecting
                val port = connectPort ?: awaitConnectPort() ?: run {
                    _step.value = Step.Failed(Reason.NOT_FOUND)
                    return@launch
                }
                val connected = runCatching { adb.connect(host, port) }.getOrElse { error ->
                    Log.i(TAG, "connect failed: ${error.message}")
                    false
                }
                if (!connected) {
                    _step.value = Step.Failed(Reason.REFUSED)
                    return@launch
                }

                _step.value = Step.Downloading(null)
                val file = apk { progress -> _step.value = Step.Downloading(progress) }.getOrElse { error ->
                    val reason = if (error is NoReleaseException) Reason.NO_RELEASE else Reason.NO_APK
                    _step.value = Step.Failed(reason, error.message.orEmpty())
                    return@launch
                }

                _step.value = Step.Installing
                val result = installApk(adb, file)
                if (!result.startsWith("Success")) {
                    _step.value = Step.Failed(Reason.INSTALL_FAILED, result.take(MAX_DETAIL))
                    return@launch
                }

                _step.value = Step.Finishing
                // Wear OS has no "install unknown apps" screen: this is what lets the watch
                // install the updates the phone sends it from now on.
                exec(adb, "appops set $PACKAGE REQUEST_INSTALL_PACKAGES allow")
                exec(adb, "am start -n $PACKAGE/$ACTIVITY")
                _step.value = Step.Done
            } catch (error: Exception) {
                Log.w(TAG, "install stopped: ${error.message}", error)
                _step.value = Step.Failed(Reason.REFUSED, error.message.orEmpty().take(MAX_DETAIL))
            } finally {
                runCatching { adb.disconnect() }
                runCatching { adb.close() }
            }
        }
    }

    fun reset() {
        _step.value = Step.Idle
    }

    private suspend fun awaitConnectPort(): Int? {
        repeat(CONNECT_WAIT_STEPS) {
            _found.value.connectPort?.let { return it }
            kotlinx.coroutines.delay(CONNECT_WAIT_STEP_MS)
        }
        return _found.value.connectPort
    }

    /**
     * `pm install` reading the APK from its standard input, the way `adb install`
     * streams it: `-S` says how many bytes to expect, `-i` names this app as the
     * installer of record, `-r` replaces, `-g` grants what the manifest asks for.
     */
    private suspend fun installApk(adb: WatchAdb, apk: File): String = withContext(Dispatchers.IO) {
        val size = apk.length()
        adb.openStream("exec:cmd package install -r -g -i $PACKAGE -S $size").use { stream ->
            // Not closed after writing: closing the write side closes the stream, and the
            // answer ("Success", or why not) comes back on it after the last byte.
            val out = stream.openOutputStream()
            apk.inputStream().use { it.copyTo(out, BUFFER) }
            out.flush()
            stream.openInputStream().bufferedReader().readText().trim()
        }
    }

    private suspend fun exec(adb: WatchAdb, command: String): String = withContext(Dispatchers.IO) {
        runCatching {
            adb.openStream("exec:$command").use { it.openInputStream().bufferedReader().readText().trim() }
        }.getOrElse { it.message.orEmpty() }
    }

    private companion object {
        const val TAG = "WatchInstaller"
        const val PACKAGE = "dev.pampa.fluidify"
        const val ACTIVITY = "dev.pampa.fluidify.wear.MainActivity"
        const val BUFFER = 64 * 1024
        const val MAX_DETAIL = 200
        /** Thirty seconds: a watch that has just paired can take a while to announce its port. */
        const val CONNECT_WAIT_STEPS = 60
        const val CONNECT_WAIT_STEP_MS = 500L
        const val MULTICAST_TAG = "fluidify-watch-install"
        const val NSD_PAIRING = "_adb-tls-pairing._tcp"
        const val NSD_CONNECT = "_adb-tls-connect._tcp"
        const val RESOLVE_ATTEMPTS = 5
        const val RESOLVE_RETRY_MS = 400L
    }
}

/** The store has no watch build to offer yet: pick an APK on the phone instead. */
class NoReleaseException : IllegalStateException("no watch build is published")

