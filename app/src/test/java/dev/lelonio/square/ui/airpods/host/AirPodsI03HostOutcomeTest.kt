// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.ui.airpods.host

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import dev.lelonio.square.airpods.runtime.android.AirPodsAndroidController
import dev.lelonio.square.airpods.runtime.android.AndroidRuntimeState
import dev.lelonio.square.airpods.runtime.android.ControlChannelRecovery
import dev.lelonio.square.airpods.runtime.android.platform.*
import dev.lelonio.square.airpods.runtime.storage.PairAddress
import dev.lelonio.square.airpods.runtime.storage.PairVerification
import dev.lelonio.square.ui.airpods.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.lang.reflect.Proxy

/** PREPARED, NOT EXECUTED. Real host/controller/ordinary adapter, synthetic Android profile. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class AirPodsI03HostOutcomeTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val selected = PairAddress("11:22:33:44:55:66")

    @Before fun resetFacts() {
        org.robolectric.shadows.ShadowProcess.setPid(41303)
        context.getSharedPreferences("airpods_runtime_v1", 0).edit().clear().commit()
        shadowOf(context).denyPermissions(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS)
        shadowOf(context.getSystemService(BluetoothManager::class.java).adapter).setState(BluetoothAdapter.STATE_ON)
    }

    private class Profile(var cold: Boolean = false) : AudioProfilePort {
        var onReady: (() -> Unit)? = null
        val operations = mutableListOf<Pair<PairAddress, AudioOperation>>()
        var result: PlatformResult = PlatformResult.Denied(PlatformFailure.HIDDEN_API_UNAVAILABLE)
        override fun ready() = !cold
        override fun prepare(onReady: () -> Unit): Boolean { this.onReady = onReady; return true }
        override fun invoke(pair: PairAddress, operation: AudioOperation): PlatformResult {
            operations += pair to operation
            return result
        }
        override fun close() = Unit
    }

    private fun owner(profile: Profile = Profile()): AirPodsAndroidController {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter.getRemoteDevice(selected.value)).setBondState(BluetoothDevice.BOND_BONDED)
        return AirPodsAndroidController(context, MutableStateFlow(true), { true }, null,
            audioProfileFactory = { profile }, runtimeDispatcher = Dispatchers.Unconfined).also {
            it.store.select(selected).getOrThrow()
            it.store.recordSetup(selected, PairVerification("A3063", Build.FINGERPRINT, 1)).getOrThrow()
            it.store.transaction { data -> data.copy(start = data.start.copy(enabled = true, consentGeneration = 1)) to Unit }.getOrThrow()
        }
    }

    private fun connected(host: AirPodsHost) {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        val profile = Proxy.newProxyInstance(BluetoothProfile::class.java.classLoader,
            arrayOf(BluetoothProfile::class.java)) { proxy, method, args -> when (method.name) {
                "getConnectedDevices" -> listOf(adapter.getRemoteDevice(selected.value))
                "getConnectionState" -> BluetoothProfile.STATE_CONNECTED
                "getDevicesMatchingConnectionStates" -> emptyList<BluetoothDevice>()
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "I03 synthetic A2DP profile"
                else -> null
            } } as BluetoothProfile
        ReflectionHelpers.setField(ReflectionHelpers.getField<HostBluetooth>(host, "bluetooth"), "profile", profile)
    }

    private suspend fun close(owner: AirPodsAndroidController, host: AirPodsHost) {
        host.close()
        owner.stopControls()
        context.unregisterReceiver(ReflectionHelpers.getField(owner, "receiver"))
        ReflectionHelpers.getField<CoroutineScope>(owner, "processScope").cancel()
    }

    @Test(timeout = 10_000) fun connectAndDisconnectSettingsRequiredReachTheVisibleModelAndSettingsAction() = runBlocking {
        for (operation in AudioOperation.entries) {
            val profile = Profile()
            val owner = owner(profile)
            val host = AirPodsHost(context, owner)
            try {
                if (operation == AudioOperation.DISCONNECT) connected(host)
                host.refresh()
                val before = owner.store.snapshot().getOrThrow()
                val action = if (operation == AudioOperation.CONNECT) SetupAction.CONNECT else SetupAction.DISCONNECT
                assertTrue(host.state.value.allowed("setup.$action"))
                val launches = mutableListOf<SetupAction>()
                host.dispatch(host.stamp, AirPodsUiAction.Setup(action)) { next, _ -> launches += next }
                assertEquals(listOf(selected to operation), profile.operations)
                assertTrue(host.state.value.audioResult is PlatformResult.SettingsRequired)
                assertTrue(host.state.value.audioResult!!.presentation().offerSettings)
                assertEquals(StatusKind.Problem, host.state.value.audioResult!!.presentation().kind)
                assertTrue("Recovery is offered for an explicit tap", launches.isEmpty())
                host.dispatch(host.stamp, AirPodsUiAction.Setup(SetupAction.BLUETOOTH_SETTINGS)) { next, pair ->
                    assertEquals(selected, pair)
                    launches += next
                }
                assertEquals(listOf(SetupAction.BLUETOOTH_SETTINGS), launches)
                assertEquals(before, owner.store.snapshot().getOrThrow())
                assertFalse(owner.runtime.value.active)
            } finally { close(owner, host) }
        }
    }

    @Test(timeout = 10_000) fun queuedProfileFailurePublishesRecoveryWithoutAnotherConnectTap() = runBlocking {
        val profile = Profile(cold = true)
        val owner = owner(profile)
        val host = AirPodsHost(context, owner)
        try {
            host.refresh()
            host.dispatch(host.stamp, AirPodsUiAction.Setup(SetupAction.CONNECT)) { _, _ -> fail("No automatic launch") }
            assertEquals(PlatformResult.Queued, host.state.value.audioResult)
            profile.cold = false
            requireNotNull(profile.onReady).invoke()
            host.refresh() // same refresh used by the audioResult collector
            assertTrue(host.state.value.audioResult is PlatformResult.SettingsRequired)
            assertTrue(host.state.value.audioResult!!.presentation().offerSettings)
            assertEquals(listOf(selected to AudioOperation.CONNECT), profile.operations)
            assertFalse(owner.runtime.value.active)
        } finally { close(owner, host) }
    }

    @Test(timeout = 10_000) fun stopBeforeColdCallbackCannotLeaveRecoveryForAnOldRequest() = runBlocking {
        val profile = Profile(cold = true)
        val owner = owner(profile)
        val host = AirPodsHost(context, owner)
        try {
            host.refresh()
            host.dispatch(host.stamp, AirPodsUiAction.Setup(SetupAction.CONNECT)) { _, _ -> fail("No launch") }
            val late = requireNotNull(profile.onReady)
            host.dispatch(host.stamp, AirPodsUiAction.Setup(SetupAction.STOP)) { _, _ -> fail("No launch") }
            late()
            host.refresh()
            assertTrue(profile.operations.isEmpty())
            assertNull(host.state.value.audioResult)
            assertTrue(host.state.value.suppressed)
        } finally { close(owner, host) }
    }

    @Test(timeout = 10_000) fun settingsReturnRechecksProfileFactsAndDoesNotConfirmAnUnchangedConnection() = runBlocking {
        val profile = Profile()
        val owner = owner(profile)
        val host = AirPodsHost(context, owner)
        try {
            host.refresh()
            val before = owner.store.snapshot().getOrThrow()
            host.dispatch(host.stamp, AirPodsUiAction.Setup(SetupAction.CONNECT)) { _, _ -> fail("No automatic launch") }
            assertTrue(host.state.value.audioResult is PlatformResult.SettingsRequired)
            host.permissionsChanged() // the actual Settings ActivityResult target; no success flag is trusted
            assertNull(host.state.value.audioResult)
            assertFalse(host.state.value.profileConnected)
            assertEquals(dev.lelonio.square.R.string.airpods_feature_disconnected, host.state.value.connectionSummary())
            assertEquals(listOf(selected to AudioOperation.CONNECT), profile.operations)
            assertEquals(before, owner.store.snapshot().getOrThrow())
            assertFalse(owner.runtime.value.active)
        } finally { close(owner, host) }
    }

    @Test(timeout = 10_000) fun permissionCallbackReadsBothEffectiveGrantsAndRevocationWithoutGrantingConsent() = runBlocking {
        val owner = owner()
        val host = AirPodsHost(context, owner)
        try {
            val before = owner.store.snapshot().getOrThrow()
            suspend fun check(phone: Boolean, answer: Boolean) {
                if (phone) shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
                else shadowOf(context).denyPermissions(Manifest.permission.READ_PHONE_STATE)
                if (answer) shadowOf(context).grantPermissions(Manifest.permission.ANSWER_PHONE_CALLS)
                else shadowOf(context).denyPermissions(Manifest.permission.ANSWER_PHONE_CALLS)
                host.permissionsChanged() // actual ActivityResult callback target
                assertEquals(CallGestureAccess(phone, answer), host.state.value.callAccess)
                assertEquals(!(phone && answer), host.state.value.allowed("setup.CALL_PERMISSION"))
                assertEquals(before, owner.store.snapshot().getOrThrow())
                assertFalse(owner.runtime.value.active)
            }
            check(false, false)
            check(true, false)
            check(false, true)
            check(true, true)
            check(true, false)
        } finally { close(owner, host) }
    }

    @Test(timeout = 10_000) fun refreshMapsI01RecoveryAfterClosedRepositoryAndRetainsPriority() = runBlocking {
        val owner = owner()
        val host = AirPodsHost(context, owner)
        try {
            connected(host)
            val runtime = ReflectionHelpers.getField<MutableStateFlow<AndroidRuntimeState>>(owner, "mutableRuntime")
            for (recovery in listOf(ControlChannelRecovery.WAITING, ControlChannelRecovery.RECONNECTING)) {
                runtime.value = runtime.value.copy(controlRecovery = recovery)
                host.refresh()
                assertEquals(recovery, host.state.value.controlRecovery)
                assertEquals(if (recovery == ControlChannelRecovery.WAITING) StatusKind.Problem else StatusKind.Waiting,
                    host.state.value.connectionKind())
            }
            owner.stopControls()
            host.refresh()
            assertTrue(host.state.value.suppressed)
            assertEquals(dev.lelonio.square.R.string.airpods_feature_stopped, host.state.value.connectionSummary())
        } finally { close(owner, host) }
    }
}
