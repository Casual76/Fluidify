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
// rows and turns a touch into an AirPodsUiAction. What a surface may say is unchanged: a thumb, a
// check or a switch position is a value the device reported in this session, a ring or a spinner
// is a request still waiting for its answer, and "--" is an unknown that never becomes a number.
package dev.lelonio.square.ui.airpods

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CaretUpDown
import com.adamglin.phosphoricons.regular.GearSix
import com.adamglin.phosphoricons.regular.Info
import com.adamglin.phosphoricons.regular.Warning
import dev.antigravity.fluidengine.ui.fluid.*
import dev.antigravity.fluidengine.ui.page.FluidPageChrome
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidStatusBadge
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.lelonio.square.R
import dev.lelonio.square.airpods.*
import dev.lelonio.square.airpods.protocol.*
import dev.lelonio.square.ui.navigation.FluidifyPageHost
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Inner margin of a pane that is not a list row; the same sixteen a row keeps for its text. */
private val PaneInset = 16.dp

/** The capsule is one finger tall at least; it grows with the text under the pictures. */
private val CapsuleMinHeight = 48.dp

/** How tall the capsule draws a picture: the height of a line of the bar it sits in. */
private val CapsulePictureHeight = 22.dp

/**
 * The capsule's two labels stop growing at this font scale.
 *
 * Home gives the capsule as little as 96 dp between the brand and the avatar, and that room does
 * not grow with the font: past this scale "L 80%" and the case's charge no longer fit side by
 * side. What is spoken is not capped, and the quick pane the capsule opens scales in full.
 */
private const val CapsuleMaxFontScale = 1.3f

// ---------------------------------------------------------------------------------------------
// Capsule
// ---------------------------------------------------------------------------------------------

/**
 * The earbuds and the case with their charge under them, and nothing else.
 *
 * One target: a touch anywhere opens the quick pane. There is no mode mark, no name and no second
 * action; whatever the capsule used to do on its own is in the pane it opens.
 */
@Composable
fun AirPodsCapsule(binding: AirPodsUiBinding, modifier: Modifier = Modifier,
    backdrop: GlassBackdropState = currentGlassBackdrop()) {
    val s = binding.state
    var origin by remember { mutableStateOf<Rect?>(null) }
    val choice = s.artworkChoice()
    val live = choice.known && s.homeVisible
    val missing = stringResource(R.string.airpods_ui_missing)
    // Earbuds only under the earbuds: the case never stands in for them here, it has its own cell.
    val earbuds = s.capsuleBatteryReading()?.takeIf { it.component != BatteryCase }
    val earbudsText = earbuds?.let { reading ->
        val percent = stringResource(R.string.airpods_feature_percent, reading.percent)
        when (reading.component) {
            BatteryLeft -> stringResource(R.string.airpods_ui_left_short) + " " + percent
            BatteryRight -> stringResource(R.string.airpods_ui_right_short) + " " + percent
            else -> percent
        }
    } ?: missing
    val caseText = s.freshBattery(BatteryCase)?.percent
        ?.let { stringResource(R.string.airpods_feature_percent, it) } ?: missing
    // One earbud reporting alone is drawn alone, with the picture of its own side.
    val earbudsPicture = when (earbuds?.component) {
        BatteryLeft -> choice.artwork.left
        BatteryRight -> choice.artwork.right
        else -> choice.artwork.buds
    }
    val title = stringResource(R.string.airpods_feature_settings_accessibility)
    val spokenBattery = stringResource(R.string.airpods_ui_capsule_battery,
        if (s.testAuthorized) "TEST/NV" else capsuleBatteryLabel(s))

    FluidFloatingCapsule(
        modifier = modifier.testTag("airpods-home-capsule").onGloballyPositioned { origin = it.boundsInWindow() },
        backdrop = backdrop, contentPadding = PaddingValues(0.dp), minHeight = CapsuleMinHeight,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = CapsuleMinHeight)
                .testTag("airpods-capsule-body")
                .fluidPressable(onClick = {
                    val rect = origin
                    val open = binding.openQuickFrom
                    if (rect != null && open != null) open(rect) else binding.openQuick()
                }, role = Role.Button)
                .semantics { contentDescription = "${if (s.testAuthorized) "TEST / NV. " else ""}$title. $spokenBattery" }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CapsuleCell(earbudsPicture, earbudsText, live, earbuds != null, "airpods-capsule-earbuds-text")
            CapsuleCell(choice.artwork.case, caseText, live, caseText != missing, "airpods-capsule-case-text")
        }
    }
}

@Composable
private fun CapsuleCell(picture: Int, text: String, live: Boolean, known: Boolean, tag: String) {
    val scale = LocalDensity.current.fontScale
    val base = MaterialTheme.typography.labelSmall
    val style = if (scale > CapsuleMaxFontScale && base.fontSize.isSp) base.copy(
        fontSize = base.fontSize * (CapsuleMaxFontScale / scale),
        lineHeight = if (base.lineHeight.isSp) base.lineHeight * (CapsuleMaxFontScale / scale) else base.lineHeight,
    ) else base
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AirPodsPicture(picture, Modifier.height(CapsulePictureHeight), live)
        Text(text, style = style, maxLines = 1,
            textAlign = TextAlign.Center,
            color = LocalContentColor.current.copy(alpha = if (known) 1f else 0.5f),
            modifier = Modifier.width(IntrinsicSize.Max).testTag(tag))
    }
}

@Composable private fun capsuleBatteryLabel(s: AirPodsUiState): String {
    val reading = s.capsuleBatteryReading() ?: return stringResource(R.string.airpods_ui_missing)
    val percent = stringResource(R.string.airpods_feature_percent, reading.percent)
    return when (reading.component) {
        BatteryLeft -> stringResource(R.string.airpods_ui_left_short) + " " + percent
        BatteryRight -> stringResource(R.string.airpods_ui_right_short) + " " + percent
        BatteryCase -> stringResource(R.string.airpods_ui_case_short) + " " + percent
        else -> percent
    }
}

