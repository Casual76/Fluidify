// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.ui.airpods

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Check
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.FluidSpinner
import dev.antigravity.fluidengine.ui.fluid.fluidRowPressable
import dev.lelonio.square.R
import dev.lelonio.square.airpods.AacpStage
import dev.lelonio.square.airpods.CommandProperty
import dev.lelonio.square.airpods.protocol.AirPodsProperties
import dev.lelonio.square.airpods.protocol.PropertySpec

// Presentation only. Nothing here reads a socket, starts work or decides an authority: every
// function turns facts the host already supplied into a word, a mark or a place on the page.

/** What a status mark may claim. A filled mark is reserved for something the device or host reported. */
internal enum class StatusKind { Confirmed, Waiting, Problem, Neutral }

private val connectingStages = setOf(
    AacpStage.CONSTRUCTING, AacpStage.CONSTRUCTED, AacpStage.CONNECTING,
    AacpStage.CONNECTED, AacpStage.HANDSHAKE_SENT,
)

/**
 * The one line the page and the quick pane say about the connection.
 *
 * Ordered by what makes the later facts unknowable: without the Bluetooth permission the
 * connection cannot be verified at all, a stopped service is not "disconnected", and an audio
 * profile that is up says nothing about the controls channel. "Connected" needs both the host's
 * profile fact and a subscribed, authorized session; everything short of that names what is missing.
 */
internal fun AirPodsUiState.connectionSummary(): Int = when {
    !bluetoothGranted -> R.string.airpods_reason_bluetooth_permission
    suppressed -> R.string.airpods_feature_stopped
    !profileConnected -> R.string.airpods_feature_disconnected
    domain.stage == AacpStage.ERROR -> R.string.airpods_ui_aacp_error
    controlsReady -> R.string.airpods_feature_connected
    domain.stage in connectingStages -> R.string.airpods_feature_connecting
    else -> R.string.airpods_feature_controls_unavailable
}

internal fun AirPodsUiState.connectionKind(): StatusKind = when (connectionSummary()) {
    R.string.airpods_feature_connected -> StatusKind.Confirmed
    R.string.airpods_feature_connecting -> StatusKind.Waiting
    R.string.airpods_ui_aacp_error -> StatusKind.Problem
    else -> StatusKind.Neutral
}

/**
 * The pages of the AirPods settings, in LibrePods' shape: one page of groups, and a page behind
 * every row that needs more room than a row.
 */
enum class AirPodsSettingsPage {
    Main, Rename, LongPressLeft, LongPressRight, Calls, AdaptiveStrength, Equalizer, Controls,
    HeadGestures, Automations, Setup, Diagnostics,
}

/**
 * The one page a device property is edited on.
 *
 * Exhaustive on purpose, with no `else`: a property added to the protocol does not compile until
 * somebody says where it goes, so no control can be left off every page by omission. The shape
 * follows upstream's `AirPodsSettingsScreen`: switches stay on the main page, a choice among
 * several alternatives or a number gets a page of its own.
 */
internal fun airPodsSettingsPageOf(property: CommandProperty): AirPodsSettingsPage = when (property) {
    CommandProperty.MODE,
    CommandProperty.ADAPTIVE_VOLUME_CONFIG,
    CommandProperty.CONVERSATION,
    CommandProperty.EAR_DETECTION_CONFIG,
    CommandProperty.AUTOMATIC_CONNECTION_CONFIG,
    CommandProperty.SLEEP_DETECTION_CONFIG,
    CommandProperty.DYNAMIC_END_OF_CHARGE,
    CommandProperty.PPE_TOGGLE_CONFIG,
    CommandProperty.ALLOW_OFF_OPTION -> AirPodsSettingsPage.Main
    // One mask for both stems: shown on either long-press page, filed under the left one.
    CommandProperty.LISTENING_MODE_CONFIGS,
    CommandProperty.STEM_CONFIG -> AirPodsSettingsPage.LongPressLeft
    CommandProperty.MIC_MODE,
    CommandProperty.CALL_MANAGEMENT_CONFIG -> AirPodsSettingsPage.Calls
    CommandProperty.AUTO_ANC_STRENGTH -> AirPodsSettingsPage.AdaptiveStrength
    CommandProperty.EQ -> AirPodsSettingsPage.Equalizer
    CommandProperty.NAME -> AirPodsSettingsPage.Rename
    CommandProperty.DOUBLE_CLICK_INTERVAL,
    CommandProperty.CLICK_HOLD_INTERVAL,
    CommandProperty.ONE_BUD_ANC_MODE,
    CommandProperty.CHIME_VOLUME,
    CommandProperty.VOLUME_SWIPE_INTERVAL,
    CommandProperty.VOLUME_SWIPE_MODE -> AirPodsSettingsPage.Controls
}

