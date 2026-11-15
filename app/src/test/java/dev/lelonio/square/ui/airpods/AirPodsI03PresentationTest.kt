// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.ui.airpods

import dev.lelonio.square.R
import dev.lelonio.square.airpods.AacpStage
import dev.lelonio.square.airpods.AirPodsState
import dev.lelonio.square.airpods.runtime.android.ControlChannelRecovery
import dev.lelonio.square.airpods.runtime.android.platform.PlatformFailure
import dev.lelonio.square.airpods.runtime.android.platform.PlatformResult
import org.junit.Assert.*
import org.junit.Test

/** PREPARED ONLY: no compiler, test runner, device or radio used by I03. */
class AirPodsI03PresentationTest {
    @Test fun controlRecoveryRemainsVisibleAfterRepositoryTeardown() {
        val waiting = AirPodsUiState(
            domain = AirPodsState(stage = AacpStage.CLOSED), bluetoothGranted = true,
            profileConnected = true, controlRecovery = ControlChannelRecovery.WAITING,
        )
        assertEquals(R.string.airpods_ui_aacp_error, waiting.connectionSummary())
        assertEquals(StatusKind.Problem, waiting.connectionKind())
        val retrying = waiting.copy(controlRecovery = ControlChannelRecovery.RECONNECTING)
        assertEquals(R.string.airpods_feature_connecting, retrying.connectionSummary())
        assertEquals(StatusKind.Waiting, retrying.connectionKind())
        for (recovery in listOf(waiting, retrying)) {
            assertEquals(R.string.airpods_feature_stopped, recovery.copy(suppressed = true).connectionSummary())
            assertEquals(R.string.airpods_feature_disconnected, recovery.copy(profileConnected = false).connectionSummary())
            assertEquals(R.string.airpods_reason_bluetooth_permission, recovery.copy(bluetoothGranted = false).connectionSummary())
        }
        assertEquals(R.string.airpods_feature_controls_unavailable,
            waiting.copy(controlRecovery = ControlChannelRecovery.NONE).connectionSummary())
    }

    @Test fun bothEffectiveCallGrantsAreRequiredAndEachMissingGrantIsNamed() {
        val denied = CallGestureAccess()
        val phoneOnly = CallGestureAccess(readPhoneState = true)
        val answerOnly = CallGestureAccess(answerPhoneCalls = true)
        val both = CallGestureAccess(true, true)
        assertFalse(denied.granted)
        assertFalse(phoneOnly.granted)
        assertFalse(answerOnly.granted)
        assertTrue(both.granted)
        assertEquals(R.string.wf_i03_call_access_both_missing, denied.summary())
        assertEquals(R.string.wf_i03_call_access_answer_missing, phoneOnly.summary())
        assertEquals(R.string.wf_i03_call_access_phone_missing, answerOnly.summary())
        assertEquals(R.string.wf_i03_call_access_granted, both.summary())
    }

    @Test fun androidRequestOutcomesNeverBecomeConfirmedAudioConnections() {
        val results = listOf(PlatformResult.RequestAccepted, PlatformResult.Queued,
            PlatformResult.SettingsRequired(PlatformFailure.HIDDEN_API_UNAVAILABLE),
            PlatformResult.Denied(PlatformFailure.PERMISSION))
        results.forEach { assertNotEquals(StatusKind.Confirmed, it.presentation().kind) }
        assertEquals(StatusKind.Waiting, PlatformResult.Queued.presentation().kind)
        assertFalse(PlatformResult.RequestAccepted.presentation().offerSettings)
        assertTrue(results[2].presentation().offerSettings)
        assertEquals(StatusKind.Problem, results[2].presentation().kind)
    }
}