// ---------------------------------------------------------------------------------------------
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
        if (state.modes.isNotEmpty()) ModeControl(state, dispatch, "airpods-quick-MODE")
        val conversation = AirPodsProperties.spec(CommandProperty.CONVERSATION)
        if (conversation != null && state.identity?.let(conversation::candidate) == true) FluidListGroup {
            PropertySwitchRow(state, conversation, dispatch)
        }
    }
}

/** What the device calls itself, else what its model is called, else the feature's own name. */
@Composable
private fun deviceTitle(s: AirPodsUiState): String =
    s.identity?.name?.takeIf { it.isNotBlank() }
        ?: s.artworkChoice().modelName
        ?: stringResource(R.string.airpods_feature_title)

// ---------------------------------------------------------------------------------------------
// Settings: the pages
// ---------------------------------------------------------------------------------------------

@Composable
private fun pageTitle(page: AirPodsSettingsPage, s: AirPodsUiState): String = when (page) {
    AirPodsSettingsPage.Main -> s.identity?.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.airpods_host_title)
    AirPodsSettingsPage.Rename -> stringResource(R.string.airpods_feature_setting_device_rename)
    AirPodsSettingsPage.LongPressLeft -> stringResource(R.string.airpods_ui_left)
    AirPodsSettingsPage.LongPressRight -> stringResource(R.string.airpods_ui_right)
    AirPodsSettingsPage.Calls -> stringResource(R.string.airpods_feature_calls_group)
    AirPodsSettingsPage.AdaptiveStrength -> stringResource(R.string.airpods_feature_setting_aacp_auto_anc_strength)
    AirPodsSettingsPage.Equalizer -> stringResource(R.string.airpods_feature_setting_device_eq)
    AirPodsSettingsPage.Controls -> stringResource(R.string.airpods_feature_controls_group)
    AirPodsSettingsPage.HeadGestures -> stringResource(R.string.airpods_feature_setting_host_head_gestures)
    AirPodsSettingsPage.Automations -> stringResource(R.string.airpods_feature_automations_group)
    AirPodsSettingsPage.Setup -> stringResource(R.string.airpods_ui_setup)
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
    var open by rememberSaveable { mutableStateOf(start.takeIf { it != AirPodsSettingsPage.Main }) }
    val stack = remember(open) { listOf(AirPodsSettingsPage.Main) + listOfNotNull(open) }
    FluidifyPageHost(stack = stack, onBack = { open = null }, modifier = Modifier.fillMaxSize()) { page ->
        FluidPageChrome(pageTitle(page, state),
            onBack = if (page == AirPodsSettingsPage.Main) onBack else ({ open = null }), backdrop = backdrop)
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.fillMaxSize().padding(padding).padding(top = 64.dp)) {
                if (page == AirPodsSettingsPage.Main && notice != null) Text(notice, Modifier.padding(16.dp))
                AirPodsSettingsContent(state, dispatch, Modifier.weight(1f), page = page, onOpen = { open = it })
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
            AirPodsSettingsPage.Main -> MainPage(state, dispatch, onOpen)
            AirPodsSettingsPage.Rename -> RenamePage(state, dispatch)
            AirPodsSettingsPage.LongPressLeft -> LongPressPage(state, GestureSide.LEFT, dispatch)
            AirPodsSettingsPage.LongPressRight -> LongPressPage(state, GestureSide.RIGHT, dispatch)
            AirPodsSettingsPage.Calls -> CallsPage(state, dispatch)
            AirPodsSettingsPage.AdaptiveStrength -> RegistryPage(page, state, dispatch)
            AirPodsSettingsPage.Equalizer -> Pane { EqEditor(state, dispatch) }
            AirPodsSettingsPage.Controls -> RegistryPage(page, state, dispatch)
            AirPodsSettingsPage.HeadGestures -> HeadGesturesPage(state, dispatch)
            AirPodsSettingsPage.Automations -> AutomationsPage(state, dispatch)
            AirPodsSettingsPage.Setup -> SetupPage(state, dispatch)
            AirPodsSettingsPage.Diagnostics -> DiagnosticsPage(state, dispatch)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Main page
// ---------------------------------------------------------------------------------------------

/** Whether a control belongs on this device's pages at all. An unknown model hides nothing. */
private fun AirPodsUiState.applies(spec: PropertySpec): Boolean = identity?.let(spec::candidate) != false

@Composable
private fun ColumnScope.MainPage(state: AirPodsUiState, dispatch: (AirPodsUiAction) -> Unit,
    onOpen: (AirPodsSettingsPage) -> Unit) {
    val identity = state.identity
    // The device's own settings are shown while a recognised device is answering, as upstream
    // shows them only while connected. Fluidify's side of the feature is always below.
    val device = identity?.takeIf { state.controlsReady && it.recognized }

    DeviceHeader(state)
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

    if (device != null) DeviceGroups(state, device, dispatch, onOpen)

    // Fluidify's half: what the phone does, and what the pair needs before anything above exists.
    Pane {
        OpenRow(R.string.airpods_ui_setup, AirPodsSettingsPage.Setup, onOpen)
        FluidListDivider()
        OpenRow(R.string.airpods_feature_automations_group, AirPodsSettingsPage.Automations, onOpen)
        FluidListDivider()
        // No "On"/"Off" beside this row: the bare word of an alternative between two editors
        // would be read as one of theirs.
        OpenRow(R.string.airpods_feature_setting_host_head_gestures, AirPodsSettingsPage.HeadGestures, onOpen)
        FluidListDivider()
        OpenRow(R.string.airpods_feature_diagnostics_group, AirPodsSettingsPage.Diagnostics, onOpen)
    }
    // Plain words on the page floor, in the page's own ink.
    Text(stringResource(R.string.airpods_feature_exclusive_summary),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = 4.dp))

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
    FluidSectionFootnote(stringResource(R.string.airpods_feature_forget_summary))
    if (forget) FluidAlert(onDismissRequest = { forget = false }, title = stringResource(R.string.airpods_feature_forget),
        message = stringResource(R.string.airpods_ui_forget_confirm), actions = listOf(
            FluidAlertAction(stringResource(R.string.airpods_ui_cancel), { forget = false }),
            FluidAlertAction(stringResource(R.string.airpods_feature_forget), { forget = false; dispatch(AirPodsUiAction.Setup(SetupAction.FORGET)) })))
}

