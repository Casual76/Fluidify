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
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.GearSix
import dev.antigravity.fluidengine.ui.fluid.*
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.lelonio.square.R
import dev.lelonio.square.airpods.*
import dev.lelonio.square.airpods.protocol.*

// Quick pane
// ---------------------------------------------------------------------------------------------

/**
 * The pictures with their charge, the mode selector, one switch, and the way to everything else.
 *
 * The quick-mode preference, the automation preferences and the phone's volume are settings, and
 * are on the settings pages; nothing here summarises them.
 */
@Composable
fun AirPodsQuickOverlay(state: AirPodsUiState, visible: Boolean, onDismiss: () -> Unit,
    dispatch: (AirPodsUiAction) -> Unit, onOpenSettings: () -> Unit, origin: () -> Rect? = { null }) {
    FluidGlassModalPortal(visible = visible, onDismissRequest = onDismiss, origin = origin,
        paneTitle = stringResource(R.string.airpods_ui_quick)) {
        FluidSheetScaffold(title = deviceTitle(state), maxWidth = 560.dp,
            insetBottom = true,
            actions = {
                // Tinted, not filled: the accent of this pane belongs to the mode that is on.
                FluidButton(stringResource(R.string.airpods_ui_full), { onDismiss(); onOpenSettings() },
                    style = FluidButtonStyle.Tinted, fillWidth = true,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("airpods-all-settings"),
                    leading = { Icon(PhosphorIcons.Regular.GearSix, contentDescription = null) })
            }) { AirPodsQuickContent(state, dispatch) }
    }
}

/** What the quick pane holds between its title and its one button. */
@Composable
internal fun AirPodsQuickContent(state: AirPodsUiState, dispatch: (AirPodsUiAction) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AirPodsBatteryView(state)
        AudioRequestOutcome(state, dispatch)
        // Only when there is something to explain: a ready session is already told by the
        // charge and the thumb on the selector.
        if (!state.controlsReady) {
            StatusLine(stringResource(state.connectionSummary()), state.connectionKind(),
                style = MaterialTheme.typography.bodyMedium)
            // Where the capsule can be seen without a connection (the player), asking for one is here.
            if (!state.profileConnected && state.allowed("setup.CONNECT")) FluidListGroup {
                SetupButton(state, SetupAction.CONNECT, R.string.airpods_feature_connect, dispatch)
            }
        }
        MainSettingsFailure(state)
        if (state.modes.isNotEmpty()) ModeControl(state, dispatch, "airpods-quick-MODE")
        val conversation = AirPodsProperties.spec(CommandProperty.CONVERSATION)
        if (conversation != null && state.identity?.let(conversation::candidate) == true) FluidListGroup {
            MainSwitchRow(state, conversation, dispatch)
        }
        if (!state.pair.host.automationsEnabled)
            Text(stringResource(R.string.airpods_reason_automations_off), style = MaterialTheme.typography.bodySmall)

    }
}

/** Existing selector, driven by the shared effective value and the local write queue. */
@Composable internal fun ModeControl(s: AirPodsUiState, dispatch: (AirPodsUiAction) -> Unit, tagPrefix: String,
    modifier: Modifier = Modifier) {
    val effective = s.currentMode
    key(s.domain.generation) {
        AirPodsNoiseControl(
            modes = s.modes,
            effective = effective,
            onRequest = { mode ->
                val value = PropertyValue.Number(mode.wire)
                if (mode != effective && s.writeAllowed(CommandProperty.MODE, value)) ({
                    dispatch(AirPodsUiAction.Write(s.domain.generation, CommandProperty.MODE, value))
                }) else null
            },
            tagPrefix = tagPrefix,
            modifier = modifier,
        )
    }
}

@Composable internal fun PreferredModePicker(s: AirPodsUiState, dispatch: (AirPodsUiAction) -> Unit, inset: Boolean) {
    val options = s.modes.filter { it != ListeningMode.ANC }.map { mode ->
        FluidContextAction(modeLabel(mode.wire), selected = s.preferredMode == mode) { dispatch(AirPodsUiAction.PreferredMode(mode)) }
    }
    val menu = rememberFluidContextMenu(actions = { options })
    MenuRow(
        label = stringResource(R.string.airpods_ui_quick_preference),
        value = s.preferredMode?.let { modeLabel(it.wire) } ?: stringResource(R.string.airpods_ui_missing),
        enabled = s.allowed("local.preferredMode") && options.isNotEmpty(),
        onClick = { menu.open() },
        modifier = Modifier.testTag("airpods-preferred-mode").fluidContextMenuAnchor(menu),
        note = if (inset) stringResource(R.string.airpods_ui_local_note) else null,
        inset = inset,
    )
    AvailabilityReason(s, "local.preferredMode",
        if (inset) Modifier.padding(start = PaneInset, end = PaneInset, bottom = 12.dp) else Modifier)
}

@Composable internal fun modeLabel(wire: Int) = stringResource(when(wire) {1->R.string.airpods_feature_mode_off;2->R.string.airpods_feature_mode_anc;3->R.string.airpods_feature_mode_transparency;else->R.string.airpods_feature_mode_adaptive})
