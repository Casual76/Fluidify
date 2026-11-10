// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.media

import android.app.Application
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import dev.lelonio.square.airpods.runtime.storage.PairAddress
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers

/** PREPARED, NOT EXECUTED. Real private Android observation boundary, shadow profile reads.
 * Reflection supplies only the profile and invokes observation; it does not forge C6 ownership. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35],
    shadows = [I5LintA2dpPermissionShadow::class])
class I5LintMediaPermissionTest {
    @Suppress("DEPRECATION")
    private fun assertClosed(throwOnConnected: Boolean) {
        val selected = PairAddress("00:11:22:33:44:55")
        val profile = Shadow.newInstanceOf(BluetoothA2dp::class.java)
        val shadow = Shadow.extract<I5LintA2dpPermissionShadow>(profile)
        shadow.throwOnConnected = throwOnConnected
        shadow.device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(selected.value)
        val env = AndroidMediaEnvironment(RuntimeEnvironment.getApplication())
        ReflectionHelpers.setField(env, "a2dp", profile)
        val method = AndroidMediaEnvironment::class.java.getDeclaredMethod(
            "facts", PairAddress::class.java, Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        for (call in listOf(false, true)) {
            val facts = method.invoke(env, selected, call) as ExternalMediaFacts
            assertEquals(ExternalMediaFacts(false, false, false, false, "unavailable", call), facts)
        }
        assertEquals(2, shadow.connectedReads)
        assertEquals(if (throwOnConnected) 0 else 2, shadow.streamingReads)
    }

    @Test fun deniedConnectedDevicesClosesAllRouteEvidenceAndPreservesCallFlag() = assertClosed(true)
    @Test fun revocationAtStreamingReadDiscardsPreviouslyReadConnectedPair() = assertClosed(false)
}

@Implements(BluetoothA2dp::class)
class I5LintA2dpPermissionShadow {
    var throwOnConnected = false
    lateinit var device: BluetoothDevice
    var connectedReads = 0
    var streamingReads = 0

    @Implementation
    protected fun getConnectedDevices(): List<BluetoothDevice> {
        connectedReads++
        if (throwOnConnected) throw SecurityException("synthetic connected-device denial")
        return listOf(device)
    }

    @Implementation
    protected fun isA2dpPlaying(candidate: BluetoothDevice): Boolean {
        check(candidate == device)
        streamingReads++
        throw SecurityException("synthetic streaming-read revocation")
    }
}
