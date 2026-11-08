// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.android
import android.app.Application
import android.app.Service
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35],
    shadows = [AirPodsTileLifecycleShadow::class])
class AirPodsComponentsTest {
    @Test fun arbitraryStartWithoutGateReservationDoesNotBecomeForeground() {
        val service = Robolectric.buildService(AirPodsControlService::class.java).create().get()
        val result = service.onStartCommand(Intent().putExtra("enabled",true).putExtra("setupVerified",true).putExtra("productConformance",true),0,1)
        assertEquals(Service.START_NOT_STICKY,result)
        assertEquals(0,shadowOf(service).lastForegroundNotificationId)
        assertNull(AirPodsAndroidRuntime.get())
    }
    @Test fun nullIntentCannotRestoreStoppedService() {
        val service = Robolectric.buildService(AirPodsControlService::class.java).create().get()
        assertEquals(Service.START_NOT_STICKY,service.onStartCommand(null,0,1))
        assertEquals(0,shadowOf(service).lastForegroundNotificationId)
    }
    @Test fun tileClickAndListeningCannotConstructProcessOwner() {
        val tile = Robolectric.buildService(AirPodsQuickSettingsTile::class.java).create().get()
        tile.onStartListening(); tile.onClick()
        assertNull(AirPodsAndroidRuntime.get())
    }
    /** PREPARED / NOT EXECUTED: lifecycle ingress remains inert with no process owner. */
    @Test fun tileAddedRepeatedListeningStopAndDestroyCannotStartServiceOrCreateOwner() {
        val service = Robolectric.buildService(AirPodsQuickSettingsTile::class.java).create()
        val tile = service.get()
        tile.onTileAdded()
        repeat(3) { tile.onStartListening(); tile.onClick() }
        tile.onStopListening()
        tile.onStartListening()
        service.destroy()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNull(AirPodsAndroidRuntime.get())
        assertNull(AirPodsServiceOwnerRegistry.resolve())
        assertNull(shadowOf(tile.application).nextStartedService)
    }
}

/** Robolectric 4.16's Tile shadow cannot be cast to ShadowService in framework
 * super.onDestroy. The component's real onDestroy and observer disposal still run;
 * only the framework teardown is stubbed in this no-owner lifecycle fixture. */
@org.robolectric.annotation.Implements(android.service.quicksettings.TileService::class)
class AirPodsTileLifecycleShadow : org.robolectric.shadows.ShadowTileService() {
    @org.robolectric.annotation.Implementation
    protected fun onDestroy() = Unit
}
