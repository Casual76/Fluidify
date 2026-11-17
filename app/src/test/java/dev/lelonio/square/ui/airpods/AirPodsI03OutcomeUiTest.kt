// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.ui.airpods

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.antigravity.fluidengine.ui.fluid.*
import dev.lelonio.square.R
import dev.lelonio.square.airpods.AacpStage
import dev.lelonio.square.airpods.AirPodsState
import dev.lelonio.square.airpods.runtime.android.ControlChannelRecovery
import dev.lelonio.square.airpods.runtime.android.platform.*
import dev.lelonio.square.ui.theme.SquareTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** PREPARED ONLY. Real UI input/recomposition; no Android Settings or hardware success proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AirPodsI03OutcomeUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun text(id: Int) = context.getString(id)

    private fun mount(content: @Composable () -> Unit) {
        compose.setContent {
            val backdrop = rememberGlassBackdrop()
            SquareTheme(darkTheme = false, amoled = false, systemBars = false) {
                CompositionLocalProvider(LocalGlassBackdrop provides backdrop,
                    LocalFluidMotionPolicy provides FluidMotionPolicy(0f)) {
                    Box(Modifier.fillMaxSize()) { content() }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun settingsRequiredAfterTap(operation: AudioOperation) {
        val setup = if (operation == AudioOperation.CONNECT) SetupAction.CONNECT else SetupAction.DISCONNECT
        var state by mutableStateOf(AirPodsUiState(bluetoothGranted = true,
            profileConnected = operation == AudioOperation.DISCONNECT,
            availability = listOf(setup, SetupAction.BLUETOOTH_SETTINGS).associate { "setup.$it" to UiAvailability(true) }))
        val actions = mutableListOf<AirPodsUiAction>()
        mount {
            AirPodsSettingsContent(state, { action ->
                actions += action
                if (action == AirPodsUiAction.Setup(setup)) state = state.copy(
                    audioResult = PlatformResult.SettingsRequired(PlatformFailure.HIDDEN_API_UNAVAILABLE))
            }, page = AirPodsSettingsPage.Setup)
        }
        compose.onNodeWithTag("airpods-setup-$setup").performScrollTo().performClick()
        compose.onNodeWithTag("airpods-audio-outcome").performScrollTo()
            .assertIsDisplayed().assertTextEquals(text(R.string.wf_i03_audio_settings))
        compose.onNodeWithTag("airpods-audio-settings").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(AirPodsUiAction.Setup(setup), AirPodsUiAction.Setup(SetupAction.BLUETOOTH_SETTINGS)), actions)
            assertEquals(operation == AudioOperation.DISCONNECT, state.profileConnected)
            assertFalse(state.controlsReady)
        }
    }

    @Test fun connectFallbackOffersAReachableBluetoothActionWithoutInventingConnection() = settingsRequiredAfterTap(AudioOperation.CONNECT)
    @Test fun disconnectFallbackOffersAReachableBluetoothActionWithoutInventingDisconnection() = settingsRequiredAfterTap(AudioOperation.DISCONNECT)

    @Test fun deferredFailureReplacesWaitingInThePlayerQuickPaneAndKeepsRecoveryReachable() {
        var state by mutableStateOf(AirPodsUiState(audioResult = PlatformResult.Queued,
            availability = mapOf("setup.BLUETOOTH_SETTINGS" to UiAvailability(true))))
        val actions = mutableListOf<AirPodsUiAction>()
        mount { AirPodsQuickContent(state, actions::add) }
        compose.onNodeWithTag("airpods-audio-outcome").assertTextEquals(text(R.string.wf_i03_audio_waiting))
        compose.runOnIdle { state = state.copy(audioResult = PlatformResult.SettingsRequired(PlatformFailure.REJECTED)) }
        compose.onNodeWithTag("airpods-audio-outcome").assertTextEquals(text(R.string.wf_i03_audio_settings)).assertIsDisplayed()
        compose.onNodeWithTag("airpods-audio-settings").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(AirPodsUiAction.Setup(SetupAction.BLUETOOTH_SETTINGS)), actions) }
    }

    @Test fun permissionReturnShowsPartialThenGrantedAndRevokedStatesInsteadOfEndlessConfigure() {
        var state by mutableStateOf(AirPodsUiState(
            availability = mapOf("setup.CALL_PERMISSION" to UiAvailability(true))))
        val actions = mutableListOf<AirPodsUiAction>()
        mount { AirPodsSettingsContent(state, { actions += it }, page = AirPodsSettingsPage.Calls) }
        compose.onNodeWithTag("airpods-setup-CALL_PERMISSION").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(AirPodsUiAction.Setup(SetupAction.CALL_PERMISSION)), actions)
            state = state.copy(callAccess = CallGestureAccess(readPhoneState = true))
        }
        compose.onNodeWithTag("airpods-call-access-state").performScrollTo()
            .assertTextEquals(text(R.string.wf_i03_call_access_answer_missing))
        compose.onNodeWithTag("airpods-setup-CALL_PERMISSION").assertExists()
        compose.runOnIdle { state = state.copy(callAccess = CallGestureAccess(true, true)) }
        compose.onNodeWithTag("airpods-call-access-state").performScrollTo()
            .assertTextEquals(text(R.string.wf_i03_call_access_granted)).assertIsDisplayed()
        compose.onNodeWithTag("airpods-setup-CALL_PERMISSION").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(callAccess = CallGestureAccess(answerPhoneCalls = true)) }
        compose.onNodeWithTag("airpods-call-access-state").performScrollTo()
            .assertTextEquals(text(R.string.wf_i03_call_access_phone_missing))
        compose.onNodeWithTag("airpods-setup-CALL_PERMISSION").assertExists()
        compose.runOnIdle { assertEquals(1, actions.size) }
    }

    @Test fun oneIdentityNotePreservesExplicitCandidateSelectionAndVerification() {
        val state = AirPodsUiState(bluetoothGranted = true, selectedCandidateId = "first",
            candidates = listOf(PairCandidate("first", "Same label", "Repeated warning"),
                PairCandidate("second", "Same label", "Repeated warning")),
            availability = mapOf("host.select" to UiAvailability(true), "setup.VERIFY_SELECTED" to UiAvailability(true)))
        val actions = mutableListOf<AirPodsUiAction>()
        mount { AirPodsSettingsContent(state, { actions += it }, page = AirPodsSettingsPage.Setup) }
        compose.onAllNodesWithTag("airpods-identity-note").assertCountEquals(1)
        compose.onNodeWithTag("airpods-candidate-first").assertIsSelected()
        compose.onNodeWithTag("airpods-candidate-second").assertIsNotSelected().performClick()
        compose.onNodeWithTag("airpods-candidate-first").assertIsSelected() // selection is a host fact
        compose.onNodeWithTag("airpods-setup-VERIFY_SELECTED").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(AirPodsUiAction.SelectPair("second"), AirPodsUiAction.Setup(SetupAction.VERIFY_SELECTED)), actions)
            assertFalse(state.verified)
            assertFalse(state.enabled)
        }
    }

    @Test fun i01RecoveryRendersAfterTeardownAndReturnsToMeasuredConnectionState() {
        var state by mutableStateOf(AirPodsUiState(domain = AirPodsState(stage = AacpStage.CLOSED),
            bluetoothGranted = true, profileConnected = true, productGatePassed = true,
            controlRecovery = ControlChannelRecovery.WAITING))
        mount { AirPodsSettingsContent(state, {}) }
        compose.onNodeWithTag("airpods-summary-state").performScrollTo()
            .assertTextEquals(text(R.string.airpods_ui_aacp_error)).assertIsDisplayed()
        compose.runOnIdle { state = state.copy(controlRecovery = ControlChannelRecovery.RECONNECTING) }
        compose.onNodeWithTag("airpods-summary-state").assertTextEquals(text(R.string.airpods_feature_connecting))
        compose.runOnIdle { state = state.copy(suppressed = true) }
        compose.onNodeWithTag("airpods-summary-state").assertTextEquals(text(R.string.airpods_feature_stopped))
        compose.runOnIdle { state = uiFixture() }
        compose.runOnIdle { assertEquals(R.string.airpods_feature_connected, state.connectionSummary()) }
        compose.onNodeWithTag("airpods-summary-state").assertDoesNotExist()
        compose.onNodeWithTag("airpods-mode-2").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("airpods-mode-3").performScrollTo().assertHasClickAction()
    }

    @Test fun authorizedEverydayPageOpensSetupAndDiagnosticsDirectly() {
        val pages = mutableListOf<AirPodsSettingsPage>()
        mount { AirPodsSettingsContent(uiFixture(), {}, onOpen = pages::add) }
        compose.onNodeWithTag("airpods-open-Setup").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("airpods-open-Diagnostics").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(AirPodsSettingsPage.Setup, AirPodsSettingsPage.Diagnostics), pages) }
    }
}