/**
 * The pictures and the charge, and under them only what the pictures cannot say: that this is a
 * test session, why there is no live reading, and the last values that are no longer live.
 */
@Composable private fun DeviceHeader(s: AirPodsUiState) {
    Column(Modifier.fillMaxWidth().testTag("airpods-summary"), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AirPodsBatteryView(s)
        if (s.testAuthorized) FluidStatusBadge("TEST / NV", tone = FluidTone.Warning)
        StatusLine(stringResource(s.connectionSummary()), s.connectionKind(),
            Modifier.testTag("airpods-summary-state"), style = MaterialTheme.typography.bodyMedium)
        BatteryHistory(s)
    }
}

/** Upstream's groups, top to bottom, for a device that is answering. */
@Composable
private fun ColumnScope.DeviceGroups(state: AirPodsUiState, identity: AirPodsIdentity,
    dispatch: (AirPodsUiAction) -> Unit, onOpen: (AirPodsSettingsPage) -> Unit) {
    val main = remember { airPodsPageSpecs(AirPodsSettingsPage.Main) }
    val shown = main.filter { it.candidate(identity) }
    fun group(properties: List<CommandProperty>) = shown.filter { it.property in properties }

    Pane {
        OpenRow(R.string.airpods_feature_setting_device_rename, AirPodsSettingsPage.Rename, onOpen,
            value = identity.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.airpods_ui_missing))
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
    val strength = remember { airPodsPageSpecs(AirPodsSettingsPage.AdaptiveStrength) }.filter { it.candidate(identity) }
    GroupLabel(R.string.airpods_feature_audio_group)
    Pane {
        audio.forEach { spec ->
            PropertySwitchRow(state, spec, dispatch)
            FluidListDivider()
        }
        strength.forEach { spec ->
            OpenRow(featureTitles.getValue(spec.catalogId), AirPodsSettingsPage.AdaptiveStrength, onOpen,
                value = receivedNumber(state, spec.property)?.toString() ?: stringResource(R.string.airpods_ui_missing))
            FluidListDivider()
        }
        OpenRow(R.string.airpods_feature_setting_device_eq, AirPodsSettingsPage.Equalizer, onOpen)
    }

    // The lone switches, in upstream's order, each group in its own pane.
    airPodsMainGroups.drop(1).forEach { properties ->
        val specs = group(properties)
        SwitchPane(specs, state, dispatch)
        // Upstream has two hearing pages above; why they are not here is said where their switch is.
        if (specs.any { it.property == CommandProperty.DYNAMIC_END_OF_CHARGE } &&
            (Capability.HEARING_AID in identity.modelDeclared || Capability.PPE in identity.modelDeclared))
            FluidSectionFootnote(stringResource(R.string.airpods_ui_hearing_excluded))
    }

    if (remember { airPodsPageSpecs(AirPodsSettingsPage.Controls) }.any { it.candidate(identity) }) Pane {
        OpenRow(R.string.airpods_feature_controls_group, AirPodsSettingsPage.Controls, onOpen)
    }

    // A main-page control this page does not place by name yet is still here, after the others.
    val placed = airPodsMainGroups.flatten().toSet() + CommandProperty.MODE
    SwitchPane(shown.filterNot { it.property in placed }, state, dispatch)
}

@Composable private fun SwitchPane(specs: List<PropertySpec>, state: AirPodsUiState, dispatch: (AirPodsUiAction) -> Unit) {
    if (specs.isEmpty()) return
    Pane {
        specs.forEachIndexed { index, spec ->
            if (index > 0) FluidListDivider()
            PropertyControl(state, spec, dispatch)
        }
    }
}

private fun receivedNumber(s: AirPodsUiState, property: CommandProperty): Int? =
    (s.domain.propertyObserved(property)?.value as? PropertyValue.Number)?.value

/** One long-press side on the main page: what is saved for it, and the page where it is chosen. */
@Composable private fun LongPressRow(s: AirPodsUiState, side: GestureSide, page: AirPodsSettingsPage,
    onOpen: (AirPodsSettingsPage) -> Unit) {
    val saved = s.gestureAssignments[side]?.takeIf { it in gestureOptions(side) }
    OpenRow(if (side == GestureSide.LEFT) R.string.airpods_ui_left else R.string.airpods_ui_right, page, onOpen,
        value = saved?.let { stringResource(gestureLabels.getValue(it)) } ?: stringResource(R.string.airpods_ui_choose_action),
        tag = "airpods-gesture-$side")
}

// ---------------------------------------------------------------------------------------------
// The pages behind the rows
// ---------------------------------------------------------------------------------------------

@Composable private fun ColumnScope.RenamePage(state: AirPodsUiState, dispatch: (AirPodsUiAction) -> Unit) {
    Pane {
        state.identity?.let { id ->
            IdentityLine(id)
            FluidListDivider()
        }
        RenameEditor(state, dispatch)
    }
}

/**
 * Upstream's `LongPress` page: what holding this stem does, and which modes a hold cycles through.
 *
 * The two choices are saved on the phone; the mask of modes and the interception are the
 * device's, shared by both stems, and say so with their own status.
 */
@Composable private fun ColumnScope.LongPressPage(s: AirPodsUiState, side: GestureSide, dispatch: (AirPodsUiAction) -> Unit) {
    val options = gestureOptions(side)
    val saved = s.gestureAssignments[side]?.takeIf { it in options }
    val editable = s.allowed("gesture.$side") && s.stemPresentation().editable
    Pane {
        options.forEachIndexed { index, action ->
            if (index > 0) FluidListDivider()
            FluidChoiceRow(stringResource(gestureLabels.getValue(action)), selected = saved == action,
                onSelect = { dispatch(AirPodsUiAction.AssignGesture(side, action)) },
                modifier = Modifier.testTag("airpods-gesture-$side-$action").semantics { if (!editable) disabled() },
                enabled = editable)
        }
    }
    // A long press is a choice kept on the phone; what the earbuds do with it is the status below.
    FluidSectionFootnote(stringResource(R.string.airpods_stem_local_choice))

    remember { airPodsPageSpecs(AirPodsSettingsPage.LongPressLeft) }.filter(s::applies).forEach { spec ->
        Pane { PropertyEditor(s, spec, dispatch) }
    }
    Pane { PaneBlock(spacing = 4.dp) { StemStatusSummary(s) } }
}