/**
 * The public registry controls of [page], in registry order.
 *
 * The registry still owns membership: a page asks for its controls here instead of naming them,
 * so every public control is on exactly one page and an internal dependency is on none.
 */
internal fun airPodsPageSpecs(
    page: AirPodsSettingsPage,
    controls: List<PropertySpec> = AirPodsProperties.controls,
): List<PropertySpec> = controls.filter { !it.internalDependency && airPodsSettingsPageOf(it.property) == page }

/**
 * Where upstream puts the main page's switches, top to bottom, one inner list per group.
 *
 * Only an order. A main-page control missing from here is still drawn, after these, by the page.
 */
internal val airPodsMainGroups: List<List<CommandProperty>> = listOf(
    listOf(CommandProperty.ADAPTIVE_VOLUME_CONFIG, CommandProperty.CONVERSATION),
    listOf(CommandProperty.EAR_DETECTION_CONFIG, CommandProperty.AUTOMATIC_CONNECTION_CONFIG),
    listOf(CommandProperty.SLEEP_DETECTION_CONFIG),
    listOf(CommandProperty.DYNAMIC_END_OF_CHARGE, CommandProperty.PPE_TOGGLE_CONFIG),
    listOf(CommandProperty.ALLOW_OFF_OPTION),
)

/** Host toggles that belong beside the gesture controls; every other one is an automation. */
internal val gestureToggles: List<LocalToggle> =
    listOf(LocalToggle.HEAD_GESTURES, LocalToggle.ALTERNATE_PACKETS)

internal val automationToggles: List<LocalToggle> = LocalToggle.entries - gestureToggles.toSet()

/**
 * A mark and the words it qualifies.
 *
 * The words are their own text node, exactly as given: tests and screen readers look for the
 * status string, and the mark only repeats in shape what the words already say. [modifier] goes on
 * the words for that reason.
 */
@Composable
internal fun StatusLine(
    text: String,
    kind: StatusKind,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall,
) {
    val scheme = MaterialTheme.colorScheme
    val ink = if (kind == StatusKind.Problem) scheme.error else scheme.onSurfaceVariant
    val confirmed = scheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(10.dp), contentAlignment = Alignment.Center) {
            if (kind == StatusKind.Waiting) {
                FluidSpinner(size = 10.dp, color = ink)
            } else {
                Canvas(Modifier.size(8.dp)) {
                    when (kind) {
                        StatusKind.Confirmed -> drawCircle(confirmed)
                        StatusKind.Problem -> drawCircle(ink)
                        // Hollow: nothing has been reported, and an empty ring cannot be read as a value.
                        else -> drawCircle(
                            color = ink,
                            radius = size.minDimension / 2f - 0.75.dp.toPx(),
                            style = Stroke(1.5.dp.toPx()),
                        )
                    }
                }
            }
        }
        Text(text, style = style, color = ink, modifier = modifier)
    }
}

/**
 * One alternative of a setting: a word in a well, the received one filled.
 *
 * A single node carrying the label, the selection and the click, so a choice reads as
 * "Left, selected" and a test can ask the same node for all three. Without [onClick] there is no
 * click action at all, not a disabled one: an alternative that cannot be requested must not
 * accept a request from any input. [awaiting] rings the alternative that was asked for while its
 * answer is outstanding; it is never drawn as selected until the device says so.
 */
