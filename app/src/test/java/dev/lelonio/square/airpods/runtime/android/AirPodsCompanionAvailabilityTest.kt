// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.android

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import dev.lelonio.square.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Resolve the real manifest and versioned resource without loading the API31 service on API28. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28, 31, 35])
class AirPodsCompanionAvailabilityTest {
    @Test fun companionServiceIsAvailableOnlyOnSupportedAndroidVersions() {
        val app = RuntimeEnvironment.getApplication()
        val component = ComponentName(app.packageName,
            "dev.lelonio.square.airpods.runtime.android.AirPodsCompanionService")
        @Suppress("DEPRECATION")
        val service = app.packageManager.getServiceInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS)
        val supported = Build.VERSION.SDK_INT >= 31
        assertEquals(supported, app.resources.getBoolean(R.bool.airpods_companion_service_enabled))
        assertEquals(supported, service.enabled)
        assertTrue(service.exported)
        assertEquals("android.permission.BIND_COMPANION_DEVICE_SERVICE", service.permission)
    }
}
