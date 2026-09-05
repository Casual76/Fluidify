package dev.lelonio.square.playback

import android.media.MediaRoute2Info
import android.media.MediaRoute2ProviderService
import android.media.RouteDiscoveryPreference
import android.media.RoutingSessionInfo
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import dev.lelonio.square.data.RemoteConnect
import dev.lelonio.square.data.RemoteDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The account's other devices, offered to the phone's own output switcher.
 *
 * Android has a panel of its own for choosing where sound goes — behind the
 * "Media output" tile, and on the media notification — and since Android 11
 * an app can put its own destinations in it through one of these. Spotify's
 * client does exactly that: a laptop or a speaker sits in the list beside the
 * Bluetooth earpiece, and choosing it there is the same handover as choosing
 * it in the app's own picker. This is that, for the devices the account
 * reports through Connect.
 *
 * Nothing here moves the music itself. A choice is passed to
 * [RemoteConnect.requests], where the playback service — which holds the
 * player and knows where the song is — carries it out the way the app's own
 * picker does. What this owns is the list, and the system's idea of which
 * entry is selected.
 *
 * Bound by the system, not by the app: the class is loaded on Android 11 and
 * up only, which is what the manifest's `enabled` flag is for.
 */
@RequiresApi(Build.VERSION_CODES.R)
class ConnectRouteProvider : MediaRoute2ProviderService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The one routing session there can be: playback is on one device at a time. */
    private var sessionInfo: RoutingSessionInfo? = null

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            RemoteConnect.devices.collect { devices -> publish(devices) }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Hands the system the current list.
     *
     * Every device but this phone: the phone is the system's own route, the
     * one it draws first and selects when a session of ours is released. The
     * selected session follows the account's active device, so the switcher
     * shows the laptop as chosen while the laptop is playing, whoever chose it.
     */
    private fun publish(devices: List<RemoteDevice>) {
        val routes = devices.filterNot { it.isThisPhone }.map(::routeOf)
        notifyRoutes(routes)

        val active = devices.firstOrNull { it.active && !it.isThisPhone }
        val current = sessionInfo
        when {
            active == null && current != null -> {
                sessionInfo = null
                notifySessionReleased(current.id)
            }
            active != null && current?.selectedRoutes?.firstOrNull() != active.id -> {
                val info = sessionOf(active)
                sessionInfo = info
                if (current == null) {
                    // Nobody asked through the switcher; it is being told.
                    notifySessionCreated(REQUEST_ID_NONE, info)
                } else {
                    notifySessionUpdated(info)
                }
            }
            active != null && current != null -> {
                // Same device; the volume may have moved.
                val info = sessionOf(active)
                if (info.volume != current.volume) {
                    sessionInfo = info
                    notifySessionUpdated(info)
                }
            }
        }
    }

    private fun routeOf(device: RemoteDevice): MediaRoute2Info =
        MediaRoute2Info.Builder(device.id, device.name)
            .addFeature(FEATURE)
            .addFeature(MediaRoute2Info.FEATURE_REMOTE_AUDIO_PLAYBACK)
            // The kind of thing it is, for the icon beside it. The types are
            // Android 14's; earlier phones draw every remote route the same.
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    setType(typeOf(device.type))
                }
            }
            .setVolumeHandling(MediaRoute2Info.PLAYBACK_VOLUME_VARIABLE)
            .setVolumeMax(VOLUME_MAX)
            .setVolume(percentOf(device.volume))
            .setConnectionState(
                if (device.active) {
                    MediaRoute2Info.CONNECTION_STATE_CONNECTED
                } else {
                    MediaRoute2Info.CONNECTION_STATE_DISCONNECTED
                },
            )
            .build()

    private fun sessionOf(device: RemoteDevice): RoutingSessionInfo =
        RoutingSessionInfo.Builder(SESSION_ID, packageName)
            .addSelectedRoute(device.id)
            .setVolumeHandling(MediaRoute2Info.PLAYBACK_VOLUME_VARIABLE)
            .setVolumeMax(VOLUME_MAX)
            .setVolume(percentOf(device.volume))
            .build()

    /** librespot's spelling of the type, drawn with the nearest system icon. */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun typeOf(type: String): Int = when (type.lowercase()) {
        "computer" -> MediaRoute2Info.TYPE_REMOTE_COMPUTER
        "smartphone", "tablet" -> MediaRoute2Info.TYPE_REMOTE_SMARTPHONE
        "tv", "stb", "castvideo" -> MediaRoute2Info.TYPE_REMOTE_TV
        "speaker", "castaudio", "avr" -> MediaRoute2Info.TYPE_REMOTE_SPEAKER
        "smartwatch" -> MediaRoute2Info.TYPE_REMOTE_SMARTWATCH
        "gameconsole" -> MediaRoute2Info.TYPE_REMOTE_GAME_CONSOLE
        "automobile" -> MediaRoute2Info.TYPE_REMOTE_CAR
        else -> MediaRoute2Info.TYPE_UNKNOWN
    }

    override fun onDiscoveryPreferenceChanged(preference: RouteDiscoveryPreference) {
        publish(RemoteConnect.devices.value)
        // And the account's own picture, fresh: the panel is open.
        scope.launch(Dispatchers.IO) {
            runCatching { dev.lelonio.square.nativecore.NativeBridge.refreshCluster() }
        }
    }

    // --- What the switcher asks for -------------------------------------------

    override fun onCreateSession(
        requestId: Long,
        packageName: String,
        routeId: String,
        sessionHints: Bundle?,
    ) {
        val device = RemoteConnect.devices.value.firstOrNull { it.id == routeId }
        if (device == null) {
            notifyRequestFailed(requestId, REASON_ROUTE_NOT_AVAILABLE)
            return
        }
        // Answered now, moved next: the switcher wants an answer at once, and
        // the account reports the move a beat later, which is when the list
        // above catches up.
        val info = sessionOf(device)
        sessionInfo = info
        notifySessionCreated(requestId, info)
        RemoteConnect.request(RemoteConnect.TransferRequest.To(routeId))
    }

    override fun onTransferToRoute(requestId: Long, sessionId: String, routeId: String) {
        val device = RemoteConnect.devices.value.firstOrNull { it.id == routeId }
        if (device == null) {
            notifyRequestFailed(requestId, REASON_ROUTE_NOT_AVAILABLE)
            return
        }
        val info = sessionOf(device)
        sessionInfo = info
        notifySessionUpdated(info)
        RemoteConnect.request(RemoteConnect.TransferRequest.To(routeId))
    }

    /**
     * The system route chosen: the phone itself. Playback comes back here,
     * the same way it does when the app's own picker names this device.
     */
    override fun onReleaseSession(requestId: Long, sessionId: String) {
        sessionInfo = null
        notifySessionReleased(sessionId)
        RemoteConnect.request(RemoteConnect.TransferRequest.Here)
    }

    override fun onSelectRoute(requestId: Long, sessionId: String, routeId: String) =
        onTransferToRoute(requestId, sessionId, routeId)

    override fun onDeselectRoute(requestId: Long, sessionId: String, routeId: String) =
        onReleaseSession(requestId, sessionId)

    override fun onSetRouteVolume(requestId: Long, routeId: String, volume: Int) {
        scope.launch(Dispatchers.IO) {
            RemoteConnect.setVolume(routeId, rawOf(volume))
        }
    }

    override fun onSetSessionVolume(requestId: Long, sessionId: String, volume: Int) {
        val routeId = sessionInfo?.selectedRoutes?.firstOrNull() ?: return
        onSetRouteVolume(requestId, routeId, volume)
    }

    private fun percentOf(raw: Int): Int = (raw.coerceIn(0, RAW_MAX) * VOLUME_MAX + RAW_MAX / 2) / RAW_MAX

    private fun rawOf(percent: Int): Int = (percent.coerceIn(0, VOLUME_MAX) * RAW_MAX) / VOLUME_MAX

    companion object {
        /**
         * What marks a route as one of ours.
         *
         * The switcher shows an app the routes matching the features the app
         * itself asked to discover, so the service that owns the player asks
         * for this one; see PlaybackService.offerDevicesToSystem.
         */
        const val FEATURE = "dev.pampa.fluidify.CONNECT"

        private const val SESSION_ID = "fluidify-connect"

        /** The system's own hundred steps; the protocol's are 0..65535. */
        private const val VOLUME_MAX = 100
        private const val RAW_MAX = 65535
    }
}
