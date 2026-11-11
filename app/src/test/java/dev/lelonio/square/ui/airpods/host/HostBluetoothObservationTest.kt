package dev.lelonio.square.ui.airpods.host

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import androidx.test.core.app.ApplicationProvider
import dev.lelonio.square.airpods.runtime.storage.PairAddress
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/** UI profile observation only; no controller/service/transport is installed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class HostBluetoothObservationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val pair = PairAddress("00:11:22:33:44:55")
    private fun proxy(device: BluetoothDevice): BluetoothProfile = Proxy.newProxyInstance(
        BluetoothProfile::class.java.classLoader, arrayOf(BluetoothProfile::class.java),
    ) { _, method, _ -> when(method.name) {
        "getConnectedDevices", "getDevicesMatchingConnectionStates" -> listOf(device)
        "getConnectionState" -> BluetoothProfile.STATE_CONNECTED
        else -> null
    } } as BluetoothProfile

    @Suppress("DEPRECATION")
    @Test fun refusedRequestCanBeRetriedAndPendingRequestIsNotDuplicated() {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        adapter.enable()
        var requests = 0
        var callback: BluetoothProfile.ServiceListener? = null
        val releases = mutableListOf<BluetoothProfile>()
        val host = HostBluetooth(context, { listener ->
            requests++; if (requests == 1) false else { callback = listener; true }
        }, { _, value -> releases += value })
        try {
            host.prepare(); assertEquals(1, requests); assertFalse(host.connected(pair))
            host.prepare(); assertEquals(2, requests)
            host.prepare(); assertEquals(2, requests) // current request is still pending
            val profile = proxy(adapter.getRemoteDevice(pair.value))
            callback!!.onServiceConnected(BluetoothProfile.A2DP, profile)
            assertTrue(host.connected(pair))
            host.prepare(); assertEquals(2, requests)
        } finally { host.close() }
        assertEquals(1, releases.size)
    }

    @Test fun callbackAfterDisposalOnlyReleasesItsProfile() {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        var callback: BluetoothProfile.ServiceListener? = null
        var releases = 0
        val host = HostBluetooth(context, { callback = it; true }, { _, _ -> releases++ })
        host.prepare(); host.close()
        callback!!.onServiceConnected(BluetoothProfile.A2DP, proxy(adapter.getRemoteDevice(pair.value)))
        assertFalse(host.connected(pair)); assertEquals(1, releases)
        host.prepare(); host.close(); assertEquals(1, releases)
    }
}
