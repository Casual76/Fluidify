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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Info
import com.adamglin.phosphoricons.regular.Warning
import dev.antigravity.fluidengine.ui.fluid.*
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.lelonio.square.R
import dev.lelonio.square.airpods.*
import dev.lelonio.square.airpods.protocol.*

// ---------------------------------------------------------------------------------------------
// Main page
// ---------------------------------------------------------------------------------------------

/** Whether a control belongs on this device's pages at all. An unknown model hides nothing. */
internal fun AirPodsUiState.applies(spec: PropertySpec): Boolean = identity?.let(spec::candidate) != false

@Composable
internal fun ColumnScope.MainPage(state: AirPodsUiState, dispatch: (AirPodsUiAction) -> Unit,
    onOpen: (AirPodsSettingsPage) -> Unit) {
    val identity = state.identity
    // The device's own settings are shown while a recognised device is answering, as upstream
    // shows them only while connected. Fluidify's side of the feature is always below.
    val device = identity?.takeIf { state.controlsReady && it.recognized }

    DeviceHeader(state)
    AudioRequestOutcome(state, dispatch)
    // The gate is said once, at the top, in the tone of a warning.
    if (!state.productGatePassed) FluidInlineMessage(
        message = stringResource(R.string.airpods_ui_gate),
        title = stringResource(R.string.airpods_reason_gate),
        tone = FluidTone.Warning,
        leading = { Icon(PhosphorIcons.Regular.Warning, contentDescription = null) },
        glass = true,
        modifier = Modifier.testTag("airpods-gate"),
    ) else if (state.controlsReady && device == null) FluidInlineMessage(
        message = stringResource(R.string.airpods_reason_information),
        tone = FluidTone.Info,
        leading = { Icon(PhosphorIcons.Regular.Info, contentDescription = null) },
        glass = true,
        modifier = Modifier.testTag("airpods-information-needed"),
    )

    if (!state.pair.host.automationsEnabled)
        Text(stringResource(R.string.airpods_reason_automations_off), style = MaterialTheme.typography.bodySmall)
    MainSettingsFailure(state)
    if (device != null) DeviceGroups(state, device, dispatch, onOpen)

    // Fluidify's half: what the phone does, and what the pair needs before anything above exists.
    Pane {
        OpenRow(R.string.airpods_ui_connect_setup, AirPodsSettingsPage.Setup, onOpen)
        FluidListDivider()
        OpenRow(R.string.airpods_feature_automations_group, AirPodsSettingsPage.Automations, onOpen)
        FluidListDivider()
        // No "On"/"Off" beside this row: the bare word of an alternative between two editors
        // would be read as one of theirs.
        OpenRow(R.string.airpods_feature_setting_host_head_gestures, AirPodsSettingsPage.HeadGestures, onOpen)
        FluidListDivider()
        OpenRow(R.string.airpods_feature_diagnostics_group, AirPodsSettingsPage.Diagnostics, onOpen)
    }
    if (identity != null) {
        GroupLabel(R.string.airpods_feature_device_group)
        Pane {
            // Explicit information page may display identity; never include it in diagnostics.
            IdentityLine(identity)
            FluidListDivider()
            SetupButton(state, SetupAction.INFORMATION, R.string.airpods_ui_information, dispatch)
        }
    }

    var forget by remember { mutableStateOf(false) }
    FluidButton(stringResource(R.string.airpods_feature_forget), { forget = true }, enabled = state.allowed("setup.FORGET"),
        style = FluidButtonStyle.Destructive, fillWidth = true, modifier = Modifier.padding(top = 8.dp))
    if (forget) FluidAlert(onDismissRequest = { forget = false }, title = stringResource(R.string.airpods_feature_forget),
        message = stringResource(R.string.airpods_ui_forget_confirm), actions = listOf(
            FluidAlertAction(stringResource(R.string.airpods_ui_cancel), { forget = false }),
            FluidAlertAction(stringResource(R.string.airpods_feature_forget), { forget = false; dispatch(AirPodsUiAction.Setup(SetupAction.FORGET)) })))
}