@Composable private fun ColumnScope.CallsPage(state: AirPodsUiState, dispatch: (AirPodsUiAction) -> Unit) {
    RegistryPage(AirPodsSettingsPage.Calls, state, dispatch)
    Pane { SetupButton(state, SetupAction.CALL_PERMISSION, R.string.airpods_call_permission, dispatch) }
    FluidSectionFootnote(stringResource(R.string.airpods_call_explanation))
}

/** Every registry control filed under [page] that applies to this device, one pane each. */
@Composable private fun ColumnScope.RegistryPage(page: AirPodsSettingsPage, state: AirPodsUiState,
    dispatch: (AirPodsUiAction) -> Unit) {
    remember(page) { airPodsPageSpecs(page) }.filter(state::applies).forEach { spec ->
        Pane { PropertyControl(state, spec, dispatch) }
    }
}

@Composable private fun ColumnScope.HeadGesturesPage(state: AirPodsUiState, dispatch: (AirPodsUiAction) -> Unit) {
    Pane {
        LocalToggleRows(state, dispatch, gestureToggles)
        FluidListDivider()
        GesturePicker(state, GestureSide.NOD, dispatch)
        FluidListDivider()
        GesturePicker(state, GestureSide.SHAKE, dispatch)
    }
    Pane {
        PaneBlock {
            Text(stringResource(R.string.airpods_ui_raw_plot), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TrackingPlot(state.headHistory)
            AdaptiveCells(Modifier.fillMaxWidth(), columns = 2, gap = 8.dp) {
                FluidButton(stringResource(R.string.airpods_ui_tracking_start), { dispatch(AirPodsUiAction.Tracking(true)) },
                    enabled = state.allowed("tracking.start"), style = FluidButtonStyle.Tinted,
                    modifier = Modifier.testTag("airpods-tracking-start"), grip = FluidPressGrip.YieldsToScroll)
                FluidButton(stringResource(R.string.airpods_ui_tracking_stop), { dispatch(AirPodsUiAction.Tracking(false)) },
                    enabled = state.allowed("tracking.stop"), style = FluidButtonStyle.Tinted,
                    modifier = Modifier.testTag("airpods-tracking-stop"), grip = FluidPressGrip.YieldsToScroll)
            }
            AvailabilityReason(state, "tracking.start")
            AvailabilityReason(state, "tracking.stop")
        }
    }
    FluidSectionFootnote(stringResource(R.string.airpods_ui_local_note))
}

/** The phone's side of the automations, and the two phone-side preferences the quick pane used to carry. */
@Composable private fun ColumnScope.AutomationsPage(state: AirPodsUiState, dispatch: (AirPodsUiAction) -> Unit) {
    Pane {
        LocalToggleRows(state, dispatch, automationToggles)
        FluidListDivider()
        PaneBlock {
            NumberEditor(stringResource(R.string.airpods_feature_setting_host_ca_volume), state.pair.host.conversationVolumePercent, 10..85,
                state.allowed("host.ca_volume") && state.pair.host.automationsEnabled,
                reason = if (!state.pair.host.automationsEnabled) stringResource(R.string.airpods_reason_automations_off)
                    else state.availability["host.ca_volume"]?.reason) { dispatch(AirPodsUiAction.ConversationVolume(it)) }
        }
    }
    // Said once for the group instead of under every switch.
    FluidSectionFootnote(stringResource(R.string.airpods_ui_local_note))
    FluidSectionFootnote(stringResource(R.string.airpods_ui_background_note))

    Pane { PaneBlock { HostAutomationSummary(state) } }

    Pane {
        PreferredModePicker(state, dispatch, inset = true)
        FluidListDivider()
        PaneBlock {
            NumberEditor(stringResource(R.string.airpods_feature_setting_host_volume), state.localVolumePercent, 0..100,
                state.allowed("host.volume"), reason = state.availability["host.volume"]?.reason) { dispatch(AirPodsUiAction.LocalVolume(it)) }
        }
    }
}

@Composable private fun ColumnScope.SetupPage(state: AirPodsUiState, dispatch: (AirPodsUiAction) -> Unit) {
    Pane {
        Text(stringResource(R.string.airpods_ui_choose), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = PaneInset, top = 12.dp, end = PaneInset, bottom = 8.dp))
        // Only with the permission is an empty list a fact about the phone.
        if (state.candidates.isEmpty() && state.bluetoothGranted) Text(
            stringResource(R.string.airpods_feature_no_candidates), style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = PaneInset, end = PaneInset, bottom = 12.dp))
        state.candidates.forEach { candidate ->
            FluidListDivider()
            FluidChoiceRow(candidate.displayName, selected = candidate.id == state.selectedCandidateId,
                onSelect = { dispatch(AirPodsUiAction.SelectPair(candidate.id)) },
                subtitle = candidate.identityHint)
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
            SetupAction.BLUETOOTH_SETTINGS to R.string.airpods_feature_open_bluetooth,
            leadingDivider = false)
    }
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

@Composable private fun ColumnScope.DiagnosticsPage(state: AirPodsUiState, dispatch: (AirPodsUiAction) -> Unit) {
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
}

// ---------------------------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------------------------

/**
 * The label over a group: small, in the secondary ink, in the words of the resource.
 *
 * Sentence case, as upstream writes its group titles; these strings are also what the pages are
 * read by, and capitals would make "Press and hold" a different string.
 */
@Composable private fun GroupLabel(title: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = PaneInset, top = 4.dp).semantics { heading() })
}

/** One group of the page: the Engine's Content glass over the canvas, opaque where there is none. */
@Composable private fun Pane(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    FluidListGroup(modifier = modifier, glass = true, content = content)
}

/** Content of a pane that is not a list row, on the row's own margins. */
@Composable private fun PaneBlock(spacing: Dp = 8.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = PaneInset, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(spacing), content = content)
}

