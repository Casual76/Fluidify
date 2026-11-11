// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.ui.airpods.host

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
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

/** PREPARED ONLY. Real UI events -> AirPodsHost.dispatch -> admitted controller -> real
 * writer/decoder -> fake transport. State is collected from the real host, never fabricated.
 * The focused PropertyEditor is production UI; name/EQ use the production body of their own
 * settings page because their editors are private. This is not full Activity navigation, visual
 * QA or hardware. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w600dp-h960dp-mdpi",
    instrumentedPackages = ["dev.lelonio.square.airpods"], shadows = [HostProtocolSocketShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AirPodsHostProtocolUiTest {
    @get:Rule val compose = createComposeRule()

    private fun mount(f: HostProtocolFixture, property: CommandProperty? = null,
        page: AirPodsSettingsPage = AirPodsSettingsPage.Main) {
        compose.setContent {
            val state by f.host.state.collectAsState()
            val scope = rememberCoroutineScope()
            val captured = f.host.stamp
            val dispatch: (AirPodsUiAction) -> Unit = { action -> scope.launch {
                f.host.dispatch(captured, action) { _, _ -> fail("No platform UI for protocol writes") }
            } }
            val backdrop = rememberGlassBackdrop()
            val modal = rememberFluidGlassModalHostState()
            SquareTheme(darkTheme = false, amoled = false, systemBars = false) {
                CompositionLocalProvider(LocalGlassBackdrop provides backdrop,
                    LocalFluidGlassModalHostState provides modal,
                    LocalFluidMotionPolicy provides FluidMotionPolicy(0f)) {
                    Box(Modifier.requiredSize(360.dp, 640.dp)) {
                        if (property == null) AirPodsSettingsContent(state, dispatch, Modifier.fillMaxSize(), page = page)
                        else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            PropertyEditor(state, requireNotNull(AirPodsProperties.spec(property)), dispatch)
                        }
                        FluidGlassModalHost(modal, backdrop = backdrop)
                    }
                }
            }
        }
    }

    private fun pendingVisible(f: HostProtocolFixture) {
        compose.onAllNodesWithText(f.text(R.string.airpods_ui_pending)).onFirst()
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun scalarClickKeepsOldSelectionPendingUntilDecodedRxChangesIt() {
        HostProtocolFixture().use { f ->
            f.start(); mount(f, CommandProperty.MIC_MODE)
            val automatic = f.text(optionLabels.getValue("automatic"))
            val left = f.text(optionLabels.getValue("left"))
            compose.onNodeWithText(automatic).assertIsSelected()
            compose.onNodeWithText(left).performClick()
            compose.waitUntil(4_000) { f.transport.count(HostProtocolWire.MIC_LEFT) == 1 }
            f.refresh()
            pendingVisible(f)
            compose.onNodeWithText(automatic).assertIsSelected()
            // FluidListRow exposes Selected only for the selected row.
            compose.onNodeWithText(left).assert(isSelected().not())
            assertEquals(PropertyValue.Number(0), f.observed(CommandProperty.MIC_MODE))
            f.receive(HostProtocolWire.MIC_LEFT, CommandProperty.MIC_MODE, PropertyValue.Number(2))
            compose.onNodeWithText(left).assertIsSelected()
            compose.onNodeWithText(automatic).assert(isSelected().not())
            compose.onNodeWithText(f.text(R.string.airpods_ui_observed)).assertIsDisplayed()
            assertEquals(1, f.transport.count(HostProtocolWire.MIC_LEFT))
        }
    }

    @Test fun multibyteNumberEditorSendsPinnedChimePayloadAndShowsObservedDifferentValue() {
        HostProtocolFixture().use { f ->
            f.start(); mount(f, CommandProperty.CHIME_VOLUME)
            val title = f.text(featureTitles.getValue("aacp.chime_volume"))
            compose.onNode(hasSetTextAction() and hasText(title)).performTextInput("37")
            compose.onNodeWithText(f.text(R.string.airpods_ui_apply)).performScrollTo().performClick()
            compose.waitUntil(4_000) { f.transport.count(HostProtocolWire.CHIME_37) == 1 }
            f.refresh()
            pendingVisible(f)
            assertNull(f.observed(CommandProperty.CHIME_VOLUME))
            f.receive(HostProtocolWire.CHIME_40, CommandProperty.CHIME_VOLUME, PropertyValue.Number(40))
            compose.onNodeWithText("$title · 40").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(f.text(R.string.airpods_ui_observed)).performScrollTo().assertIsDisplayed()
            assertEquals(CommandOutcome.OBSERVED_OTHER, f.host.state.value.domain.outcomes[CommandProperty.CHIME_VOLUME])
        }
    }

    @Test fun renameEditorSendsUtf8ByteLengthAndIdentityStaysOldUntilInformation() {
        HostProtocolFixture().use { f ->
            f.start(); mount(f, page = AirPodsSettingsPage.Rename)
            compose.onNode(hasSetTextAction() and hasText(f.text(R.string.airpods_ui_name)))
                .performScrollTo().performTextInput("Caffè")
            compose.onNode(hasText(f.text(R.string.airpods_feature_setting_device_rename)) and hasClickAction())
                .performScrollTo().performClick()
            compose.waitUntil(4_000) { f.transport.count(HostProtocolWire.RENAME_CAFFE) == 1 }
            f.refresh()
            pendingVisible(f)
            assertEquals("Pods", f.host.state.value.identity?.name)
            compose.onNodeWithText("Apple Inc. · Pods", substring = true).performScrollTo().assertIsDisplayed()
            f.receive(HostProtocolWire.INFORMATION_CAFFE, CommandProperty.NAME, PropertyValue.Name("Caffè"))
            compose.onNodeWithText("Apple Inc. · Caffè", substring = true).performScrollTo().assertIsDisplayed()
            assertEquals(CommandOutcome.OBSERVED_MATCH, f.host.state.value.domain.outcomes[CommandProperty.NAME])
            assertEquals(1, f.transport.count(HostProtocolWire.RENAME_CAFFE))
        }
    }

    @Test fun eqEditorDraftIsNotRxAndDifferentDecodedEqReplacesTheDraft() {
        HostProtocolFixture().use { f ->
            f.start()
            val previous = PropertyValue.Eq(false, 50, 50, 50)
            f.receive(HostProtocolWire.EQ_OLD, CommandProperty.EQ, previous)
            mount(f, page = AirPodsSettingsPage.Equalizer)
            // The EQ has its own page: its On is the only one, and its four Apply are the only ones.
            compose.onAllNodesWithText(f.text(R.string.airpods_ui_on)).onLast().performScrollTo().performClick()
            fun band(resource: Int, value: Float, applyOffsetFromEnd: Int) {
                compose.onNodeWithContentDescription(f.text(resource)).performScrollTo()
                    .performSemanticsAction(SemanticsActions.SetProgress) { set -> assertTrue(set(value)) }
                // Current production Settings: low/mid/high/final-EQ are the final four Apply
                // buttons (phone volume is unavailable with the deliberately absent media port).
                val applies = compose.onAllNodes(hasText(f.text(R.string.airpods_ui_apply)) and hasClickAction())
                val count = applies.fetchSemanticsNodes().size
                assertTrue("EQ band Apply locator requires four trailing buttons", count >= 4)
                applies[count - applyOffsetFromEnd].performScrollTo().performClick()
            }
            band(R.string.airpods_ui_low, 0f, 4)
            band(R.string.airpods_ui_high, 100f, 2)
            assertEquals(previous, f.observed(CommandProperty.EQ))
            assertEquals(0, f.transport.count(HostProtocolWire.EQ_NEW)) // band draft commits do not send AACP
            compose.onAllNodes(hasText(f.text(R.string.airpods_ui_apply)) and hasClickAction())
                .onLast().performScrollTo().performClick()
            compose.waitUntil(4_000) { f.transport.count(HostProtocolWire.EQ_NEW) == 1 }
            f.refresh()
            pendingVisible(f)
            assertEquals(previous, f.observed(CommandProperty.EQ))
            val other = PropertyValue.Eq(false, 10, 20, 30)
            f.receive(HostProtocolWire.EQ_OTHER, CommandProperty.EQ, other)
            assertEquals(CommandOutcome.OBSERVED_OTHER, f.host.state.value.domain.outcomes[CommandProperty.EQ])
            compose.onNodeWithContentDescription(f.text(R.string.airpods_ui_low)).performScrollTo()
                .assertRangeInfoEquals(androidx.compose.ui.semantics.ProgressBarRangeInfo(10f, 0f..100f))
            compose.onNodeWithContentDescription(f.text(R.string.airpods_ui_high)).performScrollTo()
                .assertRangeInfoEquals(androidx.compose.ui.semantics.ProgressBarRangeInfo(30f, 0f..100f))
            assertEquals(1, f.transport.count(HostProtocolWire.EQ_NEW))
        }
    }
}
