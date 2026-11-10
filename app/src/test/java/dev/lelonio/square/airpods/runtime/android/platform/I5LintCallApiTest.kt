// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.android.platform

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** PREPARED, NOT EXECUTED. Direct production port on API26/27; no Telecom service or call. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [26, 27])
class I5LintCallApiTest {
    private class NoServiceContext(base: Context, private val granted: Boolean) : ContextWrapper(base) {
        var serviceReads = 0
        override fun checkSelfPermission(permission: String) =
            if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        override fun getSystemService(name: String): Any? {
            serviceReads++
            error("Unsupported rejection must not query or dispatch Telecom")
        }
    }

    @Test fun rejectionBelowApi28ReturnsUnavailableBeforeAnyPlatformServiceRead() {
        val context = NoServiceContext(RuntimeEnvironment.getApplication(), true)
        assertEquals(PlatformResult.Denied(PlatformFailure.PLATFORM_UNAVAILABLE),
            AndroidOrdinaryCallPort(context).dispatch(false))
        assertEquals(0, context.serviceReads)
    }

    @Test fun permissionDenialStillPrecedesUnsupportedRejectionWithoutPlatformAccess() {
        val context = NoServiceContext(RuntimeEnvironment.getApplication(), false)
        assertEquals(PlatformResult.Denied(PlatformFailure.PERMISSION),
            AndroidOrdinaryCallPort(context).dispatch(false))
        assertEquals(0, context.serviceReads)
    }
}
