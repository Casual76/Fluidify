// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.android

import android.app.Application
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import dev.lelonio.square.airpods.runtime.storage.PairAddress
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/** PREPARED NOT EXECUTED. Exercises the real profile-request state, no Android request. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
class AndroidBluetoothReadinessTest {
    private val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun checkSelfPermission(permission: String) = PackageManager.PERMISSION_GRANTED
    }
    private fun profile(): BluetoothProfile = Proxy.newProxyInstance(BluetoothProfile::class.java.classLoader,
        arrayOf(BluetoothProfile::class.java)) { _, method, _ ->
        when (method.name) {
            "getConnectedDevices", "getDevicesMatchingConnectionStates" -> emptyList<android.bluetooth.BluetoothDevice>()
            "getConnectionState" -> BluetoothProfile.STATE_DISCONNECTED
            else -> null
        }
    } as BluetoothProfile
    @Test fun falseAndThrowResetRequestWithoutRetryAndLaterIngressCanRequest() {
        listOf(false, true).forEach { throws ->
            var requests = 0
            var listener: BluetoothProfile.ServiceListener? = null
            val facts = AndroidBluetoothFacts(context) { callback ->
                requests++; listener = callback
                if (requests == 1) { if (throws) error("fixture refusal") else false } else true
            }
            var ready = 0
            assertNull(facts.whenProfileReady { ready++ })
            assertEquals(1, requests); assertEquals(0, ready)
            assertNotNull(facts.whenProfileReady { ready++ })
            assertEquals(2, requests)
            listener!!.onServiceConnected(BluetoothProfile.A2DP, profile())
            assertEquals(1, ready) // failed ingress was discarded
        }
    }
    @Test fun cancellationAndRepeatedProfileDeliveryDoNotWakeUnrelatedSubscribers() {
        lateinit var listener: BluetoothProfile.ServiceListener
        var requests = 0
        val facts = AndroidBluetoothFacts(context) { listener = it; requests++; true }
        var stopped = 0; var current = 0
        val old = facts.whenProfileReady { stopped++ }!!
        facts.whenProfileReady { current++ }
        old.close()
        listener.onServiceConnected(BluetoothProfile.A2DP, profile())
        listener.onServiceConnected(BluetoothProfile.A2DP, profile())
        assertEquals(1, requests); assertEquals(0, stopped); assertEquals(1, current)
    }
    @Test fun bondQueriesDoNotRequestProfileAndUnresolvedIsNotDisconnected() {
        var requests = 0
        val facts = AndroidBluetoothFacts(context) { requests++; false }
        val pair = PairAddress("AA:BB:CC:DD:EE:01")
        facts.bond(pair); facts.bonded(pair)
        assertEquals(0, requests)
        assertEquals(ProfileConnection.UNRESOLVED, facts.connection(pair))
    }
}
