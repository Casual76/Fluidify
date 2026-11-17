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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.*
import dev.antigravity.fluidengine.ui.page.FluidPageChrome
import dev.lelonio.square.R
import dev.lelonio.square.airpods.*
import dev.lelonio.square.airpods.protocol.*
import dev.lelonio.square.ui.navigation.FluidifyPageHost

// ---------------------------------------------------------------------------------------------
// Settings: the pages
// ---------------------------------------------------------------------------------------------

@Composable
private fun pageTitle(page: AirPodsSettingsPage, s: AirPodsUiState): String = when (page) {
    AirPodsSettingsPage.Main, AirPodsSettingsPage.AdaptiveStrength -> s.effectiveName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.airpods_host_title)
    AirPodsSettingsPage.Rename -> stringResource(R.string.airpods_feature_setting_device_rename)
    AirPodsSettingsPage.LongPressLeft -> stringResource(R.string.airpods_ui_left)
    AirPodsSettingsPage.LongPressRight -> stringResource(R.string.airpods_ui_right)
    AirPodsSettingsPage.Calls -> stringResource(R.string.airpods_feature_calls_group)
    AirPodsSettingsPage.Equalizer -> stringResource(R.string.airpods_feature_setting_device_eq)
    AirPodsSettingsPage.Controls -> stringResource(R.string.airpods_feature_controls_group)
    AirPodsSettingsPage.HeadGestures -> stringResource(R.string.airpods_feature_setting_host_head_gestures)
    AirPodsSettingsPage.Automations -> stringResource(R.string.airpods_feature_automations_group)
    AirPodsSettingsPage.Setup -> stringResource(R.string.airpods_ui_connect_setup)
    AirPodsSettingsPage.Diagnostics -> stringResource(R.string.airpods_feature_diagnostics_group)
}

/**
 * The settings as pages: the main one, and one pushed over it at a time.
 *
 * The same arrangement as the app's own settings: the open page is a remembered value, the stack
 * is derived from it, and the Engine's page host moves the pages and owns the back gesture, so a
 * back closes the inner page before it leaves the feature. Each page describes its bar.
 *
 * @param notice words for the top of the main page, from whoever hosts it (no runtime installed).
 * @param start the page to show first; a host that opens the settings on a particular page.
 */
@Composable
internal fun AirPodsSettingsPages(
    state: AirPodsUiState,
    dispatch: (AirPodsUiAction) -> Unit,
    padding: PaddingValues,
    backdrop: GlassBackdropState,
    onBack: () -> Unit,
    notice: String? = null,
    start: AirPodsSettingsPage = AirPodsSettingsPage.Main,
) {
    // Keep the legacy adaptive route as an alias for callers with saved navigation state.
    var open by rememberSaveable { mutableStateOf(start.takeIf {
        it != AirPodsSettingsPage.Main && it != AirPodsSettingsPage.AdaptiveStrength
    }) }
    val stack = remember(open) { listOf(AirPodsSettingsPage.Main) +
        listOfNotNull(open.takeUnless { it == AirPodsSettingsPage.AdaptiveStrength }) }
    FluidifyPageHost(stack = stack, onBack = { open = null }, modifier = Modifier.fillMaxSize()) { page ->
        FluidPageChrome(pageTitle(page, state),
            onBack = if (page == AirPodsSettingsPage.Main) onBack else ({ open = null }), backdrop = backdrop)
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.fillMaxSize().padding(padding).padding(top = 64.dp)) {
                if (page == AirPodsSettingsPage.Main && notice != null) Text(notice, Modifier.padding(16.dp))
                AirPodsSettingsContent(state, dispatch, Modifier.weight(1f), page = page,
                    onOpen = { open = it.takeUnless { target -> target == AirPodsSettingsPage.AdaptiveStrength } })
            }
        }
    }
}

/**
 * The BODY of one settings page: navigation, title and real modal backdrop are owned by the host.
 *
 * Every page is one scrolling column, fully composed: nothing on a page is created by scrolling to it.
 *
 * @param page which page; the main one unless a host or a test asks for another.
 * @param onOpen a row of the main page asks for the page behind it.
 */
@Composable
fun AirPodsSettingsContent(state: AirPodsUiState, dispatch: (AirPodsUiAction) -> Unit, modifier: Modifier = Modifier,
    page: AirPodsSettingsPage = AirPodsSettingsPage.Main, onOpen: (AirPodsSettingsPage) -> Unit = {}) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).fluidReadingWidth().padding(16.dp)
            .testTag("airpods-page-$page"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (page) {
            AirPodsSettingsPage.Main, AirPodsSettingsPage.AdaptiveStrength -> MainPage(state, dispatch, onOpen)
            AirPodsSettingsPage.Rename -> RenamePage(state, dispatch)
            AirPodsSettingsPage.LongPressLeft -> LongPressPage(state, GestureSide.LEFT, dispatch)
            AirPodsSettingsPage.LongPressRight -> LongPressPage(state, GestureSide.RIGHT, dispatch)
            AirPodsSettingsPage.Calls -> CallsPage(state, dispatch)
            AirPodsSettingsPage.Equalizer -> EqualizerPage(state, dispatch)
            AirPodsSettingsPage.Controls -> ControlsPage(state, dispatch)
            AirPodsSettingsPage.HeadGestures -> HeadGesturesPage(state, dispatch)
            AirPodsSettingsPage.Automations -> AutomationsPage(state, dispatch)
            AirPodsSettingsPage.Setup -> SetupPage(state, dispatch)
            AirPodsSettingsPage.Diagnostics -> DiagnosticsPage(state, dispatch)
        }
    }
}
