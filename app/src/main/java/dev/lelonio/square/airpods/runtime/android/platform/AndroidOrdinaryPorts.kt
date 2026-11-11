// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.android.platform

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.companion.CompanionDeviceManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import dev.lelonio.square.airpods.runtime.storage.PairAddress
import java.lang.reflect.InvocationTargetException

/** No work at construction. Uses only the A2DP profile path, never BluetoothDevice.connect().
 * Missing hidden API metadata is a supported failure; no exemptions, JNI or accessible override.
 * Source enforcement: platform-adapters/sources.json, pinned A2dpService.java binder 1399-1415.
 */
@Suppress("MissingPermission")
class AndroidOrdinaryAudioProfile(context: Context) : AudioProfilePort {
    private val context = context.applicationContext
    private val adapter: BluetoothAdapter? get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var proxy: BluetoothProfile? = null
    private var preparing = false
    private var closed = false
    private var onReady: (() -> Unit)? = null

    private fun permitted() = if (Build.VERSION.SDK_INT >= 31) {
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    } else {
        context.checkSelfPermission(Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
    }

    @Synchronized override fun ready() = !closed && proxy != null

    @Synchronized override fun prepare(onReady: () -> Unit): Boolean {
        if (closed || preparing || !permitted() || Build.VERSION.SDK_INT !in 26..37) return false
        preparing = true
        this.onReady = onReady
        val accepted = try {
            adapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, arrived: BluetoothProfile) {
                    val continuation: (() -> Unit)?
                    synchronized(this@AndroidOrdinaryAudioProfile) {
                        if (closed || profile != BluetoothProfile.A2DP) {
                            adapter?.closeProfileProxy(profile, arrived)
                            return
                        }
                        proxy = arrived; preparing = false
                        continuation = this@AndroidOrdinaryAudioProfile.onReady
                        this@AndroidOrdinaryAudioProfile.onReady = null
                    }
                    continuation?.invoke()
                }
                override fun onServiceDisconnected(profile: Int) {
                    synchronized(this@AndroidOrdinaryAudioProfile) {
                        proxy = null; preparing = false
                    }
                }
            }, BluetoothProfile.A2DP) == true
        } catch (_: RuntimeException) { false }
        if (!accepted) { preparing = false; this.onReady = null }
        return accepted
    }

    @Synchronized override fun invoke(pair: PairAddress, operation: AudioOperation): PlatformResult {
        if (closed) return PlatformResult.Denied(PlatformFailure.CLOSED)
        if (!permitted()) return PlatformResult.Denied(PlatformFailure.PERMISSION)
        if (Build.VERSION.SDK_INT !in 26..37) return PlatformResult.Denied(PlatformFailure.PLATFORM_UNAVAILABLE)
        return try {
            val bt = adapter ?: return PlatformResult.Denied(PlatformFailure.PLATFORM_UNAVAILABLE)
            val profile = proxy ?: return PlatformResult.Denied(PlatformFailure.PLATFORM_UNAVAILABLE)
            if (!bt.isEnabled) return PlatformResult.Denied(PlatformFailure.PLATFORM_UNAVAILABLE)
            val device = bt.bondedDevices.firstOrNull { it.address.equals(pair.value, true) }
                ?: return PlatformResult.Denied(PlatformFailure.NOT_SELECTED_BONDED)
            // Connecting A2DP can evict another sink on single-device stacks. Never do that.
            if (profile.getDevicesMatchingConnectionStates(intArrayOf(
                    BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_CONNECTING,
                    BluetoothProfile.STATE_DISCONNECTING)).any { it != device })
                return PlatformResult.Denied(PlatformFailure.CALL_OR_ROUTE_MIX)
            val methodName = if (operation == AudioOperation.CONNECT) "connect" else "disconnect"
            val method = Class.forName("android.bluetooth.BluetoothA2dp").getMethod(methodName, BluetoothDevice::class.java)
            if (method.invoke(profile, device) == true) PlatformResult.RequestAccepted
            else PlatformResult.Denied(PlatformFailure.REJECTED)
        } catch (e: InvocationTargetException) {
            PlatformResult.Denied(if (e.cause is SecurityException) PlatformFailure.SECURITY else PlatformFailure.REJECTED)
        } catch (_: SecurityException) { PlatformResult.Denied(PlatformFailure.SECURITY)
        } catch (_: ReflectiveOperationException) { PlatformResult.Denied(PlatformFailure.HIDDEN_API_UNAVAILABLE)
        } catch (_: RuntimeException) { PlatformResult.Denied(PlatformFailure.PLATFORM_UNAVAILABLE) }
    }

    @Synchronized override fun close() {
        closed = true; onReady = null; preparing = false
        val old = proxy; proxy = null
        if (old != null) adapter?.closeProfileProxy(BluetoothProfile.A2DP, old)
    }
}

/** Never resolves first: package visibility is not proof that a handler is absent. */
class AndroidAssistantLaunchPort(private val context: Context?) : AssistantLaunchPort {
    override fun requestVoiceCommand(): PlatformResult {
        val c = context ?: return PlatformResult.Denied(PlatformFailure.NO_CONTEXT)
        return try {
            c.startActivity(Intent(Intent.ACTION_VOICE_COMMAND).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            // Android may silently suppress BAL. This is not ActivityLaunched.
            PlatformResult.RequestAccepted
        } catch (_: ActivityNotFoundException) { PlatformResult.Denied(PlatformFailure.NO_HANDLER)
        } catch (_: SecurityException) { PlatformResult.Denied(PlatformFailure.SECURITY)
        } catch (_: RuntimeException) { PlatformResult.Denied(PlatformFailure.PLATFORM_UNAVAILABLE) }
    }
}

/** Must be called in the live authorization supplier, not stored as a system grant. */
@Suppress("DEPRECATION")
fun hasOwnCompanionAssociation(context: Context, pair: PairAddress): Boolean = try {
    if (Build.VERSION.SDK_INT < 26 || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) false
    else {
        val manager = context.getSystemService(CompanionDeviceManager::class.java)
        if (Build.VERSION.SDK_INT >= 33) manager?.myAssociations?.any {
            it.deviceMacAddress?.toString()?.equals(pair.value, true) == true
        } == true else manager?.associations?.any { it.equals(pair.value, true) } == true
    }
} catch (_: RuntimeException) { false }

/** Intent data for a visible manual fallback. No startActivity and no background fallback. */
fun manualBluetoothSettingsIntent(): Intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
