// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.android

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Intent
import dev.lelonio.square.airpods.runtime.storage.PairAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowProcess
import org.robolectric.util.ReflectionHelpers

/** Actual controller read boundary, synthetic denied Bluetooth facts.
 * No entitlement, admission, service, socket, transport, device or permission grant. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
class I5LintBondedPermissionTest {
    @Before fun processIdentity() { ShadowProcess.setPid(34121) }

    private class DeniedFacts(var allowed: Boolean) : BluetoothRuntimeFacts {
        var adapterReads = 0
        override val adapter: BluetoothAdapter?
            get() { adapterReads++; throw SecurityException("synthetic permission revocation") }
        override fun permitted() = allowed
        override fun bond(address: PairAddress) = BondFact.UNKNOWN
        override fun connection(address: PairAddress) = ProfileConnection.UNRESOLVED
        override fun prepare() = Unit
        override fun whenProfileReady(ready: () -> Unit): AutoCloseable? = null
        override fun audioSettingsIntent() = Intent()
    }

    private fun withController(facts: DeniedFacts, check: suspend (AirPodsAndroidController) -> Unit) = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val owner = AirPodsAndroidController(app, MutableStateFlow(false), { false }, null,
            factsOverride = facts, runtimeDispatcher = Dispatchers.Unconfined)
        try {
            check(owner)
            assertFalse(owner.hasAdmission())
            assertNull(org.robolectric.Shadows.shadowOf(app).nextStartedService)
        } finally {
            // Dispose this fixture's process observer without invoking acquisition or production gates.
            ReflectionHelpers.getField<CoroutineScope>(owner, "processScope").cancel()
            app.unregisterReceiver(ReflectionHelpers.getField<BroadcastReceiver>(owner, "receiver"))
        }
    }

    @Test fun deniedPermissionDoesNotReadAdapterOrAcquireAnything() {
        val facts = DeniedFacts(false)
        withController(facts) { owner ->
            assertTrue(owner.bondedPairs().isEmpty())
            assertEquals(0, facts.adapterReads)
        }
    }

    @Test fun revocationAfterPermissionFactReturnsEmptyWithoutAcquisition() {
        val facts = DeniedFacts(true)
        withController(facts) { owner ->
            assertTrue(owner.bondedPairs().isEmpty())
            assertEquals(1, facts.adapterReads)
        }
    }
}
