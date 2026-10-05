package dev.pampa.fluidify.wear.standalone

import android.content.Context
import android.os.Build
import android.util.Log
import dev.lelonio.square.download.DownloadEvents
import dev.lelonio.square.nativecore.NativeBridge
import dev.lelonio.square.nativecore.NativeEvents
import dev.lelonio.square.playback.BitrateSteps
import dev.lelonio.square.playback.LibrespotPlayer
import dev.pampa.fluidify.wear.protocol.AuthErrors
import dev.pampa.fluidify.wear.protocol.AuthReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/** Where the watch's own engine is. */
enum class EngineStatus {
    OFF,
    STARTING,
    RUNNING,

    /** Never signed in, and the phone could not be reached to ask. */
    NEEDS_PHONE,

    /** The phone is signed out. */
    SIGNED_OUT,

    /** Spotify said no: the account has no Premium. */
    PREMIUM_REQUIRED,
    FAILED,
}

/**
 * The librespot engine on the watch: started for the music, gone with it.
 *
 * The same native core the phone runs, started the way the phone starts it
 * (PlaybackService.connectEngine) with three differences that are the whole
 * point of having it here: it says it is a smartwatch, so the account's device
 * list draws a watch; it signs in with a token the phone lends it once
 * ([WatchAuth]); and it starts over whatever network [NetworkBroker] could get,
 * at a bitrate that network can carry.
 *
 * Only while something needs it: the player while the watch plays, the
 * download queue while it fetches. Each takes a lease ([acquire]) and gives it
 * back ([release]); the last one out takes the Connect device off the account
 * and lets the radio sleep. The engine's events go to whichever player holds
 * a lease, through [events], so the download queue can start the engine
 * without a player and the player can arrive later and still hear everything.
 */
