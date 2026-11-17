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

/** Synthetic admission and real host selector. Actual controller selector -> host -> real page/portal.
 * Admission/runtime/repository observations injected; no gate admission, FGS, transport or hardware proof.
 * Action sink measures UI dispatch; blocked host dispatch also rechecks the actual host state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w600dp-h960dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AirPodsI5StemHostTest {
    @get:Rule val compose = createComposeRule()
    /** The settings page on screen. A test shows the page its control is filed under. */
    private var page by mutableStateOf(AirPodsSettingsPage.Main)
    private fun show(target: AirPodsSettingsPage) {
        compose.runOnIdle { page = target }
        compose.waitForIdle()
    }
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
                        else AirPodsSettingsContent(state, f.actions::add, Modifier.fillMaxSize(), page = page)
                        FluidGlassModalHost(modal, backdrop = backdrop)
                    }
                }
            }
        }
    }
    private fun reason(key: String, text: String) {
        compose.onNodeWithTag("airpods-reason-$key").performScrollTo().assertTextEquals(text).assertIsDisplayed()
    }
    private fun Fixture.stem(mask: Int?, outcome: CommandOutcome? = null, pending: Boolean = false) {
        domain.value = domain.value.copy(
            properties = mask?.let { mapOf(CommandProperty.STEM_CONFIG to Received<PropertyValue>(PropertyValue.Number(it), 2, 7)) }.orEmpty(),
            pending = if (pending) mapOf(CommandProperty.STEM_CONFIG to PendingCommand(8, 2, 1, generation = 7)) else emptyMap(),
            outcomes = outcome?.let { mapOf(CommandProperty.STEM_CONFIG to it) }.orEmpty())
    }
    @Test fun realHostPagesKeepLocalChoiceEditableAcrossOutcomesAtFontTwo() = runBlocking {
        val f = Fixture()
        try {
            f.owner.store.configure(f.pair) { it.copy(leftLongPress = LongPressAction.DIGITAL_ASSISTANT) }.getOrThrow()
            f.stem(0, CommandOutcome.SEND_FAILED); f.host.refresh(); mount(f, font = 2f)
            compose.onNodeWithTag("airpods-gesture-LEFT").performScrollTo()
                .assertTextContains(f.text(R.string.airpods_ui_digital_assistant), substring = true)
            compose.onNodeWithTag("airpods-gesture-RIGHT").performScrollTo()
                .assertTextContains(f.text(gestureLabels.getValue(GestureAction.NOISE_CONTROL)), substring = true)
            for (outcome in CommandOutcome.entries) {
                f.stem(0, outcome); f.host.refresh()
                show(AirPodsSettingsPage.LongPressLeft)
                compose.onNodeWithTag("airpods-gesture-LEFT-DIGITAL_ASSISTANT").performScrollTo().assertIsSelected().assertIsEnabled()
                compose.onNodeWithTag("airpods-gesture-LEFT-NOISE_CONTROL").performScrollTo().assertIsNotSelected().assertIsEnabled()
                compose.onAllNodesWithTag("airpods-stem-command").assertCountEquals(0)
                compose.onAllNodesWithTag("airpods-stem-observation").assertCountEquals(0)
                assertEquals(LongPressAction.DIGITAL_ASSISTANT, f.owner.store.snapshot().getOrThrow().pairs.getValue(f.pair).leftLongPress)
            }
            f.stem(0, pending = true); f.host.refresh()
            for (side in listOf(GestureSide.LEFT, GestureSide.RIGHT)) {
                assertTrue(f.host.state.value.allowed("gesture.$side"))
                show(if (side == GestureSide.LEFT) AirPodsSettingsPage.LongPressLeft else AirPodsSettingsPage.LongPressRight)
                compose.onNodeWithTag("airpods-gesture-$side-DIGITAL_ASSISTANT").performScrollTo().assertIsEnabled().performClick()
            }
            compose.waitForIdle()
            assertEquals(listOf(AirPodsUiAction.AssignGesture(GestureSide.LEFT, GestureAction.DIGITAL_ASSISTANT),
                AirPodsUiAction.AssignGesture(GestureSide.RIGHT, GestureAction.DIGITAL_ASSISTANT)), f.actions)
            show(AirPodsSettingsPage.HeadGestures)
            for (side in listOf(GestureSide.NOD, GestureSide.SHAKE))
                compose.onNodeWithTag("airpods-gesture-$side").performScrollTo().assertIsEnabled()
        } finally { f.close() }
    }
    @Test fun waitsForCurrentStemAfterOneSubscriptionAndKeepsPermissionAndGenerationFences() = runBlocking {
        val f = Fixture()
        try {
            f.stem(null); f.host.refresh(); page = AirPodsSettingsPage.LongPressLeft; mount(f)
            val option = compose.onNodeWithTag("airpods-gesture-LEFT-DIGITAL_ASSISTANT")
            option.performScrollTo().assertIsNotEnabled()
            assertNull(f.host.state.value.domain.propertyObserved(CommandProperty.STEM_CONFIG))
            compose.waitForIdle()
            assertEquals(listOf(AirPodsUiAction.Setup(SetupAction.INFORMATION)), f.actions)
            f.actions.clear()
            f.stem(0); f.host.refresh()
            option.assertIsEnabled()
            shadowOf(f.context).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT); f.host.refresh()
            option.assertIsNotEnabled().performTouchInput { click() }
            compose.waitForIdle(); assertTrue(f.actions.isEmpty())
            val before = f.owner.store.snapshot().getOrThrow()
            val stamp = f.host.stamp
            f.host.dispatch(stamp, AirPodsUiAction.AssignGesture(GestureSide.LEFT, GestureAction.DIGITAL_ASSISTANT)) { _, _ -> fail("No platform action") }
            assertEquals(before, f.owner.store.snapshot().getOrThrow())
            shadowOf(f.context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
            f.stem(8, CommandOutcome.OBSERVED_MATCH)
            f.domain.value = f.domain.value.copy(stage = AacpStage.CLOSED, generation = 8); f.host.refresh()
            option.assertIsNotEnabled()
            assertNull(f.host.state.value.domain.propertyObserved(CommandProperty.STEM_CONFIG))
            f.host.dispatch(stamp, AirPodsUiAction.AssignGesture(GestureSide.LEFT, GestureAction.DIGITAL_ASSISTANT)) { _, _ -> fail("No platform action") }
            assertEquals(before, f.owner.store.snapshot().getOrThrow())
            f.domain.value = f.domain.value.copy(stage = AacpStage.SUBSCRIBED, outcomes = emptyMap()); f.host.refresh()
            compose.onAllNodesWithTag("airpods-stem-command").assertCountEquals(0)
            assertTrue(f.actions.isEmpty())
        } finally { f.close() }
    }
}
