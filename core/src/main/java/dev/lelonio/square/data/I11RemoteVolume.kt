package dev.lelonio.square.data

import kotlinx.coroutines.CancellationException

/** A refused JNI dispatch is a refused volume command, including a revoked network permit. */
internal fun i11RemoteVolume(deviceId: String, volume: Int, send: (String, Int) -> Unit): Boolean =
    i11AdmittedRemoteVolume(deviceId, volume, null, send)

internal fun i11AdmittedRemoteVolume(
    deviceId: String, volume: Int, admission: ((() -> Unit) -> Unit)?,
    send: (String, Int) -> Unit,
): Boolean = i11AdmittedRemoteVolume(deviceId, volume, null, admission, send)

internal fun i11AdmittedRemoteVolume(
    deviceId: String, volume: Int, originalPermit: SpotifyNetworkPolicy.Permit?,
    admission: ((() -> Unit) -> Unit)?, send: (String, Int) -> Unit,
): Boolean = try {
    val permit = originalPermit ?: SpotifyNetworkPolicy.capture()
    if (admission == null) permit.dispatch { send(deviceId, volume.coerceIn(0, 65535)) }
    else permit.dispatchWithAdmission(admission, requireNativeScope = true) {
        send(deviceId, volume.coerceIn(0, 65535))
    }
    true
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) { false }
