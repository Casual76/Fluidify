package dev.lelonio.square.wear

import android.content.Context
import android.media.AudioManager
import dev.lelonio.square.data.RemoteConnect
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

    fun set(context: Context, level: Float, deviceId: String?) {
        val target = level.coerceIn(0f, 1f)
        val active = RemoteConnect.devices.value.firstOrNull { it.active }
        val remoteId = deviceId?.takeIf { it != PhoneWearBridge.PHONE_DEVICE_ID && !RemoteConnect.isThisPhone(it) }
            ?: active?.takeIf { RemoteConnect.elsewhereActive.value && !it.isThisPhone }?.id
        if (remoteId != null) {
            RemoteConnect.setVolume(remoteId, (target * MAX_CONNECT).roundToInt())
            return
        }
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (target * max).roundToInt().coerceIn(0, max), 0)
    }

    private const val MAX_CONNECT = 65535
}
