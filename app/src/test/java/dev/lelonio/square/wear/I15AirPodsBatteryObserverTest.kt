package dev.lelonio.square.wear

import android.app.Application
import dev.lelonio.square.airpods.runtime.android.AirPodsAndroidRuntime
import dev.lelonio.square.auth.LocalSpotifyAuthority
import dev.lelonio.square.data.AccountLocalDataCoordinator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class I15AirPodsBatteryObserverTest {
    private fun accounts(): AccountLocalDataCoordinator {
        var receipt: String? = null
        val authority = LocalSpotifyAuthority({ receipt }, { receipt = it },
            { LocalSpotifyAuthority.KeptIdentity("a", "synthetic-i15") })
        return AccountLocalDataCoordinator({ null }, { null }, { "a" }, authority, { false })
    }

    @Test fun absentOwnerStaysAbsentAndClosingRemovesAccountObserver() = runTest {
        val before = AirPodsAndroidRuntime.get()
        val accounts = accounts()
        var changes = 0
        val observer = AirPodsBatteryObserver(backgroundScope, null, accounts, AirPodsBatteryProjection()) { changes++ }
        runCurrent()
        assertNull(observer.snapshot())
        assertSame(before, AirPodsAndroidRuntime.get())
        val delivered = changes
        observer.close()
        accounts.revoke()
        runCurrent()
        assertEquals(delivered, changes)
        assertNull(observer.snapshot())
    }

    @Test fun closingBeforeQueuedAccountCallbackPreventsResidualPublisher() = runTest {
        var changes = 0
        val observer = AirPodsBatteryObserver(backgroundScope, null, accounts(), AirPodsBatteryProjection()) { changes++ }
        observer.close()
        runCurrent()
        assertEquals(0, changes)
    }
}
