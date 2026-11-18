// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.ui.airpods

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import dev.antigravity.fluidengine.ui.theme.FluidToneTileSize
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.antigravity.fluidengine.ui.fluid.*
import dev.lelonio.square.airpods.*
import dev.lelonio.square.airpods.protocol.*
import dev.lelonio.square.airpods.runtime.storage.AirPodsPairValues
import dev.lelonio.square.airpods.runtime.storage.SavedProperty
import dev.lelonio.square.ui.theme.SquareTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Prepared for I2; synthetic UI state, no claim of firmware acceptance. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = "w800dp-h1000dp-mdpi")
class AirPodsC3GesturePagesTest {
    @get:Rule val compose = createComposeRule()

    private fun mount(content: @Composable () -> Unit) {
        compose.setContent {
            val backdrop = rememberGlassBackdrop()
            val host = rememberFluidGlassModalHostState()
            SquareTheme(darkTheme = false, amoled = false, systemBars = false) {
                CompositionLocalProvider(LocalGlassBackdrop provides backdrop,
                    LocalFluidGlassModalHostState provides host,
                    LocalFluidMotionPolicy provides FluidMotionPolicy(0f)) {
                    Column(Modifier.verticalScroll(rememberScrollState())) { content() }
                }
            }
        }
    }

    private fun state(mask: Int = 6) = uiFixture().let { s ->
        s.copy(domain = s.domain.copy(properties = s.domain.properties +
            (CommandProperty.LISTENING_MODE_CONFIGS to Received(PropertyValue.Number(mask), 1000, 7))),
            gestureAssignments = mapOf(GestureSide.LEFT to GestureAction.NOISE_CONTROL,
                GestureSide.RIGHT to GestureAction.DIGITAL_ASSISTANT),
            availability = mapOf("gesture.LEFT" to UiAvailability(true), "gesture.RIGHT" to UiAvailability(true)))
    }

    @Test fun modeTouchSendsImmediatelyAndMinimumTwoDisablesRemoval() {
        val actions = mutableListOf<AirPodsUiAction>()
        mount { Column { LongPressPage(state(), GestureSide.LEFT, { actions += it }) } }
        compose.onNodeWithTag("airpods-cycle-icon-ANC", useUnmergedTree = true)
            .assertWidthIsEqualTo(FluidToneTileSize).assertHeightIsEqualTo(FluidToneTileSize)
        compose.onNodeWithTag("airpods-cycle-ANC").assertIsOn().assertIsNotEnabled()
        compose.onNodeWithTag("airpods-cycle-TRANSPARENCY").assertIsOn().assertIsNotEnabled()
        compose.onNodeWithTag("airpods-cycle-ADAPTIVE").performScrollTo().performClick()
        assertEquals(listOf(AirPodsUiAction.Write(7, CommandProperty.LISTENING_MODE_CONFIGS,
            PropertyValue.Number(14))), actions)
        compose.onAllNodesWithTag("airpods-stem-observation").assertCountEquals(0)
        compose.onAllNodesWithTag("airpods-stem-command").assertCountEquals(0)
    }

    @Test fun zeroMaskNeedsTwoExplicitTouchesAndNoInvalidWrite() {
        val actions = mutableListOf<AirPodsUiAction>()
        mount { Column { LongPressPage(state(0), GestureSide.LEFT, { actions += it }) } }
        compose.onNodeWithTag("airpods-cycle-TRANSPARENCY").performScrollTo().performClick()
        assertTrue(actions.isEmpty())
        compose.onNodeWithTag("airpods-cycle-ANC").performScrollTo().performClick()
        assertEquals(listOf(AirPodsUiAction.Write(7, CommandProperty.LISTENING_MODE_CONFIGS,
            PropertyValue.Number(6))), actions)
    }

