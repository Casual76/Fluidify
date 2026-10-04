package dev.pampa.fluidify.wear.link

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.wearable.Node
import dev.pampa.fluidify.wear.protocol.Hello
import dev.pampa.fluidify.wear.protocol.Role
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class PhoneDiscoveryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun node(nearby: Boolean) = object : Node {
        override fun getId() = "phone"
        override fun getDisplayName() = "Phone"
        override fun isNearby() = nearby
    }
    @Test fun liveNearbyNodeOverridesStaleCapabilityProximity() = runTest {
        val link = PhoneLink(context, backgroundScope, WatchState(context), ArtStore(context),
            connectedNodes = { listOf(node(true)) }, capabilityNodes = { setOf(node(false)) })
        assertEquals("phone", link.reachablePhone())
    }
    @Test fun knownPhoneDoesNotNeedToBeRediscoveredAfterEachHello() = runTest {
        val link = PhoneLink(context, backgroundScope, WatchState(context), ArtStore(context),
            connectedNodes = { listOf(node(true)) }, capabilityNodes = { error("capability unavailable") })
        link.onHello(Hello(Role.PHONE, "1.5.2", 10, wantsReply = false), "phone")
        assertEquals("phone", link.reachablePhone())
    }
    @Test fun staleNearbyCapabilityCannotAllowACloudOnlyPhone() = runTest {
        val link = PhoneLink(context, backgroundScope, WatchState(context), ArtStore(context),
            connectedNodes = { listOf(node(false)) }, capabilityNodes = { setOf(node(true)) })
        assertNull(link.reachablePhone())
    }
}