/**
 * A row that opens a page: its name, what is currently there, and the chevron.
 *
 * Upstream's `StyledListItem(name, description, onClick)`, as the Engine's row.
 */
@Composable private fun OpenRow(title: Int, page: AirPodsSettingsPage, onOpen: (AirPodsSettingsPage) -> Unit,
    value: String? = null, tag: String = "airpods-open-$page") {
    val badge: (@Composable () -> Unit)? = if (value == null) null else ({
        Text(value, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 160.dp))
    })
    FluidListRow(stringResource(title), "",
        modifier = Modifier.testTag(tag),
        badge = badge,
        onClick = { onOpen(page) })
}

/** The received identity, in one line. Shown only on pages the user opened to read it. */
@Composable private fun IdentityLine(id: AirPodsIdentity) {
    Text(listOfNotNull(id.manufacturer, id.name, id.modelNumber, id.firmware, id.version2, id.version3, id.hardwareRevision, id.updaterIdentifier, id.serialNumber, id.leftSerialNumber, id.rightSerialNumber).joinToString(" · "),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = PaneInset, vertical = 12.dp))
}

/**
 * The selector with what it cannot say by itself under it: that a request is outstanding or went
 * unanswered, that no mode has been received, and why a mode cannot be asked for.
 *
 * A mode is requested, never set: the thumb is the mode the device last reported.
 */
@Composable internal fun ModeControl(s: AirPodsUiState, dispatch: (AirPodsUiAction) -> Unit, tagPrefix: String,
    modifier: Modifier = Modifier) {
    val view = s.domain.propertyView(CommandProperty.MODE) ?: return
    val received = s.currentMode
    val requestedWire = (view.pending?.requested as? PropertyValue.Number)?.value
    val requested = ListeningMode.entries.firstOrNull { it.wire == requestedWire }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AirPodsNoiseControl(
            modes = s.modes,
            received = received,
            requested = requested,
            onRequest = { mode ->
                val value = PropertyValue.Number(mode.wire)
                if (mode != received && s.writeAllowed(CommandProperty.MODE, value)) ({
                    dispatch(AirPodsUiAction.Write(s.domain.generation, CommandProperty.MODE, value))
                }) else null
            },
            tagPrefix = tagPrefix,
        )
        when {
            view.status == PropertyStatus.PENDING ->
                StatusLine(stringResource(R.string.airpods_ui_pending), StatusKind.Waiting)
            view.status == PropertyStatus.NOT_CONFIRMED ->
                StatusLine(stringResource(R.string.airpods_ui_not_confirmed), StatusKind.Problem)
            received == null && s.controlsReady ->
                StatusLine(stringResource(R.string.airpods_ui_candidate), StatusKind.Neutral)
        }
        AvailabilityReason(s, "property.MODE")
    }
}

/** A switch for an on/off control, the full editor for anything else. */
@Composable internal fun PropertyControl(s: AirPodsUiState, spec: PropertySpec, dispatch: (AirPodsUiAction) -> Unit) {
    if (spec.encoding == ControlEncoding.BOOLEAN_1_2) PropertySwitchRow(s, spec, dispatch)
    else PropertyEditor(s, spec, dispatch)
}

/**
 * An on/off device control as upstream draws it: one row with a switch.
 *
 * The switch is the received value and nothing else, so it exists only once the device has
 * reported one. A touch asks for the other value and leaves the switch where it is until the
 * answer arrives; while it is awaited the row says so and cannot be touched. With nothing
 * received there is no position to draw: the row shows "--", or, when a value may be tried, the
 * two alternatives with neither of them selected.
 */
@Composable internal fun PropertySwitchRow(s: AirPodsUiState, spec: PropertySpec, dispatch: (AirPodsUiAction) -> Unit) {
    val view = s.domain.propertyView(spec.property) ?: return
    val value = (view.observed?.value as? PropertyValue.Number)?.value
    val title = stringResource(featureTitles.getValue(spec.catalogId))
    val reason = s.availability["property.${spec.property}"]?.takeIf { !it.enabled }?.reason
    if (value == null) {
        val tryable = spec.options.keys.any { s.writeAllowed(spec.property, PropertyValue.Number(it)) }
        if (tryable || view.status == PropertyStatus.PENDING) PropertyEditor(s, spec, dispatch)
        else FluidListRow(title, reason.orEmpty(),
            modifier = Modifier.testTag("airpods-switch-${spec.property}").alpha(0.5f).semantics { disabled() },
            badge = { Text(stringResource(R.string.airpods_ui_missing), style = MaterialTheme.typography.bodyLarge) },
            disclosure = false)
        return
    }
    val other = PropertyValue.Number(if (value == 1) 2 else 1)
    val words = when (view.status) {
        PropertyStatus.PENDING -> stringResource(R.string.airpods_ui_pending)
        PropertyStatus.NOT_CONFIRMED -> stringResource(R.string.airpods_ui_not_confirmed)
        else -> reason.orEmpty()
    }
    FluidToggleRow(title, checked = value == 1,
        onCheckedChange = {
            // Checked again at the touch: what was allowed when the row was drawn may not be now.
            if (s.writeAllowed(spec.property, other)) dispatch(AirPodsUiAction.Write(s.domain.generation, spec.property, other))
        },
        modifier = Modifier.testTag("airpods-switch-${spec.property}"),
        subtitle = words,
        enabled = s.writeAllowed(spec.property, other))
}

@Composable private fun SetupRows(s: AirPodsUiState, dispatch: (AirPodsUiAction) -> Unit,
    vararg rows: Pair<SetupAction, Int>, leadingDivider: Boolean = true) {
    rows.forEachIndexed { index, row ->
        if (leadingDivider || index > 0) FluidListDivider()
        SetupButton(s, row.first, row.second, dispatch)
    }
}