class WatchEngine(
    private val context: Context,
    private val auth: WatchAuth,
    private val network: NetworkBroker,
    private val prefs: StandalonePrefs,
) {
    private val _status = MutableStateFlow(EngineStatus.OFF)
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    @Volatile private var started = false
    private var contextReady = false
    private var leases = 0
    private val lock = kotlinx.coroutines.sync.Mutex()

    /** The engine's one listener, handing events to the player of the moment. */
    private val events = object : NativeEvents {
        @Volatile var player: LibrespotPlayer? = null

        override fun onEvent(type: String, uri: String, positionMs: Long) {
            val target = player
            // The player passes download progress on itself; without one, it still has to go.
            if (target != null) target.onEvent(type, uri, positionMs) else DownloadEvents.accept(type, uri, positionMs)
        }
    }

    /** Where the watch keeps its downloads; the engine finds them there, online or not. */
    var downloadRoot: java.io.File? = null

    /**
     * Takes a lease on the engine, starting it if it is the first. [player] (with
     * [output]) is the playback service; the download queue passes neither.
     * True when the engine is up.
     */
    suspend fun acquire(player: LibrespotPlayer? = null, output: WearAudioOutput? = null): Boolean = lock.withLock {
        // Callers are on the main thread, and the first touch of NativeBridge loads the library.
        if (output != null) withContext(Dispatchers.IO) { NativeBridge.setAudioOutput(output) }
        if (player != null) events.player = player
        val up = started || start()
        if (up) {
            leases++
        } else if (player != null && events.player === player) {
            // Not this player's engine after all: holding on to it kept a destroyed service alive.
            events.player = null
        }
        up
    }

    private val _retries = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Asked for by [requestRetry]: the playback service tries its lease again. */
    val retries: kotlinx.coroutines.flow.SharedFlow<Unit> = _retries

    /**
     * A start that failed (the phone was away, the network was down) is not the last word: the
     * next time the listener picks the watch, it is tried again. Shows STARTING at once, so a wait
     * for the outcome does not read the old failure as the new one.
     */
    fun requestRetry() {
        if (started) return
        if (_status.value != EngineStatus.STARTING) _status.value = EngineStatus.STARTING
        _retries.tryEmit(Unit)
    }

    /** Whether a start would be the first sign-in: the network, the phone's token and the handshake. */
    val firstSignIn: Boolean get() = !auth.hasCredential

    /**
     * Gives a lease back; the last one stops the engine.
     *
     * Not cancellable, whoever calls it: the lock can be held for most of a minute by an [acquire]
     * that is starting the engine, and a caller cancelled while it waited (a download worker the
     * system stopped) used to leave without giving its lease back — the count never reached zero
     * again, and the engine, the Wi-Fi and the Connect device stayed up with nothing using them.
     */
    suspend fun release(player: LibrespotPlayer? = null) {
        withContext(kotlinx.coroutines.NonCancellable) {
            lock.withLock {
                if (player != null && events.player === player) events.player = null
                leases = (leases - 1).coerceAtLeast(0)
                if (leases == 0) stop()
            }
        }
    }

    private suspend fun start(): Boolean {
        if (started) return true
        _status.value = EngineStatus.STARTING
        // The native calls are off the main thread: the first of them loads the library, and the
        // callers (the playback service's scope) are on it.
        withContext(Dispatchers.IO) {
            if (!contextReady) {
                NativeBridge.initContext(context)
                contextReady = true
            }
            // Before the engine: the first track it is asked for may be one already here.
            downloadRoot?.let { root -> runCatching { NativeBridge.setDownloadRoot(root.absolutePath) } }
            // A watch in the account's device list, not another phone.
            runCatching { NativeBridge.setDeviceType(DEVICE_TYPE) }
        }

        // A watch that has signed in before starts at once, network or not: its downloads
        // play offline, and the engine connects by itself once Wi-Fi comes up. Only a
        // first sign-in waits for a network, because it cannot happen without one. Either way
        // the hold is taken here, once, and given back once unless the engine came up.
        var holding = false
        try {
            val route = if (auth.hasCredential) {
                network.hold()
                holding = true
                network.route.value
            } else {
                network.acquire().also { holding = true }
            }
            val grant = auth.tokenForStart()
            if (grant == null || !grant.ok) {
                _status.value = when (grant?.error) {
                    AuthErrors.SIGNED_OUT -> EngineStatus.SIGNED_OUT
                    else -> EngineStatus.NEEDS_PHONE
                }
                return false
            }
            var failure = attempt { startNative(grant.accessToken.orEmpty(), grant.clientId ?: prefs.clientId, route) }
            // A credential the access point no longer takes: one more try with a token, which is
            // the only case in which the watch asks the phone again.
            if (failure != null && auth.hasCredential && grant.accessToken.isNullOrEmpty()) {
                Log.w(TAG, "kept credential refused, asking the phone: ${failure.message}")
                val fresh = auth.tokenForStart(AuthReason.CREDENTIAL_REFUSED)?.takeIf { it.ok }
                if (fresh != null) failure = attempt { startNative(fresh.accessToken.orEmpty(), fresh.clientId ?: prefs.clientId, route) }
            }
            if (failure == null) {
                started = true
                auth.onEngineSignedIn()
                _status.value = EngineStatus.RUNNING
                return true
            }
            Log.e(TAG, "engine start failed: ${failure.message}", failure)
            _status.value = if (failure.message?.contains(PREMIUM_REQUIRED) == true) EngineStatus.PREMIUM_REQUIRED else EngineStatus.FAILED
            return false
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // Whoever asked has gone; the next one starts over, from a status that says so.
            if (!started) _status.value = EngineStatus.OFF
            throw cancelled
        } finally {
            if (!started && holding) network.release()
        }
    }

    /** Runs [block], giving back what it threw — except a cancellation, which is the caller's. */
    private inline fun attempt(block: () -> Unit): Throwable? = try {
        block()
        null
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        error
    }

    /**
     * Waits for the engine to be a Spotify Connect device the account can see.
     *
     * [EngineStatus.RUNNING] comes earlier than that: a watch that has signed in before is handed
     * its player at once and connects in the background, and a transfer to it in that gap is the
     * 404 the tests found. Neither is the session being up enough ([NativeBridge.isConnected], what
     * this used to wait for): the account lists the device only once it has answered the device's
     * first state update, which can take seconds more. [NativeBridge.isEstablished] is that answer.
     *
     * Asked off the main thread, and less often the longer it takes. False when it does not
     * happen within [timeoutMs].
     */
    suspend fun awaitConnected(timeoutMs: Long): Boolean = withContext(Dispatchers.IO) {
        withTimeoutOrNull(timeoutMs) {
            var pause = CONNECTED_POLL_MS
            while (!runCatching { NativeBridge.isEstablished }.getOrDefault(false)) {
                kotlinx.coroutines.delay(pause)
                pause = (pause * 2).coerceAtMost(CONNECTED_POLL_MAX_MS)
            }
            true
        } ?: false
    }

    /** Takes the Connect device off the account and lets the network go. */
    private suspend fun stop() {
        if (!started) return
        // Blocks until the engine is down: never on the main thread.
        withContext(Dispatchers.IO) { runCatching { NativeBridge.shutdown() } }
        started = false
        network.release()
        _status.value = EngineStatus.OFF
    }

    /**
     * Not cancellable: the native start runs to its end once begun, and a caller cancelled half
     * way (the service going) used to read it as a failure — the status said FAILED over an engine
     * that was up, and the next acquire started it a second time.
     */
    private suspend fun startNative(token: String, clientId: String?, route: Route) =
        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
            NativeBridge.start(
                clientId = clientId.orEmpty(),
                deviceName = deviceName(),
                deviceId = prefs.deviceId,
                accessToken = token,
                credentialsDir = auth.credentialsDir.absolutePath,
                cacheDir = context.cacheDir.absolutePath,
                language = Locale.getDefault().language,
                bitrateKbps = bitrateFor(route),
                crossfadeMs = 0,
                listener = events,
            )
        }

    /** What the network can carry: the full step on Wi-Fi, the lowest over the phone. */
    private fun bitrateFor(route: Route): Int = when (route) {
        Route.WIFI, Route.CELLULAR -> BitrateSteps.MEDIUM
        Route.PROXY, Route.NONE -> BitrateSteps.LOW
    }

    private fun deviceName(): String = WatchName.of(context)

    private companion object {
        const val TAG = "WatchEngine"
        const val DEVICE_TYPE = "smartwatch"

        /** The phrase the native side puts in a refusal for an account without Premium. */
        const val PREMIUM_REQUIRED = "premium"
        const val CONNECTED_POLL_MS = 250L
        const val CONNECTED_POLL_MAX_MS = 1_000L
    }
}
