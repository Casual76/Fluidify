// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.media

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import dev.lelonio.square.airpods.runtime.automation.AutomationSession
import dev.lelonio.square.airpods.runtime.storage.HostSettings
import dev.lelonio.square.airpods.runtime.storage.PairAddress
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35],
    shadows = [C9ProfileRequestShadow::class])
class C9ExternalProfileObservationTest {
    private val settings = HostSettings(automationsEnabled = true, otherAppsEnabled = true,
        earPause = true, earResume = true)
    private fun rig(value: HostSettings = settings): Pair<AndroidAirPodsMediaRuntime, C9ProfileRequestShadow> {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val adapter = app.getSystemService(BluetoothManager::class.java).adapter
        val shadow = Shadow.extract<C9ProfileRequestShadow>(adapter)
        shadow.requests = 0
        val port = AndroidAirPodsMediaRuntime(app)
        port.start(AutomationSession("profile-owner", 1), PairAddress("00:11:22:33:44:55"), value)
        return port to shadow
    }
    @Test fun foregroundOnlyRequestsProfileAfterStartThenVisible() {
        val (port, shadow) = rig()
        try {
            assertEquals(0, shadow.requests)
            port.setUiVisible(true)
            assertEquals(1, shadow.requests)
        } finally { port.stop() }
    }
    @Test fun backgroundConsentAcquiresOnceAtStart() {
        val (port, shadow) = rig(settings.copy(fluidifyBackgroundEnabled = true))
        try {
            assertEquals(1, shadow.requests)
            port.setUiVisible(true)
            assertEquals(1, shadow.requests)
        } finally { port.stop() }
    }
    @Test fun visibilityChangesAndIdenticalSettingsReuseTheSameObservation() {
        val (port, shadow) = rig()
        try {
            port.setUiVisible(true)
            port.setUiVisible(false)
            port.setUiVisible(true)
            port.updateSettings(settings)
            assertEquals(1, shadow.requests)
        } finally { port.stop() }
    }
    @Test fun externalOptOutDoesNotAcquireEvenWhenVisibleOrBackgroundAllowed() {
        val (port, shadow) = rig(settings.copy(otherAppsEnabled = false, fluidifyBackgroundEnabled = true))
        try {
            port.setUiVisible(true)
            assertEquals(0, shadow.requests)
        } finally { port.stop() }
    }
    @Test fun revocationBeforeForegroundDoesNotAcquire() {
        val (port, shadow) = rig()
        port.revoke()
        port.setUiVisible(true)
        port.stop()
        assertEquals(0, shadow.requests)
    }
    @Test fun explicitExternalConsentChangeWhileVisibleAcquiresOneProfile() {
        val (port, shadow) = rig(settings.copy(otherAppsEnabled = false))
        try {
            port.setUiVisible(true)
            assertEquals(0, shadow.requests)
            port.updateSettings(settings)
            port.updateSettings(settings)
            assertEquals(1, shadow.requests)
        } finally { port.stop() }
    }
}
