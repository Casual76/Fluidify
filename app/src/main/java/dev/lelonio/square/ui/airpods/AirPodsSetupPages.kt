// SPDX-License-Identifier: GPL-3.0-or-later
//
// The three AirPods surfaces, in LibrePods' shape and on Fluidify's facts.
//
// Structure taken from LibrePods, Copyright (C) 2025 LibrePods contributors, GPL-3.0-or-later,
// pinned at 89356c08cea3db0b6b54792d9329d09a87cc85fa
// (android/app/src/main/java/me/kavishdevar/librepods/presentation/):
//   screens/AirPodsSettingsScreen.kt     the main page: pictures and charge, name, listening mode,
//                                        press and hold, calls, audio, connection, the lone
//                                        switches, about, and what closes the page
//   screens/PressAndHoldSettingsScreen.kt  the page behind Left and Right
//   components/PressAndHoldSettings.kt, AudioSettings.kt, ConnectionSettings.kt  the groups
// The pictures, the charge rings and the mode selector are ported in AirPodsBattery.kt and
// AirPodsNoiseControl.kt, which say line by line what was kept.
//
// What is Fluidify's: the theme, the Engine's rows, switches, sheet and page host, and the binding.
// Nothing here reads a socket or decides an authority; a surface only turns the host's state into
// rows and turns a touch into an AirPodsUiAction. Selection uses the repository's effective
// value; observation, local intent and display defaults remain separate in that model.
package dev.lelonio.square.ui.airpods

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.*
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.lelonio.square.R
import dev.lelonio.square.airpods.*
import dev.lelonio.square.airpods.protocol.*

@Composable internal fun ColumnScope.SetupPage(state: AirPodsUiState, dispatch: (AirPodsUiAction) -> Unit) {
    FluidSectionFootnote(stringResource(R.string.airpods_feature_exclusive_summary),
        modifier = Modifier.testTag("airpods-exclusive-notice"))
    Pane {
        Text(stringResource(R.string.wf_i03_choose_pair), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = PaneInset, top = 12.dp, end = PaneInset, bottom = 8.dp))
        Text(stringResource(R.string.wf_i03_verify_pair_note), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = PaneInset, end = PaneInset, bottom = 8.dp)
                .testTag("airpods-identity-note"))
        // Only with the permission is an empty list a fact about the phone.
        if (state.candidates.isEmpty() && state.bluetoothGranted) Text(
            stringResource(R.string.airpods_feature_no_candidates), style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = PaneInset, end = PaneInset, bottom = 12.dp))
        state.candidates.forEach { candidate ->
            FluidListDivider()
            FluidChoiceRow(candidate.displayName, selected = candidate.id == state.selectedCandidateId,
                onSelect = { dispatch(AirPodsUiAction.SelectPair(candidate.id)) },
                modifier = Modifier.testTag("airpods-candidate-${candidate.id}"))
        }
        SetupRows(state, dispatch,
            SetupAction.BLUETOOTH_PERMISSION to R.string.airpods_feature_bluetooth_access,
            SetupAction.VERIFY_SELECTED to R.string.airpods_ui_verify,
            SetupAction.ENABLE to R.string.airpods_ui_enable,
            SetupAction.DISABLE to R.string.airpods_ui_disable,
            SetupAction.STOP to R.string.airpods_ui_stop,
            SetupAction.RESUME to R.string.airpods_ui_resume)
        if (state.pendingCleanupCount > 0) {
            FluidListDivider()
            Text(stringResource(R.string.airpods_cleanup_pending, state.pendingCleanupCount),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = PaneInset, top = 12.dp, end = PaneInset))
            SetupButton(state, SetupAction.RETRY_CLEANUP, R.string.airpods_cleanup_retry, dispatch)
        }
    }

    GroupLabel(R.string.airpods_feature_service_group)
    Pane {
        SetupRows(state, dispatch,
            SetupAction.NOTIFICATION_PERMISSION to R.string.airpods_ui_notifications,
            SetupAction.MEDIA_ACCESS to R.string.airpods_feature_media_access,
            SetupAction.COMPANION_ASSOCIATION to R.string.airpods_feature_companion_setup,
            leadingDivider = false)
        if (state.audioResult?.presentation()?.offerSettings != true) {
            FluidListDivider()
            SetupButton(state, SetupAction.BLUETOOTH_SETTINGS, R.string.airpods_feature_open_bluetooth, dispatch)
        }
    }
    AudioRequestOutcome(state, dispatch)
    Pane {
        SetupRows(state, dispatch,
            SetupAction.CONNECT to R.string.airpods_audio_connect,
            SetupAction.DISCONNECT to R.string.airpods_audio_disconnect,
            SetupAction.TILE_INSTRUCTIONS to R.string.airpods_feature_setting_extra_qs,
            leadingDivider = false)
    }
    FluidSectionFootnote(stringResource(R.string.airpods_audio_explanation))
    FluidSectionFootnote(stringResource(R.string.airpods_ui_tile))
}

