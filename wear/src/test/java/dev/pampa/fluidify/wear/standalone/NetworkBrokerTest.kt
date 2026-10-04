package dev.pampa.fluidify.wear.standalone

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NetworkBrokerTest {
    @Test fun overlappingWaitsShareOneRequestAndCancellationReleasesOnlyItsHold() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val shadow = shadowOf(connectivity)
        val broker = NetworkBroker(context, StandalonePrefs(context))
        val first = async { broker.acquire() }
        val second = async { broker.acquire() }
        runCurrent()
        assertEquals(1, shadow.networkCallbacks.size)
        first.cancel()
        runCurrent()
        assertEquals(1, shadow.networkCallbacks.size)
        second.cancel()
        runCurrent()
        assertTrue(shadow.networkCallbacks.isEmpty())
        assertEquals(Route.NONE, broker.route.value)
    }

    @Test fun availabilityLossAndReturnUpdateRouteAndLastReleaseUnbinds() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val shadow = shadowOf(connectivity)
        val network = connectivity.allNetworks.first()
        val capabilities = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadow.setNetworkCapabilities(network, capabilities)
        val broker = NetworkBroker(context, StandalonePrefs(context))
        val wait = async { broker.acquire() }
        runCurrent()
        val callback = shadow.networkCallbacks.single()
        callback.onAvailable(network)
        runCurrent()
        assertEquals(Route.WIFI, wait.await())
        callback.onLost(network)
        assertNotEquals(Route.WIFI, broker.route.value)
        callback.onAvailable(network)
        assertEquals(Route.WIFI, broker.route.value)
        broker.release()
        assertTrue(shadow.networkCallbacks.isEmpty())
        assertNull(connectivity.boundNetworkForProcess)
    }
}
