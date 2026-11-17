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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CaretUpDown
import dev.antigravity.fluidengine.ui.fluid.*
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.lelonio.square.R
import dev.lelonio.square.airpods.*
import dev.lelonio.square.airpods.protocol.*
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Inner margin of a pane that is not a list row; the same sixteen a row keeps for its text. */
internal val PaneInset = 16.dp

internal fun effectiveNumber(s: AirPodsUiState, property: CommandProperty): Int? =
    (s.domain.propertyEffective(property)?.value as? PropertyValue.Number)?.value

// ---------------------------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------------------------

/**
 * The label over a group: small, in the secondary ink, in the words of the resource.
 *
 * Sentence case, as upstream writes its group titles; these strings are also what the pages are
 * read by, and capitals would make "Press and hold" a different string.
 */
@Composable internal fun GroupLabel(title: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = PaneInset, top = 4.dp).semantics { heading() })
}

/** One group of the page: the Engine's Content glass over the canvas, opaque where there is none. */
@Composable internal fun Pane(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    FluidListGroup(modifier = modifier, glass = true, content = content)
}

/** Content of a pane that is not a list row, on the row's own margins. */
@Composable internal fun PaneBlock(spacing: Dp = 8.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = PaneInset, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(spacing), content = content)
}

/**
 * A row that opens a page: its name, what is currently there, and the chevron.
 *
 * Upstream's `StyledListItem(name, description, onClick)`, as the Engine's row.
 */
@Composable internal fun OpenRow(title: Int, page: AirPodsSettingsPage, onOpen: (AirPodsSettingsPage) -> Unit,
    value: String? = null, tag: String = "airpods-open-$page") {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Trailing content is measured first by ListItem. At large fonts give the value
        // its own line, rather than leaving the title a column only a few letters wide.
        val stacked = androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.5f
        val valueWidth = maxWidth * 0.4f
        val badge: (@Composable () -> Unit)? = if (value == null || stacked) null else ({
            Text(value, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = valueWidth))
        })
        FluidListRow(stringResource(title), if (stacked) value.orEmpty() else "",
            modifier = Modifier.testTag(tag),
            badge = badge,
            onClick = { onOpen(page) })
    }
}

/** Host preferences of one group, as switches. A blocked switch says why in its second line. */
@Composable internal fun LocalToggleRows(state: AirPodsUiState, dispatch: (AirPodsUiAction) -> Unit, toggles: List<LocalToggle>) {
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

/**
 * A row that opens a menu of alternatives: what it is, what is saved, and where it is saved.
 *
 * One line of text on purpose ("Nod · Play / pause"): the saved choice is never a second node
 * carrying the bare word of an alternative, which is what the menu's own entries are.
 */
@Composable internal fun MenuRow(label: String, value: String, enabled: Boolean, onClick: () -> Unit,
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

@Composable internal fun AvailabilityReason(s: AirPodsUiState, key: String, modifier: Modifier = Modifier) {
    s.availability[key]?.takeIf { !it.enabled }?.reason?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.testTag("airpods-reason-$key"))
    }
}