    @Test fun cycleIsUnreadUntilCurrentRxAndRequestsOneSubscription() {
        val actions = mutableListOf<AirPodsUiAction>()
        val property = CommandProperty.LISTENING_MODE_CONFIGS
        val base = state()
        var shown by mutableStateOf(base.copy(
            availability = base.availability + ("setup.INFORMATION" to UiAvailability(true)),
            domain = base.domain.copy(properties = base.domain.properties - property,
                cachedValues = AirPodsPairValues(observed = mapOf(property to SavedProperty(PropertyValue.Number(8), 12))))))
        mount { Column { LongPressPage(shown, GestureSide.LEFT, actions::add) } }
        val anc = compose.onNodeWithTag("airpods-cycle-ANC").performScrollTo()
        val transparency = compose.onNodeWithTag("airpods-cycle-TRANSPARENCY")
        anc.assertIsNotEnabled().performTouchInput { click() }
        transparency.performScrollTo().assertIsNotEnabled().performTouchInput { click() }
        assertEquals(listOf(AirPodsUiAction.Setup(SetupAction.INFORMATION)), actions)

        compose.runOnIdle {
            shown = shown.copy(domain = shown.domain.copy(properties = shown.domain.properties +
                (property to Received(PropertyValue.Number(12), 1002, 6))))
        }
        anc.assertIsNotEnabled()
        compose.runOnIdle {
            shown = shown.copy(domain = shown.domain.copy(properties = shown.domain.properties +
                (property to Received(PropertyValue.Number(12), 1003, 7))))
        }
        anc.performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf(AirPodsUiAction.Setup(SetupAction.INFORMATION),
            AirPodsUiAction.Write(7, property, PropertyValue.Number(14))), actions)
    }

    @Test fun assistantHidesSharedCycle() {
        mount { Column { LongPressPage(state(), GestureSide.RIGHT, {}) } }
        compose.onNodeWithTag("airpods-gesture-RIGHT-DIGITAL_ASSISTANT").assertIsSelected()
        compose.onAllNodesWithTag("airpods-cycle-ANC").assertCountEquals(0)
    }

    @Test fun switchingFromAssistantRequestsUnreadCycleWithoutReopeningEditor() {
        val actions = mutableListOf<AirPodsUiAction>()
        val request = AirPodsUiAction.Setup(SetupAction.INFORMATION)
        val base = state()
        val assistant = mapOf(GestureSide.LEFT to GestureAction.DIGITAL_ASSISTANT)
        var shown by mutableStateOf(base.copy(gestureAssignments = assistant,
            availability = base.availability + ("setup.INFORMATION" to UiAvailability(true)),
            domain = base.domain.copy(properties = mapOf(CommandProperty.STEM_CONFIG to
                Received(PropertyValue.Number(8), 1001, 7)))))
        mount { Column { LongPressPage(shown, GestureSide.LEFT, actions::add) } }
        compose.onNodeWithTag("airpods-gesture-LEFT-DIGITAL_ASSISTANT").assertIsSelected()
        assertTrue(actions.isEmpty())
        compose.runOnIdle { shown = shown.copy(gestureAssignments = base.gestureAssignments) }
        compose.onNodeWithTag("airpods-cycle-ANC").performScrollTo().assertIsNotEnabled()
        assertEquals(listOf(request), actions)

        compose.runOnIdle { shown = shown.copy(gestureAssignments = assistant) }
        compose.onNodeWithTag("airpods-gesture-LEFT-DIGITAL_ASSISTANT").assertIsSelected()
        compose.runOnIdle { shown = shown.copy(gestureAssignments = base.gestureAssignments) }
        compose.onNodeWithTag("airpods-cycle-ANC").assertIsNotEnabled()
        assertEquals(listOf(request), actions)
    }

    @Test fun openEditorRequestsOnAdmissionAndAgainOnlyAfterCurrentStemReading() {
        val actions = mutableListOf<AirPodsUiAction>()
        val request = AirPodsUiAction.Setup(SetupAction.INFORMATION)
        val base = state()
        var shown by mutableStateOf(base.copy(domain = base.domain.copy(properties = emptyMap())))
        mount { Column { LongPressPage(shown, GestureSide.LEFT, actions::add) } }
        compose.waitForIdle()
        assertTrue(actions.isEmpty())

        compose.runOnIdle { shown = shown.copy(availability = shown.availability + ("setup.INFORMATION" to UiAvailability(true))) }
        compose.waitForIdle()
        assertEquals(listOf(request), actions)

        fun observeStem(at: Long) = compose.runOnIdle {
            shown = shown.copy(domain = shown.domain.copy(properties = mapOf(CommandProperty.STEM_CONFIG to
                Received(PropertyValue.Number(8), at, 7))))
        }
        observeStem(1001)
        compose.waitForIdle()
        assertEquals(listOf(request, request), actions)
        observeStem(1002)
        compose.waitForIdle()
        assertEquals(listOf(request, request), actions)
    }

    @Test fun ordinaryHeadPageDoesNotMountGraphAndDiagnosticsDoes() {
        var diagnostics by mutableStateOf(false)
        mount { Column { if (diagnostics) DiagnosticsPage(state(), {}) else HeadGesturesPage(state(), {}) } }
        compose.onAllNodesWithTag("airpods-head-diagnostics").assertCountEquals(0)
        compose.onNodeWithTag("airpods-gesture-NOD").assertExists()
        compose.runOnIdle { diagnostics = true }
        compose.onNodeWithTag("airpods-head-diagnostics").assertExists()
        compose.onNodeWithTag("airpods-tracking-start").assertExists()
    }

    @Test fun callsAndMicrophoneUseDirectChoicesAndKeepPermissionEntry() {
        val actions = mutableListOf<AirPodsUiAction>()
        mount { Column { CallsPage(state(), { actions += it }) } }
        compose.onNodeWithTag("airpods-choice-mute-once").assertIsSelected()
        compose.onNodeWithTag("airpods-choice-mute-twice").performScrollTo().performClick()
        compose.onNodeWithTag("airpods-choice-MIC_MODE-1").performScrollTo().performClick()
        assertEquals(listOf(AirPodsUiAction.Write(7, CommandProperty.CALL_MANAGEMENT_CONFIG, PropertyValue.Number(2)),
            AirPodsUiAction.Write(7, CommandProperty.MIC_MODE, PropertyValue.Number(1))), actions)
        compose.onNodeWithTag("airpods-setup-CALL_PERMISSION").assertExists()
    }
}
