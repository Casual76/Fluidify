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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.adamglin.phosphoricons.regular.Warning
import dev.antigravity.fluidengine.ui.fluid.*
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidStatusBadge
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.lelonio.square.R
import dev.lelonio.square.airpods.*
import dev.lelonio.square.airpods.protocol.*

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
/** What the device calls itself, else what its model is called, else the feature's own name. */
@Composable
internal fun deviceTitle(s: AirPodsUiState): String =
    s.headerName?.takeIf { it.isNotBlank() }
        ?: s.artworkChoice().modelName
        ?: stringResource(R.string.airpods_feature_title)

/**
 * The pictures and the charge, and under them only what the pictures cannot say: that this is a
 * test session or why there is no live reading. Cached case charge is beside its picture.
 */
@Composable internal fun DeviceHeader(s: AirPodsUiState) {
    Column(Modifier.fillMaxWidth().testTag("airpods-summary"), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AirPodsBatteryView(s)
        if (s.testAuthorized) FluidStatusBadge("TEST / NV", tone = FluidTone.Warning)
        if (!s.controlsReady) StatusLine(stringResource(s.connectionSummary()), s.connectionKind(),
            Modifier.testTag("airpods-summary-state"), style = MaterialTheme.typography.bodyMedium)
    }
}

// ---------------------------------------------------------------------------------------------
// The pages behind the rows
// ---------------------------------------------------------------------------------------------

@Composable internal fun ColumnScope.RenamePage(state: AirPodsUiState, dispatch: (AirPodsUiAction) -> Unit) {
    Pane {
        RenameEditor(state, dispatch)
    }
}

/** The received identity, in one line. Shown only on pages the user opened to read it. */
@Composable internal fun IdentityLine(id: AirPodsIdentity) {
    Text(listOfNotNull(id.modelNumber, id.firmware, id.version2, id.version3, id.hardwareRevision, id.updaterIdentifier, id.serialNumber, id.leftSerialNumber, id.rightSerialNumber).joinToString(" · "),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = PaneInset, vertical = 12.dp))
}

@Composable private fun RenameEditor(s: AirPodsUiState, dispatch: (AirPodsUiAction) -> Unit) {
    val knownName = s.headerName.orEmpty()
    var draft by remember(s.domain.generation) { mutableStateOf(AirPodsRenameDraft(knownName)) }
    LaunchedEffect(knownName) { draft = draft.reconcile(knownName) }
    val value = PropertyValue.Name(draft.text)
    PaneBlock {
        FluidTextField(value = draft.text, onValueChange = { draft = draft.edit(it) },
            placeholder = stringResource(R.string.airpods_ui_name), modifier = Modifier.testTag("airpods-rename-name"))
        FluidButton(stringResource(R.string.airpods_feature_setting_device_rename), {
            draft = draft.submitted()
            dispatch(AirPodsUiAction.Write(s.domain.generation,CommandProperty.NAME,value))
        },
            enabled = s.writeAllowed(CommandProperty.NAME,value), style = FluidButtonStyle.Tinted, fillWidth = true,
            grip = FluidPressGrip.YieldsToScroll)
    }
}
