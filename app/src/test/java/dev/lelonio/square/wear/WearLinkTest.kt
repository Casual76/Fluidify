package dev.lelonio.square.wear

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.wearable.Node
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WearLinkTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun node(id: String = "watch", nearby: Boolean = true) = object : Node {
        override fun getId() = id
        override fun getDisplayName() = "Watch"
        override fun isNearby() = nearby
    }
    @Test fun knownWatchUsesTheLiveNodeWithoutDependingOnCapabilityDiscovery() = runTest {
        assertTrue(WearLink(context) { listOf(node()) }.awaitNearbyWatch("watch"))
    }
    @Test fun briefReconnectionAndLookupFailureAreRetried() = runTest {
        var queries = 0
        val link = WearLink(context) {
            queries++
            when (queries) { 1 -> error("discovery temporarily unavailable"); 2 -> emptyList(); else -> listOf(node()) }
        }
        assertTrue(link.awaitNearbyWatch("watch"))
        assertEquals(3, queries)
    }
    @Test fun cloudOnlyAndOtherNearbyWatchesNeverAuthorizeTheTransfer() = runTest {
        val link = WearLink(context) { listOf(node(nearby = false), node("another-watch")) }
        assertFalse(link.awaitNearbyWatch("watch", timeoutMs = 1_000))
        assertEquals(1_000, testScheduler.currentTime)
    }
    @Test fun aQueryThatNeverCompletesIsBounded() = runTest {
        assertFalse(WearLink(context) { awaitCancellation() }.awaitNearbyWatch("watch", timeoutMs = 1_000))
    }
    @Test fun callerCancellationIsNotSwallowed() = runTest {
        try {
            WearLink(context) { throw CancellationException("cancelled") }.awaitNearbyWatch("watch")
            fail("cancellation must propagate")
        } catch (expected: CancellationException) { assertEquals("cancelled", expected.message) }
    }
}