/** Upstream's groups, top to bottom, for a device that is answering. */
@Composable
private fun ColumnScope.DeviceGroups(state: AirPodsUiState, identity: AirPodsIdentity,
    dispatch: (AirPodsUiAction) -> Unit, onOpen: (AirPodsSettingsPage) -> Unit) {
    val main = remember { airPodsPageSpecs(AirPodsSettingsPage.Main) }
    val shown = main.filter { it.candidate(identity) }
    fun group(properties: List<CommandProperty>) = shown.filter { it.property in properties }

    Pane {
        OpenRow(R.string.airpods_p1_name, AirPodsSettingsPage.Rename, onOpen,
            value = state.headerName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.airpods_ui_missing))
    }

    if (shown.any { it.property == CommandProperty.MODE }) {
        GroupLabel(R.string.airpods_feature_setting_aacp_listening_mode)
        ModeControl(state, dispatch, "airpods-mode")
    }

    if (AirPodsProperties.spec(CommandProperty.STEM_CONFIG)?.candidate(identity) == true) {
        GroupLabel(R.string.airpods_feature_accessibility_group)
        Pane {
            LongPressRow(state, GestureSide.LEFT, AirPodsSettingsPage.LongPressLeft, onOpen)
            FluidListDivider()
            LongPressRow(state, GestureSide.RIGHT, AirPodsSettingsPage.LongPressRight, onOpen)
        }
    }

    val calls = remember { airPodsPageSpecs(AirPodsSettingsPage.Calls) }.filter { it.candidate(identity) }
    if (calls.isNotEmpty()) Pane {
        OpenRow(R.string.airpods_feature_calls_group, AirPodsSettingsPage.Calls, onOpen)
    }

    val audio = group(airPodsMainGroups[0])
    val strength = shown.filter { it.property == CommandProperty.AUTO_ANC_STRENGTH }
    GroupLabel(R.string.airpods_feature_audio_group)
    Pane {
        audio.forEach { spec ->
            MainSwitchRow(state, spec, dispatch)
            FluidListDivider()
        }
        strength.forEach { spec ->
            AdaptiveStrengthSlider(state, spec, dispatch)
            FluidListDivider()
        }
        OpenRow(R.string.airpods_feature_setting_device_eq, AirPodsSettingsPage.Equalizer, onOpen)
    }

    // The lone switches, in upstream's order, each group in its own pane.
    airPodsMainGroups.drop(1).forEach { properties ->
        val specs = group(properties)
        SwitchPane(specs, state, dispatch)
    }

    if (remember { airPodsPageSpecs(AirPodsSettingsPage.Controls) }.any { it.candidate(identity) }) Pane {
        OpenRow(R.string.airpods_feature_controls_group, AirPodsSettingsPage.Controls, onOpen)
    }

    // A main-page control this page does not place by name yet is still here, after the others.
    val placed = airPodsMainGroups.flatten().toSet() + setOf(CommandProperty.MODE, CommandProperty.AUTO_ANC_STRENGTH)
    SwitchPane(shown.filterNot { it.property in placed }, state, dispatch)
}

@Composable private fun SwitchPane(specs: List<PropertySpec>, state: AirPodsUiState, dispatch: (AirPodsUiAction) -> Unit) {
    if (specs.isEmpty()) return
    Pane {
        specs.forEachIndexed { index, spec ->
            if (index > 0) FluidListDivider()
            MainSwitchRow(state, spec, dispatch)
        }
    }
}

/** The phone's side of the automations, and the two phone-side preferences the quick pane used to carry. */
@Composable internal fun ColumnScope.AutomationsPage(state: AirPodsUiState, dispatch: (AirPodsUiAction) -> Unit) {
    Pane {
        LocalToggleRows(state, dispatch, automationToggles)
        FluidListDivider()
        key(state.domain.generation) {
            AirPodsLiveSlider(state.pair.host.conversationVolumePercent,
                { dispatch(AirPodsUiAction.ConversationVolume(it)) }, range = 10..85,
                title = stringResource(R.string.airpods_feature_setting_host_ca_volume),
                enabled = state.allowed("host.ca_volume") && state.pair.host.automationsEnabled)
        }
    }
    Pane { PaneBlock { HostAutomationSummary(state) } }

    Pane {
        PreferredModePicker(state, dispatch, inset = true)
        FluidListDivider()
        key(state.domain.generation) {
            state.localVolumePercent?.let { volume ->
                AirPodsLiveSlider(volume, { dispatch(AirPodsUiAction.LocalVolume(it)) },
                    title = stringResource(R.string.airpods_feature_setting_host_volume),
                    enabled = state.allowed("host.volume"))
            }
        }
    }
}

