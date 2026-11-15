package dev.lelonio.square.wear

import android.content.Context
import android.media.AudioManager
import dev.lelonio.square.data.RemoteConnect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The volume a remote turns: this phone's music stream, or the Spotify Connect
 * device the phone is controlling.
 *
 * The phone's own volume is the system's media stream, the one its hardware keys
 * move, not the engine's software gain: a listener expects the watch and the keys
 * to move the same thing, and the system shows its own slider for it.
 */
object ConnectVolume {

    /** Only an actual remote target needs account bootstrap; explicit phone volume stays local. */
    internal fun routesRemotely(deviceId: String?): Boolean {
        if (deviceId == PhoneWearBridge.PHONE_DEVICE_ID) return false
        val devices = RemoteConnect.devices.value
        return if (deviceId != null) devices.none { it.id == deviceId && it.isThisPhone }
        else RemoteConnect.elsewhereActive.value && devices.any { it.active && !it.isThisPhone }
    }

    /**
     * Suspends because a Connect device's volume is an HTTP request: called once per bezel step
     * from the bridge's main-thread scope, it froze the phone's UI for a round trip each time.
     */
    suspend fun set(
        context: Context, level: Float, deviceId: String?, isCurrent: () -> Boolean = { true },
        networkPermit: dev.lelonio.square.data.SpotifyNetworkPolicy.Permit? = null,
        useCurrent: (() -> Boolean) -> Boolean = { it() },
    ): Boolean {
        if (!isCurrent()) return false
        return i11ConnectVolume(level, deviceId,
            active = RemoteConnect.devices.value.firstOrNull { it.active },
            elsewhere = RemoteConnect.elsewhereActive.value,
            isThisPhone = RemoteConnect::isThisPhone,
            remote = { id, raw ->
              val permit = networkPermit ?: dev.lelonio.square.data.SpotifyNetworkPolicy.capture()
              withContext(Dispatchers.IO) {
                RemoteConnect.setVolume(id, raw, permit, admission = { enter ->
                    check(useCurrent {
                        if (!isCurrent()) false else { enter(); true }
                    }) { "Volume request account changed" }
                })
              }
            },
            phone = { target -> useCurrent {
                val audio = context.getSystemService(AudioManager::class.java)
                if (audio == null || !isCurrent()) false else {
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC,
                        (target * max).roundToInt().coerceIn(0, max), 0)
                    true
                }
            } }) && isCurrent()
    }

}

/** Explicit phone selection never falls through to the active Connect speaker. */
internal suspend fun i11ConnectVolume(
    level: Float,
    deviceId: String?,
    active: dev.lelonio.square.data.RemoteDevice?,
    elsewhere: Boolean,
    isThisPhone: (String) -> Boolean,
    remote: suspend (String, Int) -> Boolean,
    phone: (Float) -> Boolean,
): Boolean {
    if (!level.isFinite()) return false
    val target = level.coerceIn(0f, 1f)
    val remoteId = if (deviceId != null) {
        deviceId.takeIf { it != PhoneWearBridge.PHONE_DEVICE_ID && !isThisPhone(it) }
    } else active?.takeIf { elsewhere && !it.isThisPhone }?.id
    return if (remoteId != null) remote(remoteId, (target * 65535).roundToInt()) else phone(target)
}
