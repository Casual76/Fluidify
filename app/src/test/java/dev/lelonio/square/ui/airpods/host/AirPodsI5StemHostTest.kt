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
import com.github.takahirom.roborazzi.captureRoboImage
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
    @Test fun realHostAndPagesSeparateLocalChoiceFromEveryCommandOutcomeAtFontTwo() = runBlocking {
        val f = Fixture()
        try {
            f.owner.store.configure(f.pair) { it.copy(leftLongPress = LongPressAction.DIGITAL_ASSISTANT) }.getOrThrow()
            f.stem(0, CommandOutcome.SEND_FAILED); f.host.refresh(); mount(f, font = 2f)
            val assistant = f.text(R.string.airpods_ui_digital_assistant)
            val noise = f.text(gestureLabels.getValue(GestureAction.NOISE_CONTROL))
            // The main page says what is saved for each side; the page behind the row is where it is chosen.
            compose.onNodeWithTag("airpods-gesture-LEFT").performScrollTo().assertTextContains(assistant, substring = true)
            compose.onNodeWithTag("airpods-gesture-RIGHT").performScrollTo().assertTextContains(noise, substring = true)
            show(AirPodsSettingsPage.LongPressLeft)
            compose.onNodeWithText(f.text(R.string.airpods_stem_local_choice)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(assistant).performScrollTo().assertIsSelected()
            compose.onNodeWithText(noise).assertIsNotSelected()
            compose.onNodeWithTag("airpods-stem-observation").performScrollTo()
                .assertTextEquals(f.text(R.string.airpods_stem_hardware))
            val expected = mapOf(CommandOutcome.SEND_FAILED to R.string.airpods_stem_failed,
                CommandOutcome.NOT_CONFIRMED to R.string.airpods_stem_timeout,
                CommandOutcome.OBSERVED_MATCH to R.string.airpods_stem_match,
                CommandOutcome.OBSERVED_OTHER to R.string.airpods_stem_other,
                CommandOutcome.CLOSED to R.string.airpods_stem_closed)
            for ((outcome, resource) in expected) {
                f.stem(0, outcome); f.host.refresh()
                compose.onNodeWithTag("airpods-stem-command").performScrollTo().assertTextEquals(f.text(resource)).assertIsDisplayed()
                val capture = java.io.File("build/airpods-full-integration/implementation/I5-stem-screenshots/$outcome-font2.png")
                capture.parentFile.mkdirs()
                compose.onRoot().captureRoboImage(capture.path)
            }
            f.stem(0, pending = true); f.host.refresh()
            for (side in listOf(GestureSide.LEFT, GestureSide.RIGHT)) {
                assertFalse(f.host.state.value.allowed("gesture.$side"))
                show(if (side == GestureSide.LEFT) AirPodsSettingsPage.LongPressLeft else AirPodsSettingsPage.LongPressRight)
                // Paused on both sides: a touch on either alternative reaches nobody.
                for (label in listOf(assistant, noise))
                    compose.onNodeWithText(label).performScrollTo().performTouchInput { click() }
                compose.waitForIdle()
                assertTrue(f.actions.isEmpty())
            }
            compose.onNodeWithTag("airpods-stem-reason").performScrollTo().assertTextEquals(f.text(R.string.airpods_stem_pending))
            val before = f.owner.store.snapshot().getOrThrow()
            f.host.dispatch(f.host.stamp, AirPodsUiAction.AssignGesture(GestureSide.RIGHT, GestureAction.DIGITAL_ASSISTANT)) { _, _ -> fail("No platform action") }
            assertEquals(before, f.owner.store.snapshot().getOrThrow())
            assertTrue(f.actions.isEmpty())
            show(AirPodsSettingsPage.HeadGestures)
            for (side in listOf(GestureSide.NOD, GestureSide.SHAKE)) {
                assertTrue(f.host.state.value.allowed("gesture.$side"))
                compose.onNodeWithTag("airpods-gesture-$side").performScrollTo().assertIsEnabled()
            }
        } finally { f.close() }
    }
    @Test fun unknownPermissionClosedAndNewGenerationHaveConcreteCurrentReasons() = runBlocking {
        val f = Fixture()
        try {
            f.stem(null); f.host.refresh(); page = AirPodsSettingsPage.LongPressLeft; mount(f)
            compose.onNodeWithTag("airpods-stem-observation").performScrollTo().assertTextEquals(f.text(R.string.airpods_stem_unknown))
            compose.onNodeWithTag("airpods-stem-reason").performScrollTo().assertTextEquals(f.text(R.string.airpods_stem_need_observation))
            shadowOf(f.context).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT); f.host.refresh()
            compose.onNodeWithTag("airpods-stem-reason").performScrollTo().assertTextEquals(f.text(R.string.airpods_reason_bluetooth_permission))
            shadowOf(f.context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
            f.stem(8, CommandOutcome.OBSERVED_MATCH)
            f.domain.value = f.domain.value.copy(stage = AacpStage.CLOSED, generation = 8); f.host.refresh()
            compose.onNodeWithTag("airpods-stem-observation").performScrollTo().assertTextEquals(f.text(R.string.airpods_stem_unknown))
            compose.onNodeWithTag("airpods-stem-command").performScrollTo().assertTextEquals(f.text(R.string.airpods_stem_closed))
            compose.onNodeWithTag("airpods-stem-reason").performScrollTo().assertTextEquals(f.text(R.string.airpods_reason_session))
            f.domain.value = f.domain.value.copy(stage = AacpStage.SUBSCRIBED, outcomes = emptyMap()); f.host.refresh()
            compose.onNodeWithTag("airpods-stem-command").performScrollTo().assertTextEquals(f.text(R.string.airpods_stem_no_command))
            assertTrue(f.actions.isEmpty())
        } finally { f.close() }
    }
}