/** Saved choices and host prerequisites only; C6 cannot certify another app's route/effect. */
@Composable internal fun HostAutomationSummary(state: AirPodsUiState) {
    Column(Modifier.fillMaxWidth().testTag("airpods-host-summary"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.airpods_ui_host_summary), style = MaterialTheme.typography.titleSmall)
        for (toggle in listOf(LocalToggle.AUTOMATIONS, LocalToggle.BACKGROUND, LocalToggle.OTHER_APPS)) {
            val saved = state.pair.localToggleValue(toggle)
            val status = state.availability["status.$toggle"]
            val description = when {
                !saved -> stringResource(R.string.airpods_ui_saved_off)
                status?.enabled == true -> stringResource(R.string.airpods_ui_saved_ready)
                else -> stringResource(R.string.airpods_ui_saved_blocked,
                    status?.reason ?: stringResource(R.string.airpods_ui_unavailable))
            }
            val label = when (toggle) {
                LocalToggle.AUTOMATIONS -> R.string.airpods_ui_summary_media
                LocalToggle.BACKGROUND -> R.string.airpods_ui_summary_background
                else -> R.string.airpods_ui_summary_other_apps
            }
            // The mark says what is saved and whether its prerequisites hold, like the words beside
            // it. It is not a reading of an effect, and a blocked preference is not an error of the device.
            val kind = when {
                !saved -> StatusKind.Neutral
                status?.enabled == true -> StatusKind.Confirmed
                else -> StatusKind.Problem
            }
            StatusLine(stringResource(label) + ": " + description, kind,
                Modifier.testTag("airpods-host-summary-$toggle"))
        }
    }
}

/** No per-command receipt UI: only a real local/save/write failure gets a message. */
@Composable internal fun MainSettingsFailure(state: AirPodsUiState) {
    val failedWrite = state.domain.desired.entries.firstOrNull { (property, desired) ->
        airPodsSettingsPageOf(property) == AirPodsSettingsPage.Main &&
            desired.generation == state.domain.generation && desired.status == DesiredStatus.FAILED
    }
    val issue = state.mainToggleIssue
    val message = when (issue?.failure) {
        AirPodsMainToggleFailure.UNAVAILABLE -> R.string.airpods_ui_unavailable
        AirPodsMainToggleFailure.LOCAL_SAVE_FAILED -> R.string.airpods_c4_save_failed
        AirPodsMainToggleFailure.DEVICE_NOT_SENT -> R.string.airpods_c4_partial_save
        null -> if (failedWrite != null) R.string.airpods_c4_send_failed else return
    }
    val property = issue?.property ?: failedWrite?.key
    val title = property?.let { AirPodsProperties.spec(it)?.catalogId }?.let(featureTitles::get)
    FluidInlineMessage(
        message = stringResource(message), title = title?.let { stringResource(it) },
        tone = FluidTone.Warning, glass = true, modifier = Modifier.testTag("airpods-main-failure"),
    )
}

@Composable internal fun MainSwitchRow(state: AirPodsUiState, spec: PropertySpec, dispatch: (AirPodsUiAction) -> Unit) {
    val checked = effectiveNumber(state, spec.property) == 1
    FluidToggleRow(
        title = stringResource(featureTitles.getValue(spec.catalogId)), checked = checked,
        enabled = state.mainToggleAllowed(spec.property, !checked),
        modifier = Modifier.testTag("airpods-switch-${spec.property}"),
        onCheckedChange = { enabled ->
            if (state.mainToggleAllowed(spec.property, enabled)) dispatch(AirPodsUiAction.Write(
                state.domain.generation, spec.property, PropertyValue.Number(if (enabled) 1 else 2)))
        },
    )
}

@Composable private fun AdaptiveStrengthSlider(state: AirPodsUiState, spec: PropertySpec,
    dispatch: (AirPodsUiAction) -> Unit) {
    val value = effectiveNumber(state, spec.property) ?: return
    key(state.domain.generation) {
        AirPodsLiveSlider(
            value = value,
            title = stringResource(featureTitles.getValue(spec.catalogId)),
            modifier = Modifier.testTag("airpods-property-${spec.property}"),
            range = spec.range, debounceMs = 150L,
            enabled = state.controlsReady && state.allowed("property.${spec.property}"),
            // PropertySpec/codec own 100-x on TX and RX; the UI is always the natural 0..100.
            onSend = { value ->
                val request = PropertyValue.Number(value)
                if (state.writeAllowed(spec.property, request)) dispatch(AirPodsUiAction.Write(
                    state.domain.generation, spec.property, request))
            },
        )
    }
}
