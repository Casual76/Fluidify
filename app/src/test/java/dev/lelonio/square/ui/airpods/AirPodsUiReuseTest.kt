// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.ui.airpods

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.antigravity.fluidengine.ui.fluid.*
import dev.lelonio.square.R
import dev.lelonio.square.airpods.AacpStage
import dev.lelonio.square.airpods.CommandProperty
import dev.lelonio.square.airpods.PendingCommand
import dev.lelonio.square.airpods.Received
import dev.lelonio.square.airpods.protocol.AirPodsProperties
import dev.lelonio.square.airpods.protocol.PropertyValue
import dev.lelonio.square.ui.theme.SquareTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * PREPARED, NOT COMPILED OR EXECUTED by the writer. Synthetic software state on the real Engine
 * components and portal; no runtime owner, radio, media or hardware.
 *
 * This replaces AirPodsUiGraphicsTest, which measured the layout that was withdrawn. What that
 * file held true and still matters is held here on the surfaces as they are now: every setting
 * is on a page, the gate is stated and nothing behind it is offered, an unknown is never a
 * number, a request is never shown as an answer, a drag is a request and not a selection, and
 * the capsule does one thing.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = "w800dp-h1000dp-mdpi")
class AirPodsUiReuseTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun label(id: Int) = context.getString(id)
    private val registry = AirPodsProperties.controls.filterNot { it.internalDependency }

    private fun mount(font: Float = 1f, content: @Composable () -> Unit) {
        compose.setContent {
            val backdrop = rememberGlassBackdrop()
            val host = rememberFluidGlassModalHostState()
            val density = LocalDensity.current
            SquareTheme(darkTheme = false, amoled = false, systemBars = false) {
                CompositionLocalProvider(LocalDensity provides Density(density.density, font),
                    LocalGlassBackdrop provides backdrop, LocalFluidGlassModalHostState provides host,
                    LocalFluidMotionPolicy provides FluidMotionPolicy(0f)) {
                    Box(Modifier.requiredSize(360.dp, 640.dp)) {
                        Row(Modifier.fillMaxSize().glassBackdropSource(backdrop)) {
                            Box(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.primaryContainer))
                            Box(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.surface))
                        }
                        content()
                        FluidGlassModalHost(host, backdrop = backdrop)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun received(vararg values: Pair<CommandProperty, Int>): AirPodsUiState {
        val base = uiFixture()
        return base.copy(domain = base.domain.copy(properties = base.domain.properties + values.associate { (p, v) ->
            p to Received<PropertyValue>(PropertyValue.Number(v), base.nowMs, base.domain.generation) }))
    }

    // ---- every setting is on a page -------------------------------------------------------

    @Test fun everyPublicRegistryControlIsDrawnOnThePageItIsFiledUnder() {
        var page by mutableStateOf(AirPodsSettingsPage.Main)
        mount { AirPodsSettingsContent(uiFixture(), {}, page = page) }
        for (target in AirPodsSettingsPage.entries) {
            compose.runOnIdle { page = target }
            compose.waitForIdle()
            for (spec in airPodsPageSpecs(target)) {
                // The fixture is a model with every capability, so nothing is hidden as not applicable.
                val title = label(featureTitles.getValue(spec.catalogId))
                assertTrue("${spec.catalogId} is missing from $target",
                    compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty())
            }
        }
        assertEquals(registry.size, AirPodsSettingsPage.entries.sumOf { airPodsPageSpecs(it).size })
    }

    @Test fun everyRowOfTheMainPageOpensThePageItNames() {
        val opened = mutableListOf<AirPodsSettingsPage>()
        mount { AirPodsSettingsContent(uiFixture(), {}, onOpen = opened::add) }
        val behindRows = AirPodsSettingsPage.entries - AirPodsSettingsPage.Main -
            AirPodsSettingsPage.LongPressLeft - AirPodsSettingsPage.LongPressRight
        for (page in behindRows) compose.onNodeWithTag("airpods-open-$page").performScrollTo().performClick()
        compose.onNodeWithTag("airpods-gesture-LEFT").performScrollTo().performClick()
        compose.onNodeWithTag("airpods-gesture-RIGHT").performScrollTo().performClick()
        assertEquals(behindRows + AirPodsSettingsPage.LongPressLeft + AirPodsSettingsPage.LongPressRight, opened)
    }

    // ---- the gate, and a session that is not there -----------------------------------------

    @Test fun gateIsStatedAtTheTopWhileOffAndNoDeviceControlIsOffered() {
        mount { AirPodsSettingsContent(uiFixture().copy(productGatePassed = false), {}) }
        compose.onNodeWithTag("airpods-gate").assertIsDisplayed()
        compose.onNodeWithText(label(R.string.airpods_reason_gate)).assertIsDisplayed()
        compose.onAllNodesWithText(label(R.string.airpods_feature_connected)).assertCountEquals(0)
        for (mode in 1..4) compose.onNodeWithTag("airpods-mode-$mode").assertDoesNotExist()
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
        // What the pair needs first stays reachable, and so does leaving.
        compose.onNodeWithTag("airpods-open-${AirPodsSettingsPage.Setup}").performScrollTo().assertHasClickAction()
        compose.onNodeWithText(label(R.string.airpods_feature_forget)).performScrollTo().assertExists()
    }

    @Test fun withTheGatePassedThereIsNoGateNoticeAndTheReceivedModeIsTheThumb() {
        mount { AirPodsSettingsContent(uiFixture(), {}) }
        compose.onNodeWithTag("airpods-gate").assertDoesNotExist()
        compose.onNodeWithTag("airpods-summary-state")
            .assertTextContains(label(R.string.airpods_feature_connected))
        compose.onNodeWithTag("airpods-mode-2").performScrollTo().assertIsSelected()
        for (mode in listOf(1, 3, 4)) compose.onNodeWithTag("airpods-mode-$mode").assertIsNotSelected()
    }

    @Test fun aLiveSessionRemainsVisibleWhenAllBatteryReadingsHaveExpired() {
        mount { AirPodsSettingsContent(uiFixture().copy(nowMs = 301_000), {}) }
        compose.onNodeWithTag("airpods-summary-state")
            .assertTextContains(label(R.string.airpods_feature_connected))
        compose.onAllNodesWithText("80%").assertCountEquals(0)
        compose.onNode(hasTestTag("airpods-battery-4") and hasAnyDescendant(
            hasText(label(R.string.airpods_ui_missing))), useUnmergedTree = true).assertExists()
    }

    @Test fun masterOffDisablesDependentSettingsWithoutDiscardingTheirSavedValues() {
        val base = uiFixture()
        val actions = mutableListOf<AirPodsUiAction>()
        val state = base.copy(pair = base.pair.copy(host = base.pair.host.copy(
            automationsEnabled = false, earPause = true, earResume = true)),
            availability = LocalToggle.entries.associate { "local.$it" to UiAvailability(true) })
        mount { AirPodsSettingsContent(state, actions::add, page = AirPodsSettingsPage.Automations) }
        for (toggle in listOf(LocalToggle.EAR_PAUSE, LocalToggle.EAR_RESUME)) {
            compose.onNode(hasText(label(toggleLabels.getValue(toggle))) and isToggleable())
                .performScrollTo().assertIsOn().assertIsNotEnabled()
        }
        compose.onNode(hasText(label(toggleLabels.getValue(LocalToggle.AUTOMATIONS))) and isToggleable())
            .performScrollTo().assertIsOff().assertIsEnabled().performTouchInput { click() }
        assertEquals(listOf<AirPodsUiAction>(AirPodsUiAction.Toggle(LocalToggle.AUTOMATIONS, true)), actions)
    }

    @Test fun existingSeparateConsentsCanStillBeRevokedWhileMasterIsOff() {
        val base = uiFixture()
        val actions = mutableListOf<AirPodsUiAction>()
        val state = base.copy(pair = base.pair.copy(host = base.pair.host.copy(
            automationsEnabled = false, otherAppsEnabled = true, fluidifyBackgroundEnabled = true)),
            availability = LocalToggle.entries.associate { "local.$it" to UiAvailability(true) })
        mount { AirPodsSettingsContent(state, actions::add, page = AirPodsSettingsPage.Automations) }
        for (toggle in listOf(LocalToggle.OTHER_APPS, LocalToggle.BACKGROUND)) {
            compose.onNode(hasText(label(toggleLabels.getValue(toggle))) and isToggleable())
                .performScrollTo().assertIsOn().assertIsEnabled().performTouchInput { click() }
        }
        assertEquals(listOf<AirPodsUiAction>(AirPodsUiAction.Toggle(LocalToggle.OTHER_APPS, false),
            AirPodsUiAction.Toggle(LocalToggle.BACKGROUND, false)), actions)
    }

    // ---- charge: received, unknown, stale ---------------------------------------------------

    @Test fun unknownChargeIsTheMissingMarkAndNeverZero() {
        mount { AirPodsSettingsContent(uiFixture(), {}) }
        compose.onAllNodesWithText("0%").assertCountEquals(0)
        val missing = label(R.string.airpods_ui_missing)
        // Left reported; right and case never did.
        compose.onNode(hasTestTag("airpods-battery-4") and hasAnyDescendant(hasText("80%")), useUnmergedTree = true).assertExists()
        compose.onNode(hasTestTag("airpods-battery-2") and hasAnyDescendant(hasText(missing)), useUnmergedTree = true).assertExists()
        compose.onNode(hasTestTag("airpods-battery-8") and hasAnyDescendant(hasText(missing)), useUnmergedTree = true).assertExists()
        compose.onAllNodesWithText("last known", substring = true).assertCountEquals(0)
    }

    @Test fun staleChargeLeavesItsCellUnknownAndIsShownOnlyAsHistory() {
        mount { AirPodsSettingsContent(uiFixture().copy(nowMs = 301_000), {}) }
        compose.onNode(hasTestTag("airpods-battery-4") and hasAnyDescendant(hasText(label(R.string.airpods_ui_missing))),
            useUnmergedTree = true).assertExists()
        compose.onAllNodesWithText("80%").assertCountEquals(0)
        compose.onNodeWithText("last known 80%", substring = true).assertExists()
    }

    @Test fun aMatchedPairIsSaidOnceWithTheLowerOfTheTwo() {
        val base = uiFixture()
        val state = base.copy(domain = base.domain.copy(batteries = mapOf(
            4 to Received(dev.lelonio.square.airpods.BatteryValue(91, 2), 1000, 7),
            2 to Received(dev.lelonio.square.airpods.BatteryValue(89, 2), 1000, 7))))
        mount { AirPodsSettingsContent(state, {}) }
        compose.onNode(hasTestTag("airpods-battery-pair") and hasAnyDescendant(hasText("89%")), useUnmergedTree = true).assertExists()
        compose.onAllNodesWithText("91%").assertCountEquals(0)
        assertTrue(earbudsReadAsOne(state.freshBattery(4), state.freshBattery(2)))
        assertFalse("An unknown side is never folded into the other", earbudsReadAsOne(state.freshBattery(4), null))
    }

    // ---- a request is not an answer -----------------------------------------------------------

    @Test fun whileARequestIsOutstandingTheReceivedAlternativeStaysSelectedAndNothingCanBeRequested() {
        val base = received(CommandProperty.MIC_MODE to 0)
        val state = base.copy(domain = base.domain.copy(pending = mapOf(CommandProperty.MIC_MODE to
            PendingCommand(2, base.nowMs, 1, generation = base.domain.generation))))
        val actions = mutableListOf<AirPodsUiAction>()
        mount { Column { PropertyEditor(state, requireNotNull(AirPodsProperties.spec(CommandProperty.MIC_MODE)), actions::add) } }
        compose.onNodeWithText(label(R.string.airpods_ui_pending)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.airpods_ui_automatic)).assertIsSelected().assert(hasClickAction().not())
        // "Left" is what was asked for. It is not selected, and asking again is not possible.
        compose.onNodeWithText(label(R.string.airpods_ui_left)).assertIsNotSelected().assert(hasClickAction().not())
        compose.onNodeWithText(label(R.string.airpods_ui_right)).assertIsNotSelected().assert(hasClickAction().not())
        compose.onNodeWithText(label(R.string.airpods_ui_left)).performTouchInput { click() }
        assertTrue(actions.isEmpty())
    }

    @Test fun aTapOnAModeAsksForItAndLeavesTheThumbOnTheReceivedMode() {
        val actions = mutableListOf<AirPodsUiAction>()
        mount { AirPodsQuickOverlay(uiFixture(), true, {}, actions::add, {}) }
        compose.onNodeWithTag("airpods-quick-MODE-3").performClick()
        assertEquals(listOf<AirPodsUiAction>(AirPodsUiAction.Write(7, CommandProperty.MODE, PropertyValue.Number(3))), actions)
        // The state did not change, because nothing was received: neither did the selection.
        compose.onNodeWithTag("airpods-quick-MODE-2").assertIsSelected()
        compose.onNodeWithTag("airpods-quick-MODE-3").assertIsNotSelected()
        // The received mode is not a request: touching it sends nothing.
        compose.onNodeWithTag("airpods-quick-MODE-2").assert(hasClickAction().not())
    }

    @Test fun draggingTheThumbToAnotherSlotIsOneRequestForThatSlotAndNotASelection() {
        val actions = mutableListOf<AirPodsUiAction>()
        mount { AirPodsQuickOverlay(uiFixture(), true, {}, actions::add, {}) }
        val from = compose.onNodeWithTag("airpods-quick-MODE-2").fetchSemanticsNode().boundsInRoot
        val to = compose.onNodeWithTag("airpods-quick-MODE-3").fetchSemanticsNode().boundsInRoot
        val distance = to.center.x - from.center.x
        compose.onNodeWithTag("airpods-quick-MODE-2").performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(distance / 10f, 0f)) }
            // Nothing is asked for while the finger is down.
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf<AirPodsUiAction>(AirPodsUiAction.Write(7, CommandProperty.MODE, PropertyValue.Number(3))), actions)
        compose.onNodeWithTag("airpods-quick-MODE-2").assertIsSelected()
        compose.onNodeWithTag("airpods-quick-MODE-3").assertIsNotSelected()
    }

    @Test fun aDragThatEndsWhereItBeganAsksForNothing() {
        val actions = mutableListOf<AirPodsUiAction>()
        mount { AirPodsQuickOverlay(uiFixture(), true, {}, actions::add, {}) }
        val slot = compose.onNodeWithTag("airpods-quick-MODE-2").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("airpods-quick-MODE-2").performTouchInput {
            down(center)
            repeat(5) { moveBy(Offset(-slot.width / 4f, 0f)) }
            repeat(5) { moveBy(Offset(slot.width / 4f, 0f)) }
            up()
        }
        compose.waitForIdle()
        assertTrue(actions.isEmpty())
    }

    @Test fun whileAModeIsAwaitedTheReceivedOneIsStillTheThumbAndNoModeCanBeAskedFor() {
        val base = uiFixture()
        val state = base.copy(domain = base.domain.copy(pending = mapOf(CommandProperty.MODE to
            PendingCommand(3, base.nowMs, 1, generation = base.domain.generation))))
        val actions = mutableListOf<AirPodsUiAction>()
        mount { AirPodsQuickOverlay(state, true, {}, actions::add, {}) }
        compose.onNodeWithText(label(R.string.airpods_ui_pending)).assertIsDisplayed()
        compose.onNodeWithTag("airpods-quick-MODE-2").assertIsSelected()
        for (mode in 1..4) compose.onNodeWithTag("airpods-quick-MODE-$mode").assert(hasClickAction().not())
        compose.onNodeWithTag("airpods-quick-MODE-3").assertIsNotSelected().performTouchInput { click() }
        assertTrue(actions.isEmpty())
    }

    @Test fun quickSaysWhatIsMissingOnlyWhenTheSessionIsNotReady() {
        var error by mutableStateOf(false)
        mount {
            val base = uiFixture()
            AirPodsQuickOverlay(if (error) base.copy(domain = base.domain.copy(stage = AacpStage.ERROR)) else base,
                true, {}, {}, {})
        }
        compose.onAllNodesWithText(label(R.string.airpods_feature_connected)).assertCountEquals(0)
        compose.onAllNodesWithText(label(R.string.airpods_ui_aacp_error)).assertCountEquals(0)
        compose.runOnIdle { error = true }
        compose.waitForIdle()
        compose.onNodeWithText(label(R.string.airpods_ui_aacp_error)).assertIsDisplayed()
        for (mode in 1..4) compose.onNodeWithTag("airpods-quick-MODE-$mode")
            .assertIsNotSelected().assert(hasClickAction().not())
    }

    // ---- the switch -------------------------------------------------------------------------

    @Test fun aSwitchIsTheReceivedValueAndATouchOnlyAsksForTheOther() {
        val actions = mutableListOf<AirPodsUiAction>()
        val title = label(featureTitles.getValue("aacp.conversation_detect_config"))
        mount { AirPodsQuickOverlay(received(CommandProperty.CONVERSATION to 1), true, {}, actions::add, {}) }
        val switch = compose.onNode(hasText(title) and isToggleable())
        switch.performScrollTo().assertIsOn()
        // The row keeps no click action in its semantics; the pointer is what a finger uses.
        switch.performTouchInput { click() }
        assertEquals(listOf<AirPodsUiAction>(AirPodsUiAction.Write(7, CommandProperty.CONVERSATION, PropertyValue.Number(2))), actions)
        compose.onNode(hasText(title) and isToggleable()).assertIsOn()
    }

    @Test fun withNothingReceivedThereIsNoSwitchPositionToShow() {
        val title = label(featureTitles.getValue("aacp.conversation_detect_config"))
        // Not ready, so nothing may be tried either: the row is the missing mark and no control.
        val state = uiFixture().let { it.copy(domain = it.domain.copy(stage = AacpStage.ERROR)) }
        mount { Column { PropertySwitchRow(state, requireNotNull(AirPodsProperties.spec(CommandProperty.CONVERSATION))) {} } }
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
        compose.onNode(hasText(title) and hasText(label(R.string.airpods_ui_missing))).assertExists()
            .assert(hasClickAction().not())
    }

    @Test fun withNothingReceivedButAValueThatMayBeTriedNeitherAlternativeIsSelected() {
        val actions = mutableListOf<AirPodsUiAction>()
        mount { Column { PropertySwitchRow(uiFixture(), requireNotNull(AirPodsProperties.spec(CommandProperty.CONVERSATION)), actions::add) } }
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
        compose.onNodeWithText(label(R.string.airpods_ui_candidate)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.airpods_ui_on)).assertIsNotSelected()
        compose.onNodeWithText(label(R.string.airpods_ui_off)).assertIsNotSelected().performClick()
        compose.waitForIdle()
        assertEquals(listOf<AirPodsUiAction>(AirPodsUiAction.Write(7, CommandProperty.CONVERSATION, PropertyValue.Number(2))), actions)
    }

    // ---- what left the quick pane is on its page ------------------------------------------------

    @Test fun theQuickPaneCarriesNoPreferencesAndNoSummaries() {
        mount { AirPodsQuickOverlay(uiFixture(), true, {}, {}, {}) }
        compose.onNodeWithTag("airpods-preferred-mode").assertDoesNotExist()
        compose.onNodeWithTag("airpods-host-summary").assertDoesNotExist()
        compose.onAllNodesWithText(label(R.string.airpods_feature_setting_host_volume), substring = true).assertCountEquals(0)
        compose.onNodeWithTag("airpods-all-settings").assertIsDisplayed()
    }

    @Test fun aDraftedNumberStandsBesideApplyAndNeverReplacesTheReceivedValue() {
        val actions = mutableListOf<AirPodsUiAction>()
        val state = uiFixture().copy(localVolumePercent = 40,
            availability = mapOf("host.volume" to UiAvailability(true)))
        val title = label(R.string.airpods_feature_setting_host_volume)
        mount { AirPodsSettingsContent(state, actions::add, page = AirPodsSettingsPage.Automations) }
        compose.onNodeWithContentDescription(title).performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { set -> assertTrue(set(55f)) }
        compose.onNodeWithText("$title · 40").assertExists()
        compose.onNodeWithText("55").assertExists()
        assertTrue("A draft is not a request", actions.isEmpty())
        // Host volume is the last number of the page: its Apply is the last enabled one.
        compose.onAllNodes(hasText(label(R.string.airpods_ui_apply)) and hasClickAction() and isEnabled())
            .onLast().performScrollTo().performClick()
        assertEquals(listOf<AirPodsUiAction>(AirPodsUiAction.LocalVolume(55)), actions)
        compose.onAllNodesWithText("55").assertCountEquals(0)
        compose.onNodeWithText("$title · 40").assertExists()
    }

    // ---- the capsule --------------------------------------------------------------------------------

    @Test fun theCapsuleIsOneTargetThatOpensThePaneAndNeverWrites() {
        val actions = mutableListOf<AirPodsUiAction>()
        var opens = 0
        var width by mutableStateOf(96)
        mount { AirPodsCapsule(AirPodsUiBinding(uiFixture(), actions::add, { opens++ }), Modifier.width(width.dp)) }
        // The widths Home (96, 120) and the player (160) give it.
        for ((index, next) in listOf(96, 120, 160).withIndex()) {
            compose.runOnIdle { width = next }
            compose.waitForIdle()
            compose.onNodeWithTag("airpods-capsule-quick", useUnmergedTree = true).assertDoesNotExist()
            val body = compose.onNodeWithTag("airpods-capsule-body", useUnmergedTree = true)
            val bounds = body.fetchSemanticsNode().boundsInRoot
            assertTrue("$next: one finger wide", bounds.width >= 48f)
            assertTrue("$next: one finger tall", bounds.height >= 48f)
            body.assertContentDescriptionContains("80%", substring = true).performClick()
            assertEquals(index + 1, opens)
            assertTrue(actions.isEmpty())
        }
    }

    @Test fun theCapsulePrintsReceivedChargeUnderEachPictureAndTheMissingMarkOtherwise() {
        mount { AirPodsCapsule(AirPodsUiBinding(uiFixture(), {}, {}), Modifier.width(120.dp)) }
        // The fixture has the left earbud only: its side is said, and the case is unknown, not zero.
        compose.onNodeWithTag("airpods-capsule-earbuds-text", useUnmergedTree = true).assertTextEquals("L 80%")
        compose.onNodeWithTag("airpods-capsule-case-text", useUnmergedTree = true)
            .assertTextEquals(label(R.string.airpods_ui_missing))
    }

    @Test fun aDisconnectedCapsuleStillOnlyOpensThePaneAndConnectingIsAskedThere() {
        val actions = mutableListOf<AirPodsUiAction>()
        var opens = 0
        val state = uiFixture().copy(profileConnected = false,
            availability = mapOf("setup.CONNECT" to UiAvailability(true)))
        var quick by mutableStateOf(false)
        mount {
            AirPodsCapsule(AirPodsUiBinding(state, actions::add, { opens++; quick = true }), Modifier.width(160.dp))
            AirPodsQuickOverlay(state, quick, { quick = false }, actions::add, {})
        }
        compose.onNodeWithTag("airpods-capsule-body", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals(1, opens)
        assertTrue("The capsule itself asks for nothing", actions.isEmpty())
        compose.onNodeWithText(label(R.string.airpods_feature_disconnected)).assertIsDisplayed()
        compose.onNodeWithTag("airpods-setup-CONNECT").performClick()
        assertEquals(listOf<AirPodsUiAction>(AirPodsUiAction.Setup(SetupAction.CONNECT)), actions)
    }

    // ---- the pictures -------------------------------------------------------------------------------

    @Test fun everyModelOfTheProtocolTableHasItsPicturesAndAnUnknownOneIsOnlyAPlaceholder() {
        for (model in dev.lelonio.square.airpods.protocol.AirPodsModels.models) for (number in model.numbers)
            assertNotNull("${model.name} ($number) has no pictures", AirPodsArtworks.find(number))
        assertNull(AirPodsArtworks.find(null))
        assertNull(AirPodsArtworks.find("A0000"))
        val unknown = AirPodsUiState().artworkChoice()
        assertFalse(unknown.known)
        assertNull("No model is named beside a placeholder", unknown.modelName)
        val known = uiFixture().artworkChoice()
        assertTrue(known.known)
        assertEquals("AirPods Pro 3", known.modelName)
    }
}
