// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AirPodsDiagnosticOwnershipTest {
    private class Transport : AacpTransport {
        var closes = 0
        override fun connect() = Unit
        override fun readSdu(): ByteArray? = null
        override fun write(packet: ByteArray) = Unit
        override fun close() { closes++ }
    }
    @Test fun openingAndDestroyingActivityCannotAcquireOrCloseAnotherDiagnosticOwner() {
        val lease = AirPodsTransportOwnership.process.reserve(AirPodsTransportOwnership.Owner.DIAGNOSTIC)!!
        val socket = Transport()
        lease.construct { socket }
        try {
            val activity = Robolectric.buildActivity(AirPodsDiagnosticActivity::class.java).create().start().resume()
            activity.pause().stop().destroy()
            assertEquals(0, socket.closes)
            assertFalse(lease.released)
            assertNull(AirPodsTransportOwnership.process.reserve(AirPodsTransportOwnership.Owner.PRODUCTION))
        } finally { lease.close() }
        assertEquals(1, socket.closes)
        assertTrue(lease.released)
    }

    @Test fun deniedPermissionWithoutTestStartCannotReleaseAnotherOwner() {
        val lease = AirPodsTransportOwnership.process.reserve(AirPodsTransportOwnership.Owner.DIAGNOSTIC)!!
        val socket = Transport()
        lease.construct { socket }
        try {
            val activity = Robolectric.buildActivity(AirPodsDiagnosticActivity::class.java).create().start().resume()
            try {
            shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
            activity.get().onRequestPermissionsResult(104, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), intArrayOf(PackageManager.PERMISSION_DENIED))
            assertEquals(0, socket.closes)
            assertFalse(lease.released)
            assertNull(AirPodsTransportOwnership.process.reserve(AirPodsTransportOwnership.Owner.PRODUCTION))
            } finally { activity.pause().stop().destroy() }
        } finally { lease.close() }
        assertEquals(1, socket.closes)
        assertTrue(lease.released)
    }
}
