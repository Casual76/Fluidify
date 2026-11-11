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

/** PREPARED ONLY, not compiled/executed here. EVERY test mounts the production body of the
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
    private var page by mutableStateOf(AirPodsSettingsPage.Main)
    private fun show(c: ScalarHostCase) {
        val target = airPodsSettingsPageOf(c.property)
        compose.runOnIdle { page = target }
        compose.waitForIdle()
    }

    /** An on/off control is one switch once the device has reported it. Before that there is no
     * position to draw, and it is two alternatives with neither selected, like any other choice. */
    private fun isSwitch(f: HostProtocolFixture, c: ScalarHostCase) =
        requireNotNull(AirPodsProperties.spec(c.property)).encoding == ControlEncoding.BOOLEAN_1_2 &&
            f.observed(c.property) != null

    /** The switch row is its own heading: one node carrying the title and the toggle state. */
    private fun switch(f: HostProtocolFixture, c: ScalarHostCase): SemanticsNodeInteraction {
        show(c)
        return compose.onNode(hasText(title(f, c)) and isToggleable())
    }

    private fun mount(f: HostProtocolFixture) {
        compose.setContent {
            val state by f.host.state.collectAsState()
            val scope = rememberCoroutineScope()
            val captured = f.host.stamp
            val dispatch: (AirPodsUiAction) -> Unit = { action -> scope.launch {
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

    private fun title(f: HostProtocolFixture, c: ScalarHostCase) = f.text(featureTitles.getValue(c.catalogId))

    /** Production has no per-editor tag/wrapper. Scope by the actual semantic heading and the
     * next PUBLIC scalar heading in document order. Resolve IDs afresh after each recomposition.
     * No global nth On/Off/Apply locator, coordinates, or fake editor subtree is substituted.
     * Fail if a heading disappears; a full-page omission cannot pass a focused editor test. */
    private fun editorNodes(f: HostProtocolFixture, c: ScalarHostCase): List<SemanticsNode> {
        show(c)
        compose.waitForIdle()
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        // The merged tree keeps FluidListRow's title, Selected and OnClick on the same node.
        val nodes = flatten(compose.onRoot().fetchSemanticsNode())
        fun heading(node: SemanticsNode, text: String) =
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
        val start = nodes.indexOfFirst { heading(it, title(f, c)) }
        assertTrue("The page of ${c.catalogId} omits it", start >= 0)
        // The final scalar is followed by the device section, not another scalar.
        // Its editor must not include device/gesture rows with the same On/Off labels.
        val otherTitles = ScalarHostCases.all.filter { it != c }.map { title(f, it) }.toSet() +
            f.text(R.string.airpods_feature_device_group)
        val end = (start + 1 until nodes.size).firstOrNull { i -> otherTitles.any { heading(nodes[i], it) } } ?: nodes.size
        return nodes.subList(start + 1, end)
    }

    private fun editorNode(f: HostProtocolFixture, c: ScalarHostCase, matcher: SemanticsMatcher): SemanticsNodeInteraction {
        val matching = editorNodes(f, c).filter { matcher.matches(it) }
        assertEquals("${c.catalogId}: unique scoped ${matcher.description}", 1, matching.size)
        val id = matching.single().id
        return compose.onNode(SemanticsMatcher("${c.catalogId} node $id") { it.id == id })
    }

    private fun modeText(f: HostProtocolFixture, wire: Int) = f.text(when (wire) {
        1 -> R.string.airpods_feature_mode_off
        2 -> R.string.airpods_feature_mode_anc
        3 -> R.string.airpods_feature_mode_transparency
        4 -> R.string.airpods_feature_mode_adaptive
        else -> error("Unexpected independent mode input")
    })

    private fun optionText(f: HostProtocolFixture, c: ScalarHostCase, input: ScalarInput) =
        if (c.property == CommandProperty.MODE) modeText(f, input.value) else f.text(optionLabels.getValue(input.label))

    private fun option(f: HostProtocolFixture, c: ScalarHostCase, input: ScalarInput) =
        editorNode(f, c, hasText(optionText(f, c, input)))

    private fun apply(f: HostProtocolFixture, c: ScalarHostCase) =
        editorNode(f, c, hasText(f.text(R.string.airpods_ui_apply)) and hasClickAction())

    private fun toggle(f: HostProtocolFixture, c: ScalarHostCase, wire: Int) =
        editorNode(f, c, hasText(modeText(f, wire)) and isToggleable())

    private fun status(f: HostProtocolFixture, c: ScalarHostCase, resource: Int) =
        editorNode(f, c, hasText(f.text(resource)) and
            hasTestTag("airpods-reason-property.${c.property}").not())

    /** The request is said to be waiting, wherever this control says it. */
    private fun awaiting(f: HostProtocolFixture, c: ScalarHostCase) {
        val words = f.text(R.string.airpods_ui_pending)
        // A switch row says it in its second line, which its single node carries as description.
        if (isSwitch(f, c)) switch(f, c).performScrollTo().assert(hasContentDescription(words, substring = true))
        else status(f, c, R.string.airpods_ui_pending).performScrollTo().assertIsDisplayed()
    }

    /** After an answer nothing is said to be waiting; an editor with a status line says "current". */
    private fun settled(f: HostProtocolFixture, c: ScalarHostCase) {
        val waiting = f.text(R.string.airpods_ui_pending)
        when {
            isSwitch(f, c) -> switch(f, c).assert(hasContentDescription(waiting, substring = true).not())
            // The selector's thumb is the received mode; it prints a status only while one is owed.
            c.property == CommandProperty.MODE ->
                assertTrue(c.catalogId, editorNodes(f, c).none { hasText(waiting).matches(it) })
            else -> status(f, c, R.string.airpods_ui_observed).performScrollTo().assertIsDisplayed()
        }
    }

    private fun assertReceivedUi(f: HostProtocolFixture, c: ScalarHostCase, input: ScalarInput) {
        when (c.kind) {
            ScalarEditorKind.OPTIONS -> if (isSwitch(f, c)) {
                val row = switch(f, c).performScrollTo().assertIsDisplayed()
                if (input.value == 1) row.assertIsOn() else row.assertIsOff()
            } else {
                option(f, c, input).performScrollTo().assertIsDisplayed().assertIsSelected()
                c.inputs.filter { it.value != input.value }.forEach {
                    option(f, c, it).assert(isSelected().not())
                }
            }
            ScalarEditorKind.NUMBER -> {
                editorNode(f, c, hasText("${title(f, c)} \u00b7 ${input.value}"))
                    .performScrollTo().assertIsDisplayed()
                editorNode(f, c, hasContentDescription(title(f, c)))
                    .assertRangeInfoEquals(ProgressBarRangeInfo(input.value.toFloat(), 0f..100f))
            }
            ScalarEditorKind.MASK -> for (wire in 1..4) {
                val row = toggle(f, c, wire).performScrollTo().assertIsDisplayed()
                if (input.value and (1 shl (wire - 1)) != 0) row.assertIsOn() else row.assertIsOff()
            }
        }
    }

    private fun editAndSend(f: HostProtocolFixture, c: ScalarHostCase) {
        val writes = f.transport.writes.size
        when (c.kind) {
            // A switch asks for the other value; the pointer is what a finger uses on its row.
            ScalarEditorKind.OPTIONS -> if (isSwitch(f, c)) switch(f, c).performScrollTo().performTouchInput { click() }
                else option(f, c, c.requested).performScrollTo().performClick()
            ScalarEditorKind.NUMBER -> {
                editorNode(f, c, hasContentDescription(title(f, c))).performScrollTo()
                    .performSemanticsAction(SemanticsActions.SetProgress) { set -> assertTrue(set(c.requested.value.toFloat())) }
                assertEquals(c.catalogId, c.initial.number, f.observed(c.property))
                assertFalse(c.catalogId, c.property in f.owner.state.value.pending)
                assertEquals(c.catalogId, writes, f.transport.writes.size)
                apply(f, c).performScrollTo().assertIsEnabled().performClick()
            }
            ScalarEditorKind.MASK -> {
                fun touch(wire: Int) {
                    // FluidToggleRow deliberately clears its semantic OnClick; exercise the
                    // real pointer handler instead of inventing an accessibility action.
                    toggle(f, c, wire).performScrollTo().performTouchInput { click() }
                }
                // From RX=6: one bit, zero bits and Off alone must each keep Apply disabled.
                touch(2); apply(f, c).assertIsNotEnabled()
                touch(3); apply(f, c).assertIsNotEnabled()
                touch(1); apply(f, c).assertIsNotEnabled()
                touch(2); apply(f, c).assertIsEnabled()
                touch(3); touch(4) // draft=15; none of the toggles sends a command
                assertEquals(c.catalogId, c.initial.number, f.observed(c.property))
                assertFalse(c.catalogId, c.property in f.owner.state.value.pending)
                assertEquals(c.catalogId, writes, f.transport.writes.size)
                apply(f, c).performScrollTo().performClick()
            }
        }
        // This also exercises the real AUTO_ANC_STRENGTH debounce before the host ingress.
        // It does not certify the exact 150ms boundary or scheduling on a physical device.
        compose.waitUntil(4_000) { f.transport.writes.size == writes + 1 }
        assertArrayEquals(c.catalogId, HostProtocolWire.bytes(c.requested.hex), f.transport.writes.last().toByteArray())
        f.refresh()
    }

    private fun fullPageCase(property: CommandProperty) {
        val c = ScalarHostCases.get(property)
        HostProtocolFixture().use { f ->
            f.start()
            f.receiveScalar(c.initial.hex, c.property, c.initial.number)
            mount(f)
            assertReceivedUi(f, c, c.initial)
            editAndSend(f, c)
            val request = f.owner.state.value.pending.getValue(c.property)
            assertEquals(c.catalogId, c.requested.number, request.requested)
            assertEquals(c.catalogId, c.initial.number, f.observed(c.property))
            awaiting(f, c)
            when (c.kind) {
                ScalarEditorKind.OPTIONS -> {
                    assertReceivedUi(f, c, c.initial)
                    if (isSwitch(f, c)) {
                        // Still the received position, and a second touch reaches nobody.
                        val sent = f.transport.writes.size
                        switch(f, c).performTouchInput { click() }
                        compose.waitForIdle()
                        assertEquals(c.catalogId, sent, f.transport.writes.size)
                    } else option(f, c, c.requested).assert(hasClickAction().not())
                }
                ScalarEditorKind.NUMBER -> assertReceivedUi(f, c, c.initial)
                // The mask intentionally displays a LOCAL draft during pending; RX stays 6.
                ScalarEditorKind.MASK -> assertReceivedUi(f, c, c.requested)
            }
            val count = f.transport.writes.size
            f.receiveScalar(c.different.hex, c.property, c.different.number)
            assertEquals(c.catalogId, CommandOutcome.OBSERVED_OTHER, f.owner.state.value.outcomes[c.property])
            // Regression expectation for the mask too: RX=6 must replace draft=15 even when
            // the previous RX was also 6. Frozen production remember(value,generation) may
            // retain that draft; REPORT.md records the statically identified product defect.
            assertReceivedUi(f, c, c.different)
            settled(f, c)
            assertEquals(c.catalogId, count, f.transport.writes.size) // feedback does not echo-write
            assertFalse(c.catalogId, f.host.state.value.productGatePassed)
            assertFalse(c.catalogId, f.owner.runtime.value.productConformance)
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

    @Test fun chimeUnknownInputRequiresAnExplicitValidDraft() = unknownNumber(CommandProperty.CHIME_VOLUME)
    @Test fun adaptiveUnknownInputRequiresAnExplicitValidDraft() = unknownNumber(CommandProperty.AUTO_ANC_STRENGTH)

    private fun unknownNumber(property: CommandProperty) {
        val c = ScalarHostCases.get(property)
        HostProtocolFixture().use { f ->
            f.start(); mount(f)
            assertNull(f.observed(property))
            status(f, c, R.string.airpods_ui_candidate).performScrollTo().assertIsDisplayed()
            apply(f, c).assertIsNotEnabled()
            for (invalid in listOf("-1", "101", "bad", "")) {
                editorNode(f, c, hasSetTextAction()).performScrollTo().performTextReplacement(invalid)
                apply(f, c).assertIsNotEnabled()
                assertNull(f.observed(property))
                assertTrue(f.owner.state.value.pending.isEmpty())
                assertEquals(HostProtocolWire.BOOTSTRAP_PACKET_COUNT, f.transport.writes.size)
            }
            editorNode(f, c, hasSetTextAction()).performScrollTo().performTextReplacement("100")
            assertNull(f.observed(property))
            assertEquals(HostProtocolWire.BOOTSTRAP_PACKET_COUNT, f.transport.writes.size)
            apply(f, c).performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(4_000) { f.transport.count(c.requested.hex) == 1 }
            f.refresh()
            assertNull(f.observed(property))
            status(f, c, R.string.airpods_ui_pending).performScrollTo().assertIsDisplayed()
            f.receiveScalar(c.different.hex, property, c.different.number)
            assertEquals(CommandOutcome.OBSERVED_OTHER, f.owner.state.value.outcomes[property])
            assertReceivedUi(f, c, c.different)
        }
    }

    @Test fun candidateOptionsAndMaskDoNotSelectGuessedDefaultsInTheFullPage() {
        HostProtocolFixture().use { f ->
            f.start(); mount(f)
            for (c in ScalarHostCases.all.filter { it.property !in setOf(CommandProperty.MIC_MODE, CommandProperty.MODE) }) {
                assertNull(c.catalogId, f.observed(c.property))
                status(f, c, R.string.airpods_ui_candidate).performScrollTo().assertIsDisplayed()
                when (c.kind) {
                    ScalarEditorKind.OPTIONS -> c.inputs.forEach { option(f, c, it).assert(isSelected().not()) }
                    ScalarEditorKind.NUMBER -> {
                        editorNode(f, c, hasSetTextAction()).assertExists()
                        apply(f, c).assertIsNotEnabled()
                    }
                    ScalarEditorKind.MASK -> {
                        (1..4).forEach { toggle(f, c, it).assertIsOff() }
                        apply(f, c).assertIsNotEnabled()
                    }
                }
            }
            assertEquals(HostProtocolWire.BOOTSTRAP_PACKET_COUNT, f.transport.writes.size)
        }
    }

    @Test fun recognizedUnsupportedModelIsNotOfferedTheControlsItDoesNotHave() {
        HostProtocolFixture().use { f ->
            f.start()
            f.receiveModel(ScalarHostCases.INFORMATION_A1523, "A1523")
            mount(f)
            for (c in ScalarHostCases.all.filter { it.needsCapability }) {
                // A control this model does not have is not on its page at all: nothing reads as
                // available, and there is nothing to touch. The host still refuses the write.
                show(c)
                compose.onAllNodesWithText(title(f, c)).assertCountEquals(0)
                assertFalse(c.catalogId, f.host.state.value.availability.getValue("property.${c.property}").reason.isNullOrBlank())
                assertFalse(c.catalogId, f.host.state.value.writeAllowed(c.property, c.requested.number))
            }
            // What every model has is still there.
            for (c in ScalarHostCases.all.filterNot { it.needsCapability }) {
                show(c)
                assertTrue(c.catalogId, compose.onAllNodesWithText(title(f, c)).fetchSemanticsNodes().isNotEmpty())
            }
            assertEquals(HostProtocolWire.BOOTSTRAP_PACKET_COUNT, f.transport.writes.size)
        }
    }

    @Test fun receivedAllowOffConfigurationUpdatesBothModeAndMaskReachability() {
        HostProtocolFixture().use { f ->
            f.start()
            val off = ScalarHostCases.get(CommandProperty.ALLOW_OFF_OPTION)
            val mode = ScalarHostCases.get(CommandProperty.MODE)
            val mask = ScalarHostCases.get(CommandProperty.LISTENING_MODE_CONFIGS)
            f.receiveScalar(off.requested.hex, off.property, off.requested.number) // Off permitted by RX=1
            mount(f)
            option(f, mode, mode.initial).performScrollTo().assertIsDisplayed().assertHasClickAction()
            toggle(f, mask, 1).performScrollTo().assertIsDisplayed()
            // Received as 1, so it is a switch that is on; a touch requests RX=2, currently still 1.
            switch(f, off).performScrollTo().assertIsOn().performTouchInput { click() }
            compose.waitUntil(4_000) { f.transport.count(off.initial.hex) == 1 }
            f.refresh()
            option(f, mode, mode.initial).performScrollTo().assertIsDisplayed().assertHasClickAction()
            toggle(f, mask, 1).performScrollTo().assertIsDisplayed()
            f.receiveScalar(off.initial.hex, off.property, off.initial.number)
            assertFalse(editorNodes(f, mode).any { hasText(modeText(f, 1)).matches(it) })
            assertFalse(editorNodes(f, mask).any { isToggleable().matches(it) && hasText(modeText(f, 1)).matches(it) })
            assertEquals(PropertyValue.Number(2), f.observed(off.property))
            assertEquals(HostProtocolWire.BOOTSTRAP_PACKET_COUNT + 1, f.transport.writes.size)
        }
    }

    @Test fun permitRevokedAfterRenderingCannotTurnAVisibleOptionIntoATransportWrite() {
        HostProtocolFixture().use { f ->
            f.start()
            val c = ScalarHostCases.get(CommandProperty.MIC_MODE)
            mount(f)
            option(f, c, c.requested).performScrollTo().assertHasClickAction()
            f.permit.revoke() // no host-state refresh: exercise the already-rendered closure
            option(f, c, c.requested).performClick()
            compose.waitForIdle()
            f.refresh()
            assertFalse(f.host.state.value.controlsReady)
            option(f, c, c.requested).assert(hasClickAction().not())
            assertTrue(f.owner.state.value.pending.isEmpty())
            assertEquals(PropertyValue.Number(0), f.observed(c.property))
            assertEquals(HostProtocolWire.BOOTSTRAP_PACKET_COUNT, f.transport.writes.size)
        }
    }
}