/** Host preferences of one group, as switches. A blocked switch says why in its second line. */
@Composable private fun LocalToggleRows(state: AirPodsUiState, dispatch: (AirPodsUiAction) -> Unit, toggles: List<LocalToggle>) {
    toggles.forEachIndexed { index, toggle ->
        if (index > 0) FluidListDivider()
        val saved = state.pair.localToggleValue(toggle)
        val masterOff = !state.pair.host.automationsEnabled
        val childInactive = masterOff && toggle != LocalToggle.AUTOMATIONS &&
            (toggle in automationToggles || toggle == LocalToggle.HEAD_GESTURES)
        // Stored consents must remain revocable without first enabling automation.
        val revokeConsent = saved && toggle in setOf(LocalToggle.OTHER_APPS, LocalToggle.BACKGROUND)
        val enabled = state.allowed("local.$toggle") && (!childInactive || revokeConsent)
        val reason = state.availability["local.$toggle"]?.reason
            ?: if (childInactive || (masterOff && toggle == LocalToggle.AUTOMATIONS))
                stringResource(R.string.airpods_reason_automations_off) else null
        FluidToggleRow(stringResource(toggleLabels.getValue(toggle)), saved,
            { dispatch(AirPodsUiAction.Toggle(toggle, it)) }, enabled = enabled,
            subtitle = if (toggle == LocalToggle.OTHER_APPS) listOfNotNull(
                stringResource(R.string.airpods_ui_other_apps_consent), reason).joinToString("\n")
            else reason.orEmpty())
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

private val batteryComponents = listOf(BatteryLeft to R.string.airpods_feature_left,
    BatteryRight to R.string.airpods_feature_right, BatteryCase to R.string.airpods_feature_case)

@Composable private fun ageLabel(nowMs: Long, atMs: Long): String {
    val age = (nowMs - atMs).takeIf { it >= 0 } ?: return stringResource(R.string.airpods_ui_age_unknown)
    return when {
        age < 60_000 -> stringResource(R.string.airpods_ui_age_now)
        age < 3_600_000 -> stringResource(R.string.airpods_ui_age_minutes, age / 60_000)
        age < 86_400_000 -> stringResource(R.string.airpods_ui_age_hours, age / 3_600_000)
        else -> stringResource(R.string.airpods_ui_age_days, age / 86_400_000)
    }
}

// History is exclusive to the settings page, and never duplicates a live component.
@Composable private fun BatteryHistory(s: AirPodsUiState) {
    batteryComponents.forEach { (component, title) ->
        if (s.freshBattery(component) == null) s.domain.batteries[component]?.let { rx ->
            val percent = rx.value.percent?.let { stringResource(R.string.airpods_feature_percent, it) }
                ?: stringResource(R.string.airpods_ui_missing)
            Text(stringResource(R.string.airpods_ui_battery_history, stringResource(title), percent,
                ageLabel(s.nowMs, rx.atMs)), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * A row that opens a menu of alternatives: what it is, what is saved, and where it is saved.
 *
 * One line of text on purpose ("Nod · Play / pause"): the saved choice is never a second node
 * carrying the bare word of an alternative, which is what the menu's own entries are.
 */
@Composable private fun MenuRow(label: String, value: String, enabled: Boolean, onClick: () -> Unit,
    modifier: Modifier = Modifier, note: String? = null, inset: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    val shape = ContinuousCornerShape(FluidRadius.Control)
    Row(
        modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { if (!enabled) disabled() }
            .alpha(if (enabled) 1f else 0.5f)
            .fluidRowPressable(onClick = if (enabled) onClick else null, shape = if (inset) null else shape,
                role = Role.Button)
            // In a pane the row is the pane's; alone on a sheet it brings its own well.
            .then(if (inset) Modifier else Modifier.clip(shape).background(scheme.onSurface.copy(alpha = 0.06f)))
            .heightIn(min = 48.dp)
            .padding(horizontal = if (inset) PaneInset else 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(buildAnnotatedString {
                append(label)
                withStyle(SpanStyle(color = scheme.onSurfaceVariant)) { append(" · "); append(value) }
            }, style = MaterialTheme.typography.bodyLarge)
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        Icon(PhosphorIcons.Regular.CaretUpDown, contentDescription = null, modifier = Modifier.size(18.dp),
            tint = scheme.onSurfaceVariant)
    }
}

@Composable private fun PreferredModePicker(s: AirPodsUiState, dispatch: (AirPodsUiAction) -> Unit, inset: Boolean) {
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

@Composable internal fun GesturePicker(s: AirPodsUiState, side: GestureSide, dispatch: (AirPodsUiAction) -> Unit) {
    val title = stringResource(when(side) { GestureSide.LEFT -> R.string.airpods_ui_left; GestureSide.RIGHT -> R.string.airpods_ui_right; GestureSide.NOD -> R.string.airpods_ui_nod; GestureSide.SHAKE -> R.string.airpods_ui_shake })
    val options = gestureOptions(side).map { action ->
        FluidContextAction(stringResource(gestureLabels.getValue(action)), selected = s.gestureAssignments[side] == action) {
            dispatch(AirPodsUiAction.AssignGesture(side, action))
        }
    }
    val menu = rememberFluidContextMenu(actions = { options })
    val saved = s.gestureAssignments[side]?.takeIf { it in gestureOptions(side) }
    val stem = side == GestureSide.LEFT || side == GestureSide.RIGHT
    MenuRow(
        label = title,
        value = saved?.let { stringResource(gestureLabels.getValue(it)) } ?: stringResource(R.string.airpods_ui_choose_action),
        enabled = s.allowed("gesture.$side") && (!stem || s.stemPresentation().editable),
        onClick = { menu.open() },
        modifier = Modifier.testTag("airpods-gesture-$side").fluidContextMenuAnchor(menu),
        // A long press is a choice kept on the phone; what the earbuds do with it is the shared status below.
        note = if (stem) stringResource(R.string.airpods_stem_local_choice) else null,
    )
    if (!stem) AvailabilityReason(s, "gesture.$side",
        Modifier.padding(start = PaneInset, end = PaneInset, bottom = 12.dp))
}

private fun statusKind(status: Int): StatusKind = when (status) {
    R.string.airpods_ui_observed -> StatusKind.Confirmed
    R.string.airpods_ui_pending -> StatusKind.Waiting
    R.string.airpods_ui_not_confirmed -> StatusKind.Problem
    else -> StatusKind.Neutral
}

@Composable internal fun PropertyEditor(s: AirPodsUiState, spec: PropertySpec, dispatch: (AirPodsUiAction) -> Unit) {
    val view = s.domain.propertyView(spec.property) ?: return
    val value = if(spec.property == CommandProperty.MODE) s.currentMode?.wire else (view.observed?.value as? PropertyValue.Number)?.value
    val status = when(view.status) { PropertyStatus.UNKNOWN -> R.string.airpods_ui_unknown; PropertyStatus.CANDIDATE -> R.string.airpods_ui_candidate; PropertyStatus.OBSERVED -> R.string.airpods_ui_observed; PropertyStatus.PENDING -> R.string.airpods_ui_pending; PropertyStatus.NOT_CONFIRMED -> R.string.airpods_ui_not_confirmed; PropertyStatus.DEPENDENCY_EXCLUDED -> R.string.airpods_ui_excluded; PropertyStatus.UNAVAILABLE -> R.string.airpods_ui_unavailable }
    val displayStatus = if(spec.property == CommandProperty.MODE && s.currentMode != null &&
        view.status == PropertyStatus.CANDIDATE) R.string.airpods_ui_observed else status
    val title = stringResource(featureTitles.getValue(spec.catalogId))
    val requested = (view.pending?.requested as? PropertyValue.Number)?.value

    val scope = rememberCoroutineScope()
    var debounce by remember { mutableStateOf<Job?>(null) }
    val latest by rememberUpdatedState(s)
    DisposableEffect(s.domain.generation) { onDispose { debounce?.cancel() } }
    fun send(v: Int) {
        val capturedGeneration = s.domain.generation
        debounce?.cancel()
        debounce = scope.launch {
            if(spec.debounceMs > 0) delay(spec.debounceMs)
            val pv = PropertyValue.Number(v)
            if(latest.domain.generation == capturedGeneration && latest.writeAllowed(spec.property,pv))
                dispatch(AirPodsUiAction.Write(capturedGeneration,spec.property,pv))
        }
    }

    Column(Modifier.fillMaxWidth()) {
        // The name, then what is known about it, then why it cannot be changed: three separate
        // texts, in that order, before any alternative.
        Column(Modifier.fillMaxWidth().padding(start = PaneInset, top = 12.dp, end = PaneInset, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            StatusLine(stringResource(displayStatus), statusKind(displayStatus))
            // Owner-localized current cause, separate from candidate/observed/pending status.
            if (spec.options.isNotEmpty() || spec.encoding == ControlEncoding.MODE_MASK)
                AvailabilityReason(s, "property.${spec.property}")
        }
        if(spec.encoding == ControlEncoding.MODE_MASK) {
            var draftMask by remember(value,s.domain.generation) { mutableStateOf<Int?>(null) }
            // The reply may repeat the previous RX value. Retire the submitted draft on
            // completion/timeout too; remembering only the value would keep that draft
            // visible beside an observed (or not-confirmed) status after pending ends.
            LaunchedEffect(view.observed, view.pending?.sequence) {
                if (view.pending == null) draftMask = null
            }
            // Upstream's order and upstream's marks (PressAndHoldSettingsScreen).
            noiseControlOrder.filter { it in s.modes }.forEach { mode ->
                val bit = 1 shl (mode.wire-1)
                val mask = draftMask ?: value
                FluidToggleRow(modeLabel(mode.wire), mask != null && mask and bit != 0,
                    { draftMask = (mask ?: 0) xor bit },
                    enabled = s.controlsReady && spec.property !in s.domain.pending && s.identity?.let(spec::candidate) == true,
                    leading = { Icon(painterResource(modeIconRes(mode.wire)), contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp)) },
                    leadingTile = false)
            }
            Row(Modifier.fillMaxWidth().padding(start = PaneInset, top = 4.dp, end = PaneInset, bottom = 12.dp),
                horizontalArrangement = Arrangement.End) {
                FluidButton(stringResource(R.string.airpods_ui_apply), { draftMask?.let(::send) },
                    style = FluidButtonStyle.Tinted, grip = FluidPressGrip.YieldsToScroll,
                    enabled = draftMask?.let { s.writeAllowed(spec.property,PropertyValue.Number(it)) } == true)
            }
        } else if(spec.options.isNotEmpty()) {
            // One row per alternative with a check on the received one, as upstream lists a choice.
            // A row that cannot be asked for has no click action at all, not a disabled one.
            val shown = spec.options.filter { spec.property != CommandProperty.MODE || s.modes.any { mode -> mode.wire == it.key } }
            shown.forEach { (v,label) ->
                val isReceived = value == v
                val allowed = s.writeAllowed(spec.property,PropertyValue.Number(v))
                FluidListDivider()
                FluidListRow(
                    if(spec.property == CommandProperty.MODE) modeLabel(v) else stringResource(optionLabels.getValue(label)), "",
                    modifier = Modifier
                        .semantics { role = Role.RadioButton; selected = isReceived; if (!allowed) disabled() }
                        // The received value stays legible while the control is unavailable.
                        .alpha(if (allowed || isReceived) 1f else 0.5f),
                    badge = { ChoiceMark(selected = isReceived, awaiting = requested == v) },
                    onClick = if(allowed) ({send(v)}) else null,
                    disclosure = false,
                )
            }
        } else {
            // An absent RX has no selected default. A draft is created only by explicit interaction.
            Column(Modifier.fillMaxWidth().padding(start = PaneInset, end = PaneInset, bottom = 12.dp)) {
                NumberEditor(title,value,spec.range,
                    s.controlsReady && spec.property !in s.domain.pending && s.identity?.let(spec::candidate) == true,
                    reason = s.availability["property.${spec.property}"]?.reason,
                    reasonTag = "airpods-reason-property.${spec.property}", onApply = ::send)
            }
        }
    }
}

@Composable private fun AvailabilityReason(s: AirPodsUiState, key: String, modifier: Modifier = Modifier) {
    s.availability[key]?.takeIf { !it.enabled }?.reason?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.testTag("airpods-reason-$key"))
    }
}

/**
 * A number with its received value in words, a slider (or a field while nothing was received),
 * and an explicit Apply.
 *
 * The words always show the received value, never the draft: the draft stands beside Apply, in
 * the accent, for as long as it is only an intention.
 */
@Composable private fun NumberEditor(label: String, value: Int?, range: IntRange, enabled: Boolean,
    reason: String? = null, reasonTag: String = "airpods-number-reason", onApply: (Int) -> Unit) {
    var draft by remember(value) { mutableStateOf<Int?>(null) }
    // Nothing received and nothing allowed: there is no value to show and none to propose.
    val editable = enabled || value != null
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(label + " · " + (value?.toString() ?: stringResource(R.string.airpods_ui_missing)),
                style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            draft?.let {
                Text(it.toString(), style = FluidTextStyles.numeric, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            }
            if (editable) FluidButton(stringResource(R.string.airpods_ui_apply), { draft?.let(onApply); draft = null },
                enabled = enabled && draft != null, style = FluidButtonStyle.Tinted,
                modifier = Modifier.heightIn(min = 44.dp), grip = FluidPressGrip.YieldsToScroll)
        }
        if (!enabled) reason?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(reasonTag))
        }
        if (editable) {
            if (value == null) {
                var entry by remember { mutableStateOf("") }
                FluidTextField(value = entry, onValueChange = { entry = it; draft = it.toIntOrNull()?.takeIf { n -> n in range } },
                    placeholder = label, enabled = enabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            } else FluidSlider(value = (draft ?: value).toFloat(), onValueChange = { draft = it.roundToInt() },
                valueRange = range.first.toFloat()..range.last.toFloat(), enabled = enabled, contentDescription = label)
        }
    }
}

@Composable private fun RenameEditor(s: AirPodsUiState, dispatch: (AirPodsUiAction) -> Unit) {
    var name by remember(s.domain.generation) { mutableStateOf("") }
    val value = PropertyValue.Name(name)
    PaneBlock {
        CommandStatusSummary(s,CommandProperty.NAME)
        FluidTextField(value = name, onValueChange = { name = it }, placeholder = stringResource(R.string.airpods_ui_name))
        FluidButton(stringResource(R.string.airpods_feature_setting_device_rename), {dispatch(AirPodsUiAction.Write(s.domain.generation,CommandProperty.NAME,value))},
            enabled = s.writeAllowed(CommandProperty.NAME,value), style = FluidButtonStyle.Tinted, fillWidth = true,
            grip = FluidPressGrip.YieldsToScroll)
    }
}

@Composable private fun EqEditor(s: AirPodsUiState, dispatch: (AirPodsUiAction) -> Unit) {
    val rx = s.domain.propertyObserved(CommandProperty.EQ)?.value as? PropertyValue.Eq
    var enabled by remember(rx,s.domain.generation) { mutableStateOf(rx?.enabled) }
    var low by remember(rx,s.domain.generation) { mutableStateOf(rx?.low) }
    var mid by remember(rx,s.domain.generation) { mutableStateOf(rx?.mid) }
    var high by remember(rx,s.domain.generation) { mutableStateOf(rx?.high) }
    val editable = s.controlsReady && CommandProperty.EQ !in s.domain.pending
    PaneBlock {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.airpods_feature_setting_device_eq), style = MaterialTheme.typography.titleMedium)
            CommandStatusSummary(s,CommandProperty.EQ)
        }
        AdaptiveCells(Modifier.fillMaxWidth()) {
            listOf(true,false).forEach { v ->
                OptionCell(stringResource(if(v) R.string.airpods_ui_on else R.string.airpods_ui_off),
                    selected = enabled == v, onClick = if(editable) ({enabled=v}) else null)
            }
        }
        NumberEditor(stringResource(R.string.airpods_ui_low),low,0..100,editable) {low=it}
        NumberEditor(stringResource(R.string.airpods_ui_mid),mid,0..100,editable) {mid=it}
        NumberEditor(stringResource(R.string.airpods_ui_high),high,0..100,editable) {high=it}
        val eq = if(enabled != null && low != null && mid != null && high != null) PropertyValue.Eq(enabled!!,low!!,mid!!,high!!) else null
        FluidButton(stringResource(R.string.airpods_ui_apply),{eq?.let {dispatch(AirPodsUiAction.Write(s.domain.generation,CommandProperty.EQ,it))}},
            enabled=eq != null && s.writeAllowed(CommandProperty.EQ,eq) && eq != rx, fillWidth = true,
            grip = FluidPressGrip.YieldsToScroll)
    }
}

@Composable private fun CommandStatusSummary(s: AirPodsUiState, property: CommandProperty) {
    val status = when {
        property in s.domain.pending -> R.string.airpods_ui_pending
        s.domain.outcomes[property] == CommandOutcome.NOT_CONFIRMED -> R.string.airpods_ui_not_confirmed
        property == CommandProperty.MODE && s.currentMode != null -> R.string.airpods_ui_observed
        s.domain.propertyObserved(property) != null -> R.string.airpods_ui_observed
        else -> R.string.airpods_ui_candidate
    }
    StatusLine(stringResource(status), statusKind(status))
}

@Composable private fun TrackingPlot(samples: List<HeadSample>) {
    val color = MaterialTheme.colorScheme.primary
    val axis = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    Canvas(Modifier.fillMaxWidth().height(96.dp)) {
        // The zero line is always there, so an empty plot reads as a plot with nothing in it.
        drawLine(axis, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx())
        val data = samples.takeLast(120)
        if(data.size > 1) {
            val max = data.maxOf { kotlin.math.abs(it.horizontal.toFloat()) }.coerceAtLeast(1f)
            val path = Path()
            data.forEachIndexed { i,s -> val x=i*size.width/(data.size-1); val y=size.height/2-s.horizontal/max*size.height/2; if(i==0) path.moveTo(x,y) else path.lineTo(x,y) }
            drawPath(path,color,style=Stroke(2.dp.toPx()))
        }
    }
}

@Composable internal fun modeLabel(wire: Int) = stringResource(when(wire) {1->R.string.airpods_feature_mode_off;2->R.string.airpods_feature_mode_anc;3->R.string.airpods_feature_mode_transparency;else->R.string.airpods_feature_mode_adaptive})

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
        Text(stringResource(R.string.airpods_ui_host_observational), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