/** An Android request outcome, shown beside the controls that asked for it. */
@Composable internal fun AudioRequestOutcome(state: AirPodsUiState, dispatch: (AirPodsUiAction) -> Unit) {
    val outcome = state.audioResult?.presentation() ?: return
    Pane {
        PaneBlock {
            StatusLine(stringResource(outcome.message), outcome.kind,
                modifier = Modifier.testTag("airpods-audio-outcome"))
        }
        if (outcome.offerSettings) {
            FluidListDivider()
            FluidListRow(stringResource(R.string.airpods_feature_open_bluetooth), "",
                modifier = Modifier.testTag("airpods-audio-settings"),
                onClick = if (state.allowed("setup.BLUETOOTH_SETTINGS")) ({
                    dispatch(AirPodsUiAction.Setup(SetupAction.BLUETOOTH_SETTINGS))
                }) else null)
        }
    }
}

@Composable internal fun ColumnScope.DiagnosticsPage(state: AirPodsUiState, dispatch: (AirPodsUiAction) -> Unit) {
    Pane {
        if (state.diagnostics.isNotEmpty()) {
            PaneBlock(spacing = 2.dp) {
                state.diagnostics.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            FluidListDivider()
        }
        SetupRows(state, dispatch,
            SetupAction.START_DIAGNOSTICS to R.string.airpods_ui_collect,
            SetupAction.STOP_DIAGNOSTICS to R.string.airpods_ui_collect_stop,
            SetupAction.EXPORT_DIAGNOSTICS to R.string.airpods_ui_export,
            SetupAction.DELETE_DIAGNOSTICS to R.string.airpods_ui_delete,
            leadingDivider = false)
    }
    HeadTrackingDiagnostics(state, dispatch)
}
@Composable private fun SetupRows(s: AirPodsUiState, dispatch: (AirPodsUiAction) -> Unit,
    vararg rows: Pair<SetupAction, Int>, leadingDivider: Boolean = true) {
    rows.forEachIndexed { index, row ->
        if (leadingDivider || index > 0) FluidListDivider()
        SetupButton(s, row.first, row.second, dispatch)
    }
}

/** Actions that leave for a system screen keep the chevron; the others act where they stand. */
private val leavingActions = setOf(SetupAction.BLUETOOTH_PERMISSION, SetupAction.NOTIFICATION_PERMISSION,
    SetupAction.CALL_PERMISSION, SetupAction.MEDIA_ACCESS, SetupAction.COMPANION_ASSOCIATION,
    SetupAction.BLUETOOTH_SETTINGS, SetupAction.EXPORT_DIAGNOSTICS)

@Composable internal fun SetupButton(s: AirPodsUiState, action: SetupAction, label: Int, dispatch: (AirPodsUiAction) -> Unit) {
    val allowed = s.allowed("setup.$action")
    val scheme = MaterialTheme.colorScheme
    // An available action is written in the accent, like every plain button of the app; a blocked
    // one steps back and leaves the row to its reason.
    val ink = when {
        !allowed -> scheme.onSurface.copy(alpha = 0.6f)
        action in leavingActions -> scheme.onSurface
        else -> scheme.primary
    }
    FluidListRow(AnnotatedString(stringResource(label), SpanStyle(color = ink)), if (allowed) ""
        else s.availability["setup.$action"]?.reason ?: stringResource(R.string.airpods_ui_unavailable),
        modifier = Modifier.testTag("airpods-setup-$action"),
        disclosure = action in leavingActions,
        onClick = if (allowed) ({ dispatch(AirPodsUiAction.Setup(action)) }) else null)
}
