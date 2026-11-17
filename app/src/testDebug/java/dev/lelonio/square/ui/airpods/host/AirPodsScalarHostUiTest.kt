// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.ui.airpods.host

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.*
import dev.lelonio.square.R
import dev.lelonio.square.airpods.*
import dev.lelonio.square.airpods.protocol.*
import dev.lelonio.square.ui.airpods.*
import dev.lelonio.square.ui.theme.SquareTheme
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real host integration. EVERY test mounts the production body of the
 * settings PAGE its control is filed under (airPodsSettingsPageOf), not a replica or a focused
 * editor: the settings are pages now, and a control is on exactly one of them. Real Compose
 * events dispatch to the real host/admitted owner; only Bluetooth SDUs/persistence/platform facts
 * are synthetic. No Activity navigation, physical device, visual-quality or firmware claim. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w600dp-h960dp-mdpi",
    instrumentedPackages = ["dev.lelonio.square.airpods"], shadows = [HostProtocolSocketShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AirPodsScalarHostUiTest {
    @get:Rule val compose = createComposeRule()

    /** The settings page on screen. Every locator below first shows the page of its control. */
    private val dispatchStarted = java.util.concurrent.atomic.AtomicInteger()
    private var page by mutableStateOf(AirPodsSettingsPage.Main)
    private fun show(c: ScalarHostCase) {
        val target = c.expectedPage
        assertEquals(c.catalogId, target, airPodsSettingsPageOf(c.property))
        compose.runOnIdle { page = target }
        compose.waitForIdle()
    }

    private fun mount(f: HostProtocolFixture) {
        compose.setContent {
            val state by f.host.state.collectAsState()
            val scope = rememberCoroutineScope()
            val captured = f.host.stamp
            val dispatch: (AirPodsUiAction) -> Unit = { action ->
                dispatchStarted.incrementAndGet()
                scope.launch {
                f.host.dispatch(captured, action) { _, _ -> fail("No platform UI for scalar writes") }
            } }
            val backdrop = rememberGlassBackdrop()
            val modal = rememberFluidGlassModalHostState()
            SquareTheme(darkTheme = false, amoled = false, systemBars = false) {
                CompositionLocalProvider(LocalGlassBackdrop provides backdrop,
                    LocalFluidGlassModalHostState provides modal,
                    LocalFluidMotionPolicy provides FluidMotionPolicy(0f)) {
                    Box(Modifier.requiredSize(360.dp, 640.dp)) {
                        AirPodsSettingsContent(state, dispatch, Modifier.fillMaxSize(), page = page)
                        FluidGlassModalHost(modal, backdrop = backdrop)
                    }
                }
            }
        }
    }

    private fun isSwitch(c: ScalarHostCase) =
        AirPodsProperties.spec(c.property)!!.encoding == ControlEncoding.BOOLEAN_1_2
    private fun control(c: ScalarHostCase, input: ScalarInput = c.requested): SemanticsNodeInteraction {
        show(c)
        val tag = when {
            isSwitch(c) -> "airpods-switch-${c.property}"
            c.property == CommandProperty.MODE -> "airpods-mode-${input.value}"
            c.property == CommandProperty.CHIME_VOLUME -> "airpods-chime-volume"
            c.property == CommandProperty.AUTO_ANC_STRENGTH -> "airpods-property-${c.property}"
            c.property == CommandProperty.CALL_MANAGEMENT_CONFIG ->
                "airpods-choice-" + if (input.value == 3) "mute-once" else "mute-twice"
            else -> "airpods-choice-${c.property}-${input.value}"
        }
        return if (c.kind == ScalarEditorKind.NUMBER) compose.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress) and
                (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))))
        else compose.onNodeWithTag(tag)
    }
    private fun cycle(c: ScalarHostCase, wire: Int): SemanticsNodeInteraction {
        show(c)
        return compose.onNodeWithTag("airpods-cycle-${ListeningMode.entries.single { it.wire == wire }.name}")
    }
    private fun assertUi(c: ScalarHostCase, input: ScalarInput) {
        when (c.kind) {
            ScalarEditorKind.MASK -> for (wire in 1..4) {
                val row = cycle(c, wire).performScrollTo()
                if (input.value and (1 shl (wire - 1)) != 0) row.assertIsOn() else row.assertIsOff()
            }
            ScalarEditorKind.NUMBER -> control(c).performScrollTo()
                .assertRangeInfoEquals(ProgressBarRangeInfo(input.value.toFloat(), 0f..100f))
            ScalarEditorKind.OPTIONS -> if (isSwitch(c)) {
                val row = control(c).performScrollTo()
                if (input.value == 1) row.assertIsOn() else row.assertIsOff()
            } else {
                control(c, input).performScrollTo().assertIsSelected()
                c.inputs.filter { it.value != input.value }.forEach { control(c, it).assertIsNotSelected() }
                if (c.property == CommandProperty.CALL_MANAGEMENT_CONFIG) {
                    compose.onNodeWithTag("airpods-choice-" + if (input.value == 3) "end-twice" else "end-once").assertIsSelected()
                    compose.onNodeWithTag("airpods-choice-" + if (input.value == 3) "end-once" else "end-twice").assertIsNotSelected()
                }
            }
        }
    }
    private fun editAndSend(f: HostProtocolFixture, c: ScalarHostCase) {
        val before = f.transport.commandWrites.size
        when(c.kind) {
            ScalarEditorKind.NUMBER -> control(c).performScrollTo()
                .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(c.requested.value.toFloat())) }
            ScalarEditorKind.MASK -> {
                // RX=6: removing either remaining mode is forbidden. Adding Off sends 7,
                // then adding Adaptive sends 15 immediately, without Apply.
                cycle(c, 2).performScrollTo().assertIsNotEnabled()
                cycle(c, 3).performScrollTo().assertIsNotEnabled()
                cycle(c, 1).performScrollTo().performTouchInput { click() }
                compose.waitUntil(4_000) { f.transport.commandWrites.size == before + 1 }
                f.sent(c.property)
                assertArrayEquals(HostProtocolWire.bytes("04 00 04 00 09 00 1a 07 00 00 00"), f.transport.commandWrites.last().toByteArray())
                cycle(c, 4).performScrollTo().performTouchInput { click() }
            }
            ScalarEditorKind.OPTIONS -> if (isSwitch(c)) control(c).performScrollTo().performTouchInput { click() }
                else control(c).performScrollTo().performClick()
        }
        compose.waitUntil(4_000) { f.transport.count(c.requested.hex) == 1 }
        f.sent(c.property)
        assertEquals(before + if (c.kind == ScalarEditorKind.MASK) 2 else 1, f.transport.commandWrites.size)
        assertArrayEquals(c.catalogId, HostProtocolWire.bytes(c.requested.hex), f.transport.commandWrites.last().toByteArray())
    }
    private fun fullPageCase(property: CommandProperty) {
        val c = ScalarHostCases.get(property)
        HostProtocolFixture().use { f ->
            f.start(); f.receiveScalar(c.initial.hex, property, c.initial.number); mount(f)
            assertUi(c, c.initial)
            editAndSend(f, c)
            assertEquals(c.initial.number, f.observed(property))
            assertUi(c, c.requested)
            compose.onAllNodesWithText(f.text(R.string.airpods_ui_pending)).assertCountEquals(0)
            compose.onAllNodesWithText(f.text(R.string.airpods_ui_apply)).assertCountEquals(0)
            val count = f.transport.commandWrites.size
            f.receiveScalar(c.different.hex, property, c.different.number)
            assertEquals(CommandOutcome.OBSERVED_OTHER, f.owner.state.value.outcomes[property])
            assertUi(c, c.different)
            assertEquals(count, f.transport.commandWrites.size)
            assertFalse(f.host.state.value.productGatePassed)
            assertFalse(f.owner.runtime.value.productConformance)
            assertNull(f.owner.store.snapshot().getOrThrow().pairs.getValue(HostProtocolFixture.PAIR).verification)
        }
    }
    @Test fun fullPageMicrophone() = fullPageCase(CommandProperty.MIC_MODE)
    @Test fun fullPageDoubleClickInterval() = fullPageCase(CommandProperty.DOUBLE_CLICK_INTERVAL)
    @Test fun fullPageClickHoldInterval() = fullPageCase(CommandProperty.CLICK_HOLD_INTERVAL)
    @Test fun fullPageListeningModeMask() = fullPageCase(CommandProperty.LISTENING_MODE_CONFIGS)
    @Test fun fullPageOneBudAnc() = fullPageCase(CommandProperty.ONE_BUD_ANC_MODE)
    @Test fun fullPageListeningMode() = fullPageCase(CommandProperty.MODE)
    @Test fun fullPageChimeVolume() = fullPageCase(CommandProperty.CHIME_VOLUME)
    @Test fun fullPageVolumeSwipeInterval() = fullPageCase(CommandProperty.VOLUME_SWIPE_INTERVAL)
    @Test fun fullPageCallAssignment() = fullPageCase(CommandProperty.CALL_MANAGEMENT_CONFIG)
    @Test fun fullPageVolumeSwipeMode() = fullPageCase(CommandProperty.VOLUME_SWIPE_MODE)
    @Test fun fullPageAdaptiveVolume() = fullPageCase(CommandProperty.ADAPTIVE_VOLUME_CONFIG)
    @Test fun fullPageConversationConfiguration() = fullPageCase(CommandProperty.CONVERSATION)
    @Test fun fullPageAdaptiveStrength() = fullPageCase(CommandProperty.AUTO_ANC_STRENGTH)
    @Test fun fullPageAllowOff() = fullPageCase(CommandProperty.ALLOW_OFF_OPTION)
    @Test fun fullPageSleepDetection() = fullPageCase(CommandProperty.SLEEP_DETECTION_CONFIG)
    @Test fun fullPageEarDetection() = fullPageCase(CommandProperty.EAR_DETECTION_CONFIG)
    @Test fun fullPageAutomaticConnection() = fullPageCase(CommandProperty.AUTOMATIC_CONNECTION_CONFIG)
    @Test fun fullPagePpe() = fullPageCase(CommandProperty.PPE_TOGGLE_CONFIG)
    @Test fun fullPageOptimizedCharging() = fullPageCase(CommandProperty.DYNAMIC_END_OF_CHARGE)

    @Test fun chimeWithoutRxShowsDefaultAndSendsDirectly() = initialNumber(CommandProperty.CHIME_VOLUME, 75)
    @Test fun adaptiveWithoutRxShowsDefaultAndSendsDirectly() = initialNumber(CommandProperty.AUTO_ANC_STRENGTH, 50)
    private fun initialNumber(property: CommandProperty, default: Int) {
        val c = ScalarHostCases.get(property)
        HostProtocolFixture().use { f ->
            f.start(); mount(f)
            assertNull(f.observed(property))
            assertUi(c, ScalarInput(default, ""))
            assertTrue(f.transport.commandWrites.isEmpty())
            editAndSend(f, c)
            assertNull(f.observed(property)); assertUi(c, c.requested)
            f.receiveScalar(c.different.hex, property, c.different.number)
            assertUi(c, c.different)
        }
    }
    @Test fun defaultsAreDisplayedWithoutBecomingObservationsOrWrites() {
        HostProtocolFixture().use { f ->
            f.start(); mount(f)
            for (c in ScalarHostCases.all) {
                val value = f.host.state.value.domain.propertyEffective(c.property)?.value as? PropertyValue.Number ?: continue
                if (c.kind == ScalarEditorKind.MASK) {
                    for (wire in 1..4) cycle(c, wire).performScrollTo().assertIsNotEnabled().assertHasNoClickAction()
                        .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, f.text(R.string.airpods_ui_unknown)))
                } else assertUi(c, ScalarInput(value.value, ""))
                if (c.property !in setOf(CommandProperty.MIC_MODE, CommandProperty.MODE)) assertNull(f.observed(c.property))
            }
            assertTrue(f.transport.commandWrites.isEmpty())
        }
    }
    @Test fun recognizedUnsupportedModelIsNotOfferedTheControlsItDoesNotHave() {
        HostProtocolFixture().use { f ->
            f.start(); f.receiveModel(ScalarHostCases.INFORMATION_A1523, "A1523"); mount(f)
            for (c in ScalarHostCases.all) {
                show(c)
                if (c.needsCapability) {
                    if (c.kind == ScalarEditorKind.MASK) compose.onAllNodesWithTag("airpods-cycle-ANC").assertCountEquals(0)
                    else control(c).assertDoesNotExist()
                    assertFalse(c.catalogId, f.host.state.value.writeAllowed(c.property, c.requested.number))
                } else control(c).assertExists()
            }
            assertTrue(f.transport.commandWrites.isEmpty())
        }
    }
    @Test fun receivedAllowOffConfigurationUpdatesBothModeAndMaskReachability() {
        HostProtocolFixture().use { f ->
            f.start()
            val off = ScalarHostCases.get(CommandProperty.ALLOW_OFF_OPTION)
            val mode = ScalarHostCases.get(CommandProperty.MODE)
            val mask = ScalarHostCases.get(CommandProperty.LISTENING_MODE_CONFIGS)
            f.receiveScalar(off.requested.hex, off.property, off.requested.number); mount(f)
            control(mode, mode.initial).performScrollTo().assertIsEnabled()
            cycle(mask, 1).performScrollTo().assertExists()
            control(off).performScrollTo().performTouchInput { click() }
            f.sent(off.property)
            // Effective policy is immediate, before its echo arrives.
            control(mode, mode.initial).assertDoesNotExist(); cycle(mask, 1).assertDoesNotExist()
            f.receiveScalar(off.initial.hex, off.property, off.initial.number)
            control(mode, mode.initial).assertDoesNotExist(); cycle(mask, 1).assertDoesNotExist()
            assertEquals(1, f.transport.commandWrites.size)
        }
    }
    @Test fun permitRevokedAfterRenderingCannotTurnAVisibleOptionIntoATransportWrite() {
        HostProtocolFixture().use { f ->
            f.start(); mount(f)
            val c = ScalarHostCases.get(CommandProperty.MIC_MODE)
            val visibleOption = control(c).performScrollTo().assertIsEnabled()
            val startedBefore = dispatchStarted.get()
            val renderedStamp = f.host.stamp
            val renderedGeneration = f.owner.state.value.generation
            f.permit.revoke()
            visibleOption.performClick()
            compose.waitForIdle()
            // GesturePropertyChoice rechecks the LIVE authority synchronously in OnClick.
            // Its already-rendered action returns without even launching the host coroutine.
            assertEquals(startedBefore, dispatchStarted.get())
            // Also exercise the host fence with the stamp captured before revocation;
            // this call returns synchronously after dispatch, rather than waiting for silence.
            f.write(c.property, c.requested.number, renderedStamp)
            f.refresh()
            assertFalse(f.host.state.value.controlsReady)
            control(c).assertIsNotEnabled()
            assertTrue(f.owner.state.value.pending.isEmpty())
            assertEquals("Revocation cannot invent an observation; retired RX is filtered",
                if (f.owner.state.value.generation == renderedGeneration) PropertyValue.Number(0) else null,
                f.observed(c.property))
            assertTrue(f.transport.commandWrites.isEmpty())
        }
    }
}