@Composable
internal fun OptionCell(
    label: String,
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    awaiting: Boolean = false,
    leading: ImageVector? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = ContinuousCornerShape(FluidRadius.Control)
    val container = if (selected) scheme.primaryContainer else scheme.onSurface.copy(alpha = 0.06f)
    val content = if (selected) scheme.onPrimaryContainer else scheme.onSurface
    Row(
        modifier = modifier
            .semantics(mergeDescendants = true) {
                this.selected = selected
                role = Role.RadioButton
                if (onClick == null) disabled()
            }
            // The received value stays legible while the control is unavailable.
            .alpha(if (onClick != null || selected) 1f else 0.5f)
            .fluidRowPressable(onClick = onClick, shape = shape)
            .clip(shape)
            .background(container)
            .then(
                if (awaiting) Modifier.border(1.5.dp, scheme.primary.copy(alpha = 0.7f), shape)
                else if (selected) Modifier.border(1.5.dp, scheme.onSurface.copy(alpha = 0.8f), shape)
                else Modifier,
            )
            .heightIn(min = 44.dp)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Icon(leading, contentDescription = null, modifier = Modifier.size(18.dp), tint = content)
        }
        Text(
            text = label,
            // Measure the words in the width left by the icon, in both the
            // intrinsic pass and the real Row pass (including large fonts).
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = content,
            textAlign = TextAlign.Center,
        )
        // A received value must be recognizable without comparing two glass fills.
        // Reserve this space in every cell so an RX does not move or rewrap its label.
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            if (selected) Icon(PhosphorIcons.Regular.Check, contentDescription = null,
                modifier = Modifier.size(18.dp), tint = content)
        }
    }
}

/**
 * The trailing mark of a choice row: a check on the alternative the device reported, a spinner on
 * the one a request is still waiting on, and the same empty space on every other row so a choice
 * never moves its own label.
 */
@Composable
internal fun ChoiceMark(selected: Boolean, awaiting: Boolean) {
    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
        when {
            selected -> Icon(PhosphorIcons.Regular.Check, contentDescription = null,
                modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            awaiting -> FluidSpinner(size = 16.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Cells side by side while every one of them fits, one per line when one does not.
 *
 * Decided during measurement from the cells' own intrinsic widths, so there is no font-scale or
 * screen-width threshold to keep in step with nine translations: a long word stacks the group
 * instead of clipping a label. With [wrapLabels] a cell may break between words and only its
 * longest word has to fit; otherwise the whole label has to fit on one line. All cells share the
 * tallest height, so a wrapped neighbour does not leave a ragged row.
 */
@Composable
internal fun AdaptiveCells(
    modifier: Modifier = Modifier,
    columns: Int = Int.MAX_VALUE,
    gap: Dp = 6.dp,
    wrapLabels: Boolean = false,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val count = measurables.size
        if (count == 0) {
            layout(constraints.minWidth, constraints.minHeight) {}
        } else {
            val gapPx = gap.roundToPx()
            val wanted = minOf(columns, count).coerceAtLeast(1)
            var perRow = wanted
            var cell: Int
            if (constraints.hasBoundedWidth) {
                val gridCell = (constraints.maxWidth - gapPx * (wanted - 1)) / wanted
                val fits = gridCell > 0 && measurables.all { measurable ->
                    val needed = if (wrapLabels) measurable.minIntrinsicWidth(Constraints.Infinity)
                    else measurable.maxIntrinsicWidth(Constraints.Infinity)
                    needed <= gridCell
                }
                if (fits) {
                    cell = gridCell
                } else {
                    perRow = 1
                    cell = constraints.maxWidth
                }
            } else {
                cell = measurables.maxOf { it.maxIntrinsicWidth(Constraints.Infinity) }
            }
            cell = cell.coerceAtLeast(0)
            val cellHeight = measurables.maxOf { it.minIntrinsicHeight(cell) }
            val placeables = measurables.map { it.measure(Constraints.fixed(cell, cellHeight)) }
            val rows = (count + perRow - 1) / perRow
            val width = if (constraints.hasBoundedWidth) constraints.maxWidth
            else cell * perRow + gapPx * (perRow - 1)
            val height = rows * cellHeight + gapPx * (rows - 1)
            layout(width, height) {
                placeables.forEachIndexed { index, placeable ->
                    placeable.placeRelative(
                        x = (index % perRow) * (cell + gapPx),
                        y = (index / perRow) * (cellHeight + gapPx),
                    )
                }
            }
        }
    }
}
