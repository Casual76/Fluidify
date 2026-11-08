// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.ui.airpods.host

import android.Manifest
import android.app.Application
import android.bluetooth.*
import android.content.*
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.antigravity.fluidengine.ui.fluid.*
import dev.lelonio.square.R
import dev.lelonio.square.airpods.*
import dev.lelonio.square.airpods.protocol.*
import dev.lelonio.square.airpods.runtime.android.*
import dev.lelonio.square.airpods.runtime.lifecycle.*
import dev.lelonio.square.airpods.runtime.storage.*
import dev.lelonio.square.ui.airpods.*
import dev.lelonio.square.ui.theme.SquareTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

/** PREPARED, NOT EXECUTED. Actual controller selector -> host -> real page/portal.
 * Admission/runtime/repository observations injected; no gate admission, FGS, transport or hardware proof.
 * Action sink measures UI dispatch; blocked host dispatch also rechecks the actual host state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w600dp-h960dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AirPodsI4AvailabilityTest {
    @get:Rule val compose = createComposeRule()
    private class Fixture {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val pair = PairAddress("00:11:22:33:44:55")
        val visible = MutableStateFlow(true)
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        val facts = object : BluetoothRuntimeFacts {
            override val adapter: BluetoothAdapter? = null
            override fun permitted() = true
            override fun bond(address: PairAddress) = BondFact.BONDED
            override fun connection(address: PairAddress) = ProfileConnection.CONNECTED
            override fun prepare() = Unit
            override fun whenProfileReady(ready: () -> Unit): AutoCloseable? = null
            override fun audioSettingsIntent() = Intent()
        }
        val owner: AirPodsAndroidController
        val host: AirPodsHost
        val domain: MutableStateFlow<AirPodsState>
        val actions = mutableListOf<AirPodsUiAction>()
        init {
            org.robolectric.shadows.ShadowProcess.setPid(41994)
            context.getSharedPreferences("airpods_runtime_v1", Context.MODE_PRIVATE).edit().clear().commit()
            shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
            shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
            owner = AirPodsAndroidController(context, visible, { true }, null, factsOverride = facts,
                runtimeDispatcher = Dispatchers.Unconfined)
            owner.store.select(pair).getOrThrow()
            owner.store.recordSetup(pair, PairVerification("A3063", Build.FINGERPRINT, 1)).getOrThrow()
            owner.store.transaction { it.copy(start = StartState(enabled = true, consentGeneration = 7)) to Unit }.getOrThrow()
            preferences(head = true, automations = true, background = false)
            ReflectionHelpers.setField(owner, "ticket", ConnectionAdmission(pair, "synthetic-admission", true, true, true,
                true, Build.FINGERPRINT, 7, productConformance = true))
            ReflectionHelpers.getField<MutableStateFlow<AndroidRuntimeState>>(owner, "mutableRuntime").value =
                AndroidRuntimeState(active = true, setupVerified = true, productConformance = true)
            val repository = ReflectionHelpers.getField<AirPodsRepository>(owner, "repository")
            domain = ReflectionHelpers.getField(repository, "mutable")
            val identity = AirPodsIdentity("A3063", "Apple Inc.", "synthetic")
            domain.value = AirPodsState(generation = 7, stage = AacpStage.SUBSCRIBED,
                identity = identity, identityReceived = Received(identity, 1, 7), activePacketFormat = HeadPacketFormat.STANDARD)
            host = AirPodsHost(context, owner)
            val bluetooth = ReflectionHelpers.getField<HostBluetooth>(host, "bluetooth")
            val profile = java.lang.reflect.Proxy.newProxyInstance(BluetoothProfile::class.java.classLoader,
                arrayOf(BluetoothProfile::class.java)) { proxy, method, args -> when (method.name) {
                    "getConnectedDevices" -> listOf(adapter.getRemoteDevice(pair.value))
                    "getConnectionState" -> BluetoothProfile.STATE_CONNECTED
                    "getDevicesMatchingConnectionStates" -> emptyList<BluetoothDevice>()
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> null
                } } as BluetoothProfile
            ReflectionHelpers.setField(bluetooth, "profile", profile)
            ReflectionHelpers.setField(bluetooth, "requested", true)
        }
        fun preferences(head: Boolean, automations: Boolean, background: Boolean) {
            owner.store.configure(pair) { it.copy(host = it.host.copy(headGestures = head,
                automationsEnabled = automations, fluidifyBackgroundEnabled = background)) }.getOrThrow()
        }
        suspend fun close() {
            ReflectionHelpers.setField(ReflectionHelpers.getField<HostBluetooth>(host, "bluetooth"), "profile", null)
            host.close(); owner.stopControls()
            context.unregisterReceiver(ReflectionHelpers.getField(owner, "receiver"))
            ReflectionHelpers.getField<CoroutineScope>(owner, "processScope").cancel()
        }
        fun text(id: Int) = context.getString(id)
    }
    private fun mount(f: Fixture, quick: Boolean = false, font: Float = 1f) {
        compose.setContent {
            val state by f.host.state.collectAsState()
            val backdrop = rememberGlassBackdrop()
            val modal = rememberFluidGlassModalHostState()
            val density = LocalDensity.current
            SquareTheme(darkTheme = false, amoled = false, systemBars = false) {
                CompositionLocalProvider(LocalDensity provides Density(density.density, font),
                    LocalGlassBackdrop provides backdrop,
                    LocalFluidGlassModalHostState provides modal,
                    LocalFluidMotionPolicy provides FluidMotionPolicy(0f)) {
                    Box(Modifier.requiredSize(360.dp, 640.dp)) {
                        if (quick) AirPodsQuickOverlay(state, true, {}, f.actions::add, {})
                        else AirPodsSettingsContent(state, f.actions::add, Modifier.fillMaxSize())
                        FluidGlassModalHost(modal, backdrop = backdrop)
                    }
                }
            }
        }
    }
    private fun reason(key: String, text: String) {
        compose.onNodeWithTag("airpods-reason-$key").performScrollTo().assertTextEquals(text).assertIsDisplayed()
    }
    @Test fun trackingSharedSelectorAndRealPageFollowPreferencesAndForegroundWithoutDispatch() = runBlocking {
        val f = Fixture()
        try {
            f.preferences(head = true, automations = false, background = false)
            f.host.refresh(); mount(f)
            assertEquals(TrackingBlockReason.AUTOMATIONS_DISABLED, f.owner.trackingUnavailableReason(true))
            reason("tracking.start", f.text(R.string.airpods_reason_automations_off))
            compose.onNodeWithTag("airpods-tracking-start").performScrollTo().assertIsNotEnabled().performTouchInput { click() }
            assertTrue(f.actions.isEmpty())
            f.host.dispatch(f.host.stamp, AirPodsUiAction.Tracking(true)) { _, _ -> fail("No platform launch") }
            assertEquals(HeadPacketFormat.STANDARD, f.domain.value.activePacketFormat)
            f.preferences(head = false, automations = true, background = false); f.host.refresh()
            assertEquals(TrackingBlockReason.HEAD_GESTURES_DISABLED, f.owner.trackingUnavailableReason(true))
            assertNull(f.owner.trackingUnavailableReason(false))
            reason("tracking.start", f.text(R.string.airpods_reason_head_preference))
            compose.onNodeWithTag("airpods-reason-tracking.stop").assertDoesNotExist()
            compose.onNodeWithTag("airpods-tracking-stop").performScrollTo().assertIsEnabled().performClick()
            assertEquals(listOf(AirPodsUiAction.Tracking(false)), f.actions)
            f.preferences(head = true, automations = true, background = false); f.host.refresh()
            assertNull(f.owner.trackingUnavailableReason(true)) // foreground ON, BG missing
            compose.onNodeWithTag("airpods-tracking-start").performScrollTo().assertIsEnabled()
            compose.onNodeWithTag("airpods-reason-tracking.start").assertDoesNotExist()
            f.visible.value = false; f.host.refresh()
            reason("tracking.start", f.text(R.string.airpods_reason_tracking_background))
            assertFalse(f.host.state.value.allowed("tracking.start"))
            assertTrue(f.host.state.value.allowed("tracking.stop"))
            // OFF revocation uses production host dispatch, even while Start is unavailable.
            f.host.dispatch(f.host.stamp, AirPodsUiAction.Toggle(LocalToggle.HEAD_GESTURES, false)) { _, _ -> fail("No launcher") }
            assertFalse(f.owner.store.snapshot().getOrThrow().pairs.getValue(f.pair).host.headGestures)
        } finally { f.close() }
    }
    @Test fun realPageNumericAndScalarReasonsChangeWithOwnerPermissionAndDisappear() = runBlocking {
        val f = Fixture()
        try {
            shadowOf(f.context).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
            f.host.refresh(); mount(f)
            reason("property.CHIME_VOLUME", f.text(R.string.airpods_reason_bluetooth_permission))
            reason("property.MIC_MODE", f.text(R.string.airpods_reason_bluetooth_permission))
            reason("tracking.start", f.text(R.string.airpods_reason_bluetooth_permission))
            reason("local.preferredMode", f.text(R.string.airpods_reason_bluetooth_permission))
            assertTrue(f.actions.isEmpty())
            shadowOf(f.context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
            f.domain.value = f.domain.value.copy(stage = AacpStage.CLOSED); f.host.refresh()
            reason("property.CHIME_VOLUME", f.text(R.string.airpods_reason_session))
            f.domain.value = f.domain.value.copy(stage = AacpStage.SUBSCRIBED); f.host.refresh()
            compose.onNodeWithTag("airpods-reason-property.CHIME_VOLUME").assertDoesNotExist()
            assertNull(f.domain.value.propertyObserved(CommandProperty.CHIME_VOLUME))
            assertEquals(PropertyStatus.CANDIDATE, f.domain.value.propertyView(CommandProperty.CHIME_VOLUME)?.status)
            assertTrue(f.actions.isEmpty())
        } finally { f.close() }
    }
    @Test fun quickPreferredReasonAndFooterStayReachableAndReasonClears() = runBlocking<Unit> {
        val f = Fixture()
        try {
            shadowOf(f.context).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
            f.host.refresh(); mount(f, quick = true, font = 2f)
            reason("local.preferredMode", f.text(R.string.airpods_reason_bluetooth_permission))
            compose.onNodeWithTag("airpods-preferred-mode").performScrollTo().assertIsNotEnabled().performTouchInput { click() }
            assertTrue(f.actions.isEmpty())
            compose.onNodeWithTag("airpods-all-settings").assertIsDisplayed().assertIsEnabled()
            shadowOf(f.context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT); f.host.refresh()
            compose.onNodeWithTag("airpods-reason-local.preferredMode").assertDoesNotExist()
            compose.onNodeWithTag("airpods-preferred-mode").performScrollTo().assertIsEnabled()
            compose.onNodeWithTag("airpods-all-settings").assertIsDisplayed()
        } finally { f.close() }
    }
}
