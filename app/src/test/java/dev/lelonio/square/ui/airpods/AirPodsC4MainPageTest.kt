// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.ui.airpods

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.antigravity.fluidengine.ui.fluid.*
import dev.lelonio.square.R
import dev.lelonio.square.airpods.*
import dev.lelonio.square.airpods.protocol.*
import dev.lelonio.square.ui.theme.SquareTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Prepared only. Real Compose/Engine controls, synthetic state, no radio or hardware claims. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = "w800dp-h1000dp-mdpi")
class AirPodsC4MainPageTest {
    @get:Rule val compose = createComposeRule()

    private fun mount(content: @Composable () -> Unit) {
        compose.setContent {
            SquareTheme(darkTheme = false, amoled = false, systemBars = false) {
                val backdrop = rememberGlassBackdrop()
                val modal = rememberFluidGlassModalHostState()
                CompositionLocalProvider(LocalGlassBackdrop provides backdrop,
                    LocalFluidGlassModalHostState provides modal,
                    LocalFluidMotionPolicy provides FluidMotionPolicy(0f)) {
                    Box(Modifier.fillMaxSize()) { content() }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun ready(): AirPodsUiState = uiFixture().copy(availability = buildMap {
        AirPodsProperties.controls.forEach { put("property.${it.property}", UiAvailability(true)) }
        LocalToggle.entries.forEach {
            put("local.$it.enable", UiAvailability(true)); put("local.$it.disable", UiAvailability(true))
        }
    })

    @Test fun mainHasSimpleEffectiveSwitchesAndAnInlineAdaptiveSliderWithoutAConfirmation() {
        val actions = mutableListOf<AirPodsUiAction>()
        val base = ready()
        val state = base.copy(domain = base.domain.copy(desired = mapOf(CommandProperty.CONVERSATION to
            DesiredProperty(PropertyValue.Number(1), 1, base.domain.generation, DesiredStatus.WRITING, base.nowMs))))
        mount { AirPodsSettingsContent(state, actions::add) }
        for (property in listOf(CommandProperty.CONVERSATION, CommandProperty.EAR_DETECTION_CONFIG,
            CommandProperty.AUTOMATIC_CONNECTION_CONFIG, CommandProperty.ADAPTIVE_VOLUME_CONFIG,
            CommandProperty.PPE_TOGGLE_CONFIG, CommandProperty.DYNAMIC_END_OF_CHARGE)) {
            compose.onNodeWithTag("airpods-switch-$property").performScrollTo().assertIsEnabled()
        }
        compose.onNodeWithTag("airpods-switch-${CommandProperty.CONVERSATION}")
            .performScrollTo().assertIsOn().performClick()
        assertEquals(listOf(AirPodsUiAction.Write(base.domain.generation, CommandProperty.CONVERSATION,
            PropertyValue.Number(2))), actions)
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.onAllNodesWithText(context.getString(R.string.airpods_ui_pending)).assertCountEquals(0)
        compose.onAllNodesWithText(context.getString(R.string.airpods_ui_not_confirmed)).assertCountEquals(0)
        compose.onNodeWithTag("airpods-main-failure").assertDoesNotExist()
        compose.onNodeWithTag("airpods-open-AdaptiveStrength").assertDoesNotExist()
        compose.onNodeWithTag("airpods-property-${CommandProperty.AUTO_ANC_STRENGTH}").assertExists()
        for (page in listOf(AirPodsSettingsPage.Setup, AirPodsSettingsPage.Automations)) {
            compose.onNodeWithTag("airpods-open-$page").performScrollTo().assertHasClickAction()
        }
    }

    @Test fun adaptiveTalkBackSendsTheNaturalFinalValueExactlyOnceAndFollowsLaterRx() {
        val actions = mutableListOf<AirPodsUiAction>()
        var state by mutableStateOf(ready())
        val property = CommandProperty.AUTO_ANC_STRENGTH
        mount { AirPodsSettingsContent(state, actions::add) }
        val slider = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress) and
            hasAnyAncestor(hasTestTag("airpods-property-$property")))
        slider.performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(25f) }
        assertEquals(listOf(AirPodsUiAction.Write(state.domain.generation, property, PropertyValue.Number(25))), actions)
        compose.runOnIdle {
            state = state.copy(domain = state.domain.copy(properties = state.domain.properties +
                (property to Received<PropertyValue>(PropertyValue.Number(70), state.nowMs, state.domain.generation))))
        }
        assertEquals(70f, slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current)
        assertEquals(1, actions.size)
    }

    @Test fun aModelWithoutAdaptiveAudioHasNoAdaptiveSlider() {
        val base = ready()
        val state = base.copy(domain = base.domain.copy(identityReceived =
            Received(AirPodsIdentity("A2084", "Apple Inc.", "fixture"), base.nowMs, base.domain.generation)))
        mount { AirPodsSettingsContent(state, {}) }
        compose.onNodeWithTag("airpods-property-${CommandProperty.AUTO_ANC_STRENGTH}").assertDoesNotExist()
        compose.onNodeWithTag("airpods-switch-${CommandProperty.PPE_TOGGLE_CONFIG}").assertDoesNotExist()
    }

    @Test fun dragKeepsItsDraftAcrossIncomingValuesAndReleaseEmitsTheFinalValueOnce() {
        var effective by mutableIntStateOf(20)
        val sent = mutableListOf<Int>()
        mount {
            AirPodsLiveSlider(effective, sent::add, Modifier.width(320.dp).testTag("live"), debounceMs = 150L)
        }
        val slider = compose.onNodeWithTag("live")
        slider.performTouchInput {
            down(Offset(width * 0.2f, centerY))
            moveTo(Offset(width * 0.8f, centerY), delayMillis = 20)
        }
        val draft = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
        assertTrue(draft > 60f)
        compose.runOnIdle { effective = 5 }
        assertEquals(draft, slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current)
        val beforeRelease = sent.toList()
        assertTrue(beforeRelease.isEmpty() || beforeRelease == listOf(draft.toInt()))
        slider.performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(beforeRelease + listOf(draft.toInt()), sent)
        assertEquals(5f, slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current)
    }

    @Test fun disabledSliderHasNoTalkBackSetProgressAction() {
        val sent = mutableListOf<Int>()
        mount { AirPodsLiveSlider(20, sent::add, Modifier.testTag("live"), enabled = false, title = "Strength") }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).assertCountEquals(0)
        assertTrue(sent.isEmpty())
    }

    @Test fun aPartialSaveIsExplicitWithoutClaimingADeviceConfirmation() {
        val state = ready().copy(mainToggleIssue = AirPodsMainToggleIssue(CommandProperty.CONVERSATION,
            AirPodsMainToggleFailure.DEVICE_NOT_SENT))
        mount { AirPodsSettingsContent(state, {}) }
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.onNodeWithText(context.getString(R.string.airpods_c4_partial_save)).assertExists()
        compose.onAllNodesWithText(context.getString(R.string.airpods_ui_pending)).assertCountEquals(0)
    }
    @Test fun quickPanelExplainsSaveFailureAndMasterOff() {
        val state = ready().copy(mainToggleIssue = AirPodsMainToggleIssue(CommandProperty.CONVERSATION,
            AirPodsMainToggleFailure.LOCAL_SAVE_FAILED))
        mount { AirPodsQuickContent(state, {}) }
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.onNodeWithText(context.getString(R.string.airpods_c4_save_failed)).assertExists()
        compose.onNodeWithText(context.getString(R.string.airpods_reason_automations_off)).assertExists()
    }

}
