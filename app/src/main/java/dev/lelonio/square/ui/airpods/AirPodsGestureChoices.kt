// SPDX-License-Identifier: GPL-3.0-or-later
// Choice structure derived from LibrePods, Copyright (C) 2025 LibrePods contributors.
// Pin 89356c08cea3db0b6b54792d9329d09a87cc85fa: PressAndHoldSettingsScreen,
// CallControlScreen and MicrophoneSettingsScreen. Rendering uses Fluid Engine.
package dev.lelonio.square.ui.airpods

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import dev.antigravity.fluidengine.ui.fluid.FluidChoiceRow
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidToneTileSize
import dev.lelonio.square.R
import dev.lelonio.square.airpods.CommandProperty
import dev.lelonio.square.airpods.ListeningMode
import dev.lelonio.square.airpods.protocol.*

internal fun callAssignment(mute: Boolean, once: Boolean): Int =
    if (mute == once) 3 else 2

internal fun toggledCycleMask(mask: Int, mode: ListeningMode): Int? {
    val next = mask xor (1 shl (mode.wire - 1))
    // An incomplete RX can be built up, but a valid cycle cannot be reduced below two.
    return next.takeUnless { Integer.bitCount(mask) >= 2 && Integer.bitCount(next) < 2 }
}

/** What one touch on a cycle switch does. A selection that cannot be sent is never shown as selected. */
internal sealed interface CycleTap {
    /** The resulting mask is valid: it goes on the wire at once (no Apply). */
    data class Dispatch(val mask: Int) : CycleTap
    /** Fewer than two modes: remembered locally, nothing sent, nothing shown as on. */
    data class Pending(val mask: Int) : CycleTap
    object Ignored : CycleTap
}

internal fun cycleTap(mask: Int, mode: ListeningMode, accepts: (Int) -> Boolean, canBuild: Boolean): CycleTap {
    val next = toggledCycleMask(mask, mode) ?: return CycleTap.Ignored
    return when {
        accepts(next) -> CycleTap.Dispatch(next)
        Integer.bitCount(next) < 2 && canBuild -> CycleTap.Pending(next)
        else -> CycleTap.Ignored
    }
}

/** A switch is on only for a mask the AirPods reported (or that was just sent), never for a pending local build. */
internal fun cycleSwitchChecked(mask: Int, mode: ListeningMode, pending: Boolean): Boolean =
    !pending && mask and (1 shl (mode.wire - 1)) != 0

@Composable internal fun ColumnScope.LongPressModeSwitches(s: AirPodsUiState, dispatch: (AirPodsUiAction) -> Unit) {
    val property = CommandProperty.LISTENING_MODE_CONFIGS
    val spec = AirPodsProperties.spec(property)!!
    if (!s.applies(spec)) return
    val observed = s.domain.propertyObserved(property)
    val effective = observed?.let { effectiveNumber(s, property) }
    // Zero/single-bit RX is legal to display, illegal to send. Keep only this incomplete
    // selection until the second explicit touch, then send immediately (no Apply).
    // Reset on RX, intent, generation or available-mode changes; never persist it as a reading.
    var incomplete by remember(s.domain.generation, effective, observed, s.modes) { mutableStateOf<Int?>(null) }
    val mask = incomplete ?: effective
    val unread = stringResource(R.string.airpods_ui_unknown)
    GroupLabel(featureTitles.getValue(spec.catalogId))
    Pane {
        noiseControlOrder.filter { it in s.modes }.forEachIndexed { index, mode ->
            if (index > 0) FluidListDivider()
            val label = modeLabel(mode.wire)
            val tag = Modifier.testTag("airpods-cycle-${mode.name}")
            val leading: (@Composable () -> Unit)? = if (androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.5f) null else {
                { Icon(painterResource(modeIconRes(mode.wire)), contentDescription = null,
                    modifier = Modifier.size(FluidToneTileSize).testTag("airpods-cycle-icon-${mode.name}")) }
            }
            if (mask == null) {
                FluidListRow(label, subtitle = "", modifier = tag.semantics { disabled(); stateDescription = unread },
                    badge = { Text(stringResource(R.string.airpods_ui_missing)) },
                    leading = leading, leadingTile = false, disclosure = false)
                return@forEachIndexed
            }
            val canBuild = s.controlsReady && s.identity?.let(spec::candidate) == true
            val tap = cycleTap(mask, mode, { s.writeAllowed(property, PropertyValue.Number(it)) }, canBuild)
            AirPodsReadableToggle(label, checked = cycleSwitchChecked(mask, mode, pending = incomplete != null),
                onCheckedChange = {
                    when (tap) {
                        is CycleTap.Dispatch -> {
                            incomplete = null
                            dispatch(AirPodsUiAction.Write(s.domain.generation, property, PropertyValue.Number(tap.mask)))
                        }
                        is CycleTap.Pending -> incomplete = tap.mask
                        CycleTap.Ignored -> Unit
                    }
                }, enabled = tap !is CycleTap.Ignored,
                modifier = tag, leading = leading,
                leadingTile = false)
        }
        if (mask != null && Integer.bitCount(mask) < 2) {
            FluidListDivider()
            PaneBlock {
                Text(stringResource(R.string.airpods_cycle_min_two), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("airpods-cycle-min-two"))
            }
        }
    }
}

@Composable internal fun ColumnScope.CallPressChoices(s: AirPodsUiState, title: Int, mute: Boolean,
    dispatch: (AirPodsUiAction) -> Unit) {
    GroupLabel(title)
    Pane {
        GesturePropertyChoice(s, CommandProperty.CALL_MANAGEMENT_CONFIG, callAssignment(mute, true),
            R.string.airpods_c3_press_once, dispatch, if (mute) "mute-once" else "end-once")
        FluidListDivider()
        GesturePropertyChoice(s, CommandProperty.CALL_MANAGEMENT_CONFIG, callAssignment(mute, false),
            R.string.airpods_c3_press_twice, dispatch, if (mute) "mute-twice" else "end-twice")
    }
}

@Composable internal fun GesturePropertyChoice(s: AirPodsUiState, property: CommandProperty,
    value: Int, label: Int, dispatch: (AirPodsUiAction) -> Unit, tag: String = "$property-$value") {
    val number = PropertyValue.Number(value)
    val allowed = s.writeAllowed(property, number)
    FluidChoiceRow(stringResource(label), selected = effectiveNumber(s, property) == value,
        enabled = allowed, modifier = Modifier.testTag("airpods-choice-$tag"),
        onSelect = { if (s.writeAllowed(property, number))
            dispatch(AirPodsUiAction.Write(s.domain.generation, property, number)) })
}
