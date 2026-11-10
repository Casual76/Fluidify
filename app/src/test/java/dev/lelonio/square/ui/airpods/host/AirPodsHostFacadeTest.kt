package dev.lelonio.square.ui.airpods.host

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.lelonio.square.airpods.runtime.android.AirPodsAndroidController
import dev.lelonio.square.airpods.runtime.storage.*
import dev.lelonio.square.airpods.runtime.lifecycle.*
import dev.lelonio.square.ui.airpods.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30], application=Application::class)
class AirPodsHostFacadeTest {
    @org.junit.Before fun validProcessIdentity() { org.robolectric.shadows.ShadowProcess.setPid(41003) }
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    @Test fun absentInstallerIsExplicitAndInert() = runBlocking {
        val host = AirPodsHost(context, null)
        host.refresh()
        assertFalse(host.state.value.homeVisible); assertFalse(host.state.value.playerVisible)
        assertNull(host.stamp); assertTrue(host.state.value.diagnostics.isNotEmpty())
        host.dispatch(null, AirPodsUiAction.Setup(SetupAction.ENABLE)) { _, _ -> fail("No platform launch") }
        host.close()
    }
    @Test fun permissionResultCannotGrantConsentOrEnable() = runBlocking {
        val owner = AirPodsAndroidController(context, MutableStateFlow(false), { false }, null)
        val before = owner.store.snapshot().getOrThrow()
        val host = AirPodsHost(context, owner)
        host.permissionsChanged()
        val after = owner.store.snapshot().getOrThrow()
        assertEquals(before.start.consentGeneration, after.start.consentGeneration)
        assertFalse(after.start.enabled); assertNull(after.selectedPair)
        assertFalse(owner.runtime.value.active); host.close()
    }
    @Test fun dispatcherRejectsStaleClosureBeforePlatformLauncher() = runBlocking {
        val owner = AirPodsAndroidController(context, MutableStateFlow(false), { false }, null)
        val a = PairAddress("00:11:22:33:44:55")
        val b = PairAddress("00:11:22:33:44:66")
        owner.store.select(a).getOrThrow()
        val host = AirPodsHost(context, owner)
        host.refresh(); val old = host.stamp
        owner.store.select(b).getOrThrow()
        host.dispatch(old, AirPodsUiAction.Setup(SetupAction.BLUETOOTH_SETTINGS)) { _, _ -> fail("Stale launcher") }
        assertEquals(b, owner.store.snapshot().getOrThrow().selectedPair)
        assertFalse(owner.runtime.value.active); host.close()
    }
    @Test(timeout = 10_000) fun missingNlsAndMissingMediaHaveDistinctRealHostReasons() = runBlocking {
        val owner = consentOwner(); val host = AirPodsHost(context, owner)
        try {
            android.provider.Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners", "")
            host.refresh()
            assertEquals(context.getString(dev.lelonio.square.R.string.airpods_reason_nls),
                host.state.value.availability.getValue("local.OTHER_APPS.enable").reason)
            android.provider.Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners",
                "${context.packageName}/dev.lelonio.square.airpods.runtime.media.AirPodsMediaNotificationListener")
            host.refresh()
            assertEquals(context.getString(dev.lelonio.square.R.string.airpods_reason_media),
                host.state.value.availability.getValue("local.OTHER_APPS.enable").reason)
            host.dispatch(host.stamp, AirPodsUiAction.Toggle(LocalToggle.OTHER_APPS, true)) { _, _ -> fail("No launch") }
            assertTrue(host.state.value.diagnostics.contains(context.getString(dev.lelonio.square.R.string.airpods_reason_media)))
            assertTrue(host.state.value.allowed("local.OTHER_APPS.disable"))
            assertNull(host.state.value.availability.getValue("local.OTHER_APPS").reason)
        } finally {
            android.provider.Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners", "")
            close(owner, host)
        }
    }
    @Test(timeout = 10_000) fun realHostReportsSelectionSetupBluetoothAndProductGate() = runBlocking {
        val owner = consentOwner(); val host = AirPodsHost(context, owner)
        val adapter = context.getSystemService(android.bluetooth.BluetoothManager::class.java).adapter
        try {
            shadowOf(adapter).setState(android.bluetooth.BluetoothAdapter.STATE_OFF)
            host.refresh()
            assertEquals(context.getString(dev.lelonio.square.R.string.airpods_reason_bluetooth_off),
                host.state.value.availability.getValue("setup.ENABLE").reason)
            shadowOf(adapter).setState(android.bluetooth.BluetoothAdapter.STATE_ON)
            owner.store.transaction { it.copy(pairs = it.pairs + (selected to it.pairs.getValue(selected).copy(verification = null))) to Unit }.getOrThrow()
            host.refresh()
            assertEquals(context.getString(dev.lelonio.square.R.string.airpods_reason_setup),
                host.state.value.availability.getValue("setup.ENABLE").reason)
            owner.store.recordSetup(selected, PairVerification("A3063",android.os.Build.FINGERPRINT,1)).getOrThrow()
            val gatedOwner = AirPodsAndroidController(context, MutableStateFlow(false), { false }, null)
            val gated = AirPodsHost(context,gatedOwner)
            try {
                gated.refresh()
                assertEquals(context.getString(dev.lelonio.square.R.string.airpods_reason_gate),
                    gated.state.value.availability.getValue("setup.ENABLE").reason)
                gated.dispatch(gated.stamp,AirPodsUiAction.Toggle(LocalToggle.AUTOMATIONS,false)) { _,_-> fail("No launch") }
                assertFalse(gatedOwner.store.snapshot().getOrThrow().pairs.getValue(selected).host.automationsEnabled)
            } finally { close(gatedOwner,gated) }
        } finally { close(owner,host) }
    }
    @Test(timeout = 10_000) fun companionAbsenceQueryFailureAndExistingAssociationAreDistinct() = runBlocking {
        val owner = consentOwner()
        val registry = object : dev.lelonio.square.airpods.runtime.lifecycle.CompanionRegistryFacade {
            var feature = false
            var failQuery = false
            var existing = false
            override fun hasFeature() = feature
            override fun hasObservePermission() = false
            override fun isBonded(address: PairAddress) = true
            override fun ownAssociations(): List<dev.lelonio.square.airpods.runtime.lifecycle.OwnedAssociation> {
                if (failQuery) error("synthetic query failure")
                return if(existing) listOf(dev.lelonio.square.airpods.runtime.lifecycle.OwnedAssociation(1,selected,context.packageName)) else emptyList()
            }
            override fun start(registration: dev.lelonio.square.airpods.runtime.lifecycle.ObservationRegistration): Boolean = error("read only")
            override fun stop(registration: dev.lelonio.square.airpods.runtime.lifecycle.ObservationRegistration): Boolean = error("read only")
            override fun disassociate(association: dev.lelonio.square.airpods.runtime.lifecycle.OwnedAssociation): Boolean = error("read only")
        }
        val host = AirPodsHost(context,owner,registry)
        try {
            suspend fun reason(id: Int) {
                host.refresh()
                assertEquals(context.getString(id),host.state.value.availability.getValue("setup.COMPANION_ASSOCIATION").reason)
            }
            reason(dev.lelonio.square.R.string.airpods_reason_companion_feature)
            registry.feature=true; registry.failQuery=true
            reason(dev.lelonio.square.R.string.airpods_reason_companion_query)
            registry.failQuery=false; registry.existing=true
            reason(dev.lelonio.square.R.string.airpods_reason_companion_present)
            registry.existing=false; host.refresh()
            assertTrue(host.state.value.allowed("setup.COMPANION_ASSOCIATION"))
            assertNull(host.state.value.availability.getValue("setup.COMPANION_ASSOCIATION").reason)
        } finally { close(owner,host) }
    }
    @Config(sdk=[35])
    @Test(timeout = 10_000) fun realHostConnectionNotificationsAndBluetoothReasonsFollowCurrentFacts() = runBlocking {
        shadowOf(context).grantPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(context).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val owner=consentOwner(); val host=AirPodsHost(context,owner)
        val adapter=context.getSystemService(android.bluetooth.BluetoothManager::class.java).adapter
        shadowOf(adapter).setState(android.bluetooth.BluetoothAdapter.STATE_ON)
        try {
            host.refresh()
            assertEquals(context.getString(dev.lelonio.square.R.string.airpods_reason_connection),
                host.state.value.availability.getValue("setup.ENABLE").reason)
            val profile = java.lang.reflect.Proxy.newProxyInstance(android.bluetooth.BluetoothProfile::class.java.classLoader,
                arrayOf(android.bluetooth.BluetoothProfile::class.java)) { proxyObject, method, arguments -> when(method.name) {
                    "getConnectedDevices" -> listOf(adapter.getRemoteDevice(selected.value))
                    "getConnectionState" -> android.bluetooth.BluetoothProfile.STATE_CONNECTED
                    "getDevicesMatchingConnectionStates" -> emptyList<android.bluetooth.BluetoothDevice>()
                    "getAdapter" -> adapter
                    "hashCode" -> System.identityHashCode(proxyObject)
                    "equals" -> proxyObject === arguments?.firstOrNull()
                    "toString" -> "Synthetic A2DP profile"
                    else -> null
                } } as android.bluetooth.BluetoothProfile
            ReflectionHelpers.setField(ReflectionHelpers.getField<HostBluetooth>(host,"bluetooth"),"profile",profile)
            host.refresh()
            assertEquals(context.getString(dev.lelonio.square.R.string.airpods_reason_notifications),
                host.state.value.availability.getValue("setup.ENABLE").reason)
            assertTrue(host.state.value.allowed("setup.NOTIFICATION_PERMISSION"))
            assertNull(host.state.value.availability.getValue("setup.NOTIFICATION_PERMISSION").reason)
            shadowOf(context).denyPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
            host.refresh()
            assertEquals(context.getString(dev.lelonio.square.R.string.airpods_reason_bluetooth_permission),
                host.state.value.availability.getValue("setup.ENABLE").reason)
            host.dispatch(host.stamp,AirPodsUiAction.Toggle(LocalToggle.BACKGROUND,false)) { _,_-> fail("No launch") }
            assertFalse(owner.store.snapshot().getOrThrow().pairs.getValue(selected).host.fluidifyBackgroundEnabled)
        } finally { close(owner,host) }
    }
    private suspend fun close(owner: AirPodsAndroidController, host: AirPodsHost) {
        host.close(); owner.stopControls()
        context.unregisterReceiver(ReflectionHelpers.getField(owner, "receiver"))
        ReflectionHelpers.getField<CoroutineScope>(owner, "processScope").cancel()
    }
    private val selected = PairAddress("00:11:22:33:44:55")
    private fun cleanupRegistry(query: () -> List<OwnedAssociation>) = object : CompanionRegistryFacade {
        override fun hasFeature() = true
        override fun hasObservePermission() = true
        override fun isBonded(address: PairAddress) = true
        override fun ownAssociations() = query()
        override fun start(registration: ObservationRegistration): Boolean = error("Retry cannot observe")
        override fun stop(registration: ObservationRegistration) = true
        override fun disassociate(association: OwnedAssociation): Boolean = error("Original is already absent")
    }
    @Test(timeout = 10_000) fun pendingCleanupWithoutSelectedPairHasExplicitRetryAndConcreteFailure() = runBlocking {
        var queryFails = true
        val owner = AirPodsAndroidController(context, MutableStateFlow(false), { false }, null,
            registryOverride = cleanupRegistry { if (queryFails) error("synthetic CDM query failure") else emptyList() })
        val host = AirPodsHost(context, owner)
        val target = PendingCompanionCleanup("original-cleanup", selected, null, context.packageName, null)
        try {
            owner.store.transaction { it.copy(selectedPair = null, pairs = emptyMap(), pendingCleanup = listOf(target)) to Unit }.getOrThrow()
            host.refresh()
            assertEquals(1, host.state.value.pendingCleanupCount)
            assertTrue(host.state.value.allowed("setup.RETRY_CLEANUP"))
            assertFalse(host.state.value.allowed("setup.FORGET"))
            host.dispatch(host.stamp, AirPodsUiAction.Setup(SetupAction.RETRY_CLEANUP)) { _, _ -> fail("No platform launcher") }
            assertEquals(listOf(target), owner.store.snapshot().getOrThrow().pendingCleanup)
            assertTrue(host.state.value.diagnostics.contains(context.getString(dev.lelonio.square.R.string.airpods_cleanup_platform)))
            assertFalse(owner.runtime.value.active)
            queryFails = false
            host.dispatch(host.stamp, AirPodsUiAction.Setup(SetupAction.RETRY_CLEANUP)) { _, _ -> fail("No platform launcher") }
            assertTrue(owner.store.snapshot().getOrThrow().pendingCleanup.isEmpty())
            assertEquals(0, host.state.value.pendingCleanupCount)
            assertFalse(host.state.value.allowed("setup.RETRY_CLEANUP"))
            assertNull(owner.store.snapshot().getOrThrow().selectedPair)
            assertFalse(owner.runtime.value.active)
        } finally { close(owner, host) }
    }
    @Test(timeout = 10_000) fun cleanupRetryRejectsStaleSelectionAndFreshRetryPreservesNewPair() = runBlocking {
        val owner = AirPodsAndroidController(context, MutableStateFlow(false), { false }, null,
            registryOverride = cleanupRegistry { emptyList() })
        val host = AirPodsHost(context, owner)
        val target = PendingCompanionCleanup("original-cleanup", selected, null, context.packageName, null)
        val other = PairAddress("00:11:22:33:44:66")
        shadowOf(context.getSystemService(android.bluetooth.BluetoothManager::class.java).adapter.getRemoteDevice(other.value))
            .setBondState(android.bluetooth.BluetoothDevice.BOND_BONDED)
        try {
            owner.store.transaction { it.copy(selectedPair = null, pairs = emptyMap(), pendingCleanup = listOf(target)) to Unit }.getOrThrow()
            host.refresh(); val old = host.stamp
            owner.store.select(other).getOrThrow()
            owner.store.configure(other) { it.copy(host = it.host.copy(otherAppsEnabled = true)) }.getOrThrow()
            host.dispatch(old, AirPodsUiAction.Setup(SetupAction.RETRY_CLEANUP)) { _, _ -> fail("No platform launcher") }
            assertEquals(listOf(target), owner.store.snapshot().getOrThrow().pendingCleanup)
            host.dispatch(host.stamp, AirPodsUiAction.Setup(SetupAction.RETRY_CLEANUP)) { _, _ -> fail("No platform launcher") }
            val data = owner.store.snapshot().getOrThrow()
            assertTrue(data.pendingCleanup.isEmpty())
            assertEquals(other, data.selectedPair)
            assertTrue(data.pairs.getValue(other).host.otherAppsEnabled)
            assertFalse(owner.runtime.value.active)
        } finally { close(owner, host) }
    }
    private fun consentOwner(): AirPodsAndroidController {
        val device = context.getSystemService(android.bluetooth.BluetoothManager::class.java).adapter.getRemoteDevice(selected.value)
        shadowOf(device).setBondState(android.bluetooth.BluetoothDevice.BOND_BONDED)
        return AirPodsAndroidController(context, MutableStateFlow(false), { true }, null).also { owner ->
            owner.store.select(selected).getOrThrow()
            owner.store.recordSetup(selected, PairVerification("A3063", android.os.Build.FINGERPRINT, 1)).getOrThrow()
            owner.store.configure(selected) { it.copy(host = it.host.copy(
                automationsEnabled = true, otherAppsEnabled = true, fluidifyBackgroundEnabled = true,
            )) }.getOrThrow()
        }
    }
    @Test(timeout = 10_000) fun storedOtherAppsConsentCanBeRevokedWithoutNlsOrMediaAdapter() = runBlocking {
        val owner = consentOwner(); val host = AirPodsHost(context, owner)
        try {
            host.refresh()
            assertFalse(owner.runtime.value.mediaAvailable)
            assertFalse(host.state.value.allowed("local.OTHER_APPS.enable"))
            assertTrue(host.state.value.allowed("local.OTHER_APPS"))
            host.dispatch(host.stamp, AirPodsUiAction.Toggle(LocalToggle.OTHER_APPS, false)) { _, _ -> fail("No platform launch") }
            assertFalse(owner.store.snapshot().getOrThrow().pairs.getValue(selected).host.otherAppsEnabled)
            host.dispatch(host.stamp, AirPodsUiAction.Toggle(LocalToggle.OTHER_APPS, true)) { _, _ -> fail("No platform launch") }
            assertFalse(owner.store.snapshot().getOrThrow().pairs.getValue(selected).host.otherAppsEnabled)
            assertFalse(owner.runtime.value.active)
        } finally { close(owner, host) }
    }
    @Config(sdk = [35])
    @Test(timeout = 10_000) fun bluetoothRevocationCannotTrapBackgroundAndAutomationConsentOn() = runBlocking {
        shadowOf(context).grantPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
        val owner = consentOwner(); val host = AirPodsHost(context, owner)
        try {
            host.refresh()
            shadowOf(context).denyPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
            host.refresh()
            assertFalse(host.state.value.bluetoothGranted)
            for (toggle in listOf(LocalToggle.BACKGROUND, LocalToggle.AUTOMATIONS, LocalToggle.OTHER_APPS)) {
                assertTrue(host.state.value.allowed("local.$toggle"))
                host.dispatch(host.stamp, AirPodsUiAction.Toggle(toggle, false)) { _, _ -> fail("No platform launch") }
            }
            val h = owner.store.snapshot().getOrThrow().pairs.getValue(selected).host
            assertFalse(h.fluidifyBackgroundEnabled); assertFalse(h.automationsEnabled); assertFalse(h.otherAppsEnabled)
            assertFalse(owner.runtime.value.active)
        } finally { close(owner, host) }
    }
    @Test(timeout = 10_000) fun delayedOffCannotRevokeAnotherSelectedPair() = runBlocking {
        val owner = consentOwner(); val host = AirPodsHost(context, owner)
        val other = PairAddress("00:11:22:33:44:66")
        try {
            host.refresh(); val old = host.stamp
            shadowOf(context.getSystemService(android.bluetooth.BluetoothManager::class.java).adapter.getRemoteDevice(other.value))
                .setBondState(android.bluetooth.BluetoothDevice.BOND_BONDED)
            owner.store.select(other).getOrThrow()
            owner.store.configure(other) { it.copy(host = it.host.copy(otherAppsEnabled = true)) }.getOrThrow()
            host.dispatch(old, AirPodsUiAction.Toggle(LocalToggle.OTHER_APPS, false)) { _, _ -> fail("No platform launch") }
            val d = owner.store.snapshot().getOrThrow()
            assertTrue(d.pairs.getValue(selected).host.otherAppsEnabled)
            assertTrue(d.pairs.getValue(other).host.otherAppsEnabled)
            assertFalse(owner.runtime.value.active)
        } finally { close(owner, host) }
    }
}
