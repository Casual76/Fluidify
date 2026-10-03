package dev.pampa.fluidify.wear.standalone

import android.content.Context
import android.os.Build
import android.util.Log
import dev.lelonio.square.nativecore.NativeBridge
import dev.lelonio.square.playback.BitrateSteps
import dev.lelonio.square.playback.LibrespotPlayer
import dev.pampa.fluidify.wear.protocol.AuthErrors
import dev.pampa.fluidify.wear.protocol.AuthReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
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
 * Only while the watch is the one playing: [stop] takes the Connect device off
 * the account and lets the radio sleep.
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

    /** Starts the engine for [player], which receives its events. True when it is up. */
    suspend fun start(player: LibrespotPlayer, output: WearAudioOutput): Boolean {
        if (started) return true
        _status.value = EngineStatus.STARTING
        if (!contextReady) {
            NativeBridge.initContext(context)
            contextReady = true
        }
        NativeBridge.setAudioOutput(output)
        // A watch in the account's device list, not another phone.
        runCatching { NativeBridge.setDeviceType(DEVICE_TYPE) }

        val route = network.acquire()
        val grant = auth.tokenForStart()
        if (grant == null || !grant.ok) {
            _status.value = when (grant?.error) {
                AuthErrors.SIGNED_OUT -> EngineStatus.SIGNED_OUT
                else -> EngineStatus.NEEDS_PHONE
            }
            network.release()
            return false
        }

        val result = runCatching { startNative(player, grant.accessToken.orEmpty(), grant.clientId ?: prefs.clientId, route) }
            .recoverCatching { error ->
                // A credential the access point no longer takes: one more try with a token,
                // which is the only case in which the watch asks the phone again.
                if (!auth.hasCredential || grant.accessToken?.isNotEmpty() == true) throw error
                Log.w(TAG, "kept credential refused, asking the phone: ${error.message}")
                val fresh = auth.tokenForStart(AuthReason.CREDENTIAL_REFUSED)?.takeIf { it.ok } ?: throw error
                startNative(player, fresh.accessToken.orEmpty(), fresh.clientId ?: prefs.clientId, route)
            }
        return result.fold(
            onSuccess = {
                started = true
                auth.onEngineSignedIn()
                _status.value = EngineStatus.RUNNING
                true
            },
            onFailure = { error ->
                Log.e(TAG, "engine start failed: ${error.message}", error)
                _status.value = if (error.message?.contains(PREMIUM_REQUIRED) == true) EngineStatus.PREMIUM_REQUIRED else EngineStatus.FAILED
                network.release()
                false
            },
        )
    }

    /** Takes the Connect device off the account and lets the network go. */
    fun stop() {
        if (started) runCatching { NativeBridge.shutdown() }
        started = false
        network.release()
        _status.value = EngineStatus.OFF
    }

    private suspend fun startNative(player: LibrespotPlayer, token: String, clientId: String?, route: Route) =
        withContext(Dispatchers.IO) {
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
                listener = player,
            )
        }

    /** What the network can carry: the full step on Wi-Fi, the lowest over the phone. */
    private fun bitrateFor(route: Route): Int = when (route) {
        Route.WIFI -> BitrateSteps.MEDIUM
        Route.CELLULAR -> BitrateSteps.MEDIUM
        Route.PROXY, Route.NONE -> BitrateSteps.LOW
    }

    private fun deviceName(): String = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Wear OS"

    private companion object {
        const val TAG = "WatchEngine"
        const val DEVICE_TYPE = "smartwatch"

        /** The phrase the native side puts in a refusal for an account without Premium. */
        const val PREMIUM_REQUIRED = "premium"
    }
}
