// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.android

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import dev.lelonio.square.airpods.AirPodsTransportOwnership
import dev.lelonio.square.airpods.runtime.lifecycle.ConnectionAdmission
import dev.lelonio.square.airpods.runtime.storage.PairAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Exercises the actual scheduling facade BEFORE service attach. No Bluetooth factory,
 * real service, device or platform profile is opened by these fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
class AirPodsAndroidOwnershipTest {
    @Before fun validProcessIdentity() { org.robolectric.shadows.ShadowProcess.setPid(41002) }
    private class SchedulingContext(base: Context) : ContextWrapper(base) {
        var starts = 0
        var denied = false
        override fun startForegroundService(service: Intent): ComponentName? {
            starts++
            check(!denied)
            return ComponentName(this, AirPodsControlService::class.java)
        }
    }
    private val admission = ConnectionAdmission(PairAddress("00:11:22:33:44:55"), "fixture", true, true, true, true, "fixture-build", 1)
    private fun start(controller: AirPodsAndroidController): Boolean =
        AirPodsAndroidController::class.java.getDeclaredMethod("startOwner", ConnectionAdmission::class.java).apply { isAccessible = true }
            .invoke(controller, admission) as Boolean
    private fun cleanup(controller: AirPodsAndroidController) {
        // Invoke the same cleanup entry used by C4; retained failure is inspected separately.
        runCatching { AirPodsAndroidController::class.java.getDeclaredMethod("stopOwner").apply { isAccessible = true }.invoke(controller) }
        ReflectionHelpers.getField<CoroutineScope>(controller, "processScope").cancel()
    }

    @Test fun diagnosticLeaseDeniesRealFacadeWithoutSchedulingFgs() {
        val context = SchedulingContext(RuntimeEnvironment.getApplication())
        val controller = AirPodsAndroidController(context, MutableStateFlow(false), { false }, null)
        val lease = AirPodsTransportOwnership.process.reserve(AirPodsTransportOwnership.Owner.DIAGNOSTIC)!!
        try {
            assertFalse(start(controller))
            assertEquals(0, context.starts)
            assertEquals("TRANSPORT_OWNER_BUSY", controller.runtime.value.failure)
            assertFalse(lease.released)
        } finally { cleanup(controller); lease.close() }
    }

    @Test fun realFacadeReservesBeforeServiceAttachAndCleanupReleases() {
        val context = SchedulingContext(RuntimeEnvironment.getApplication())
        val controller = AirPodsAndroidController(context, MutableStateFlow(false), { false }, null)
        try {
            assertTrue(start(controller))
            assertEquals(1, context.starts)
            assertFalse(controller.runtime.value.active) // attached flag cannot guard this race
            assertNull(AirPodsTransportOwnership.process.reserve(AirPodsTransportOwnership.Owner.DIAGNOSTIC))
        } finally { cleanup(controller) }
        val diagnostic = AirPodsTransportOwnership.process.reserve(AirPodsTransportOwnership.Owner.DIAGNOSTIC)!!
        diagnostic.close()
    }

    @Test fun schedulingFailureKeepsLeaseUntilGateCleanupWithoutRetry() {
        val context = SchedulingContext(RuntimeEnvironment.getApplication()).apply { denied = true }
        val controller = AirPodsAndroidController(context, MutableStateFlow(false), { false }, null)
        try {
            assertFalse(start(controller))
            assertEquals(1, context.starts)
            assertNull(AirPodsTransportOwnership.process.reserve(AirPodsTransportOwnership.Owner.DIAGNOSTIC))
            assertEquals("FGS_START_DENIED", controller.runtime.value.failure)
        } finally { cleanup(controller) }
        val diagnostic = AirPodsTransportOwnership.process.reserve(AirPodsTransportOwnership.Owner.DIAGNOSTIC)!!
        diagnostic.close()
    }
}
