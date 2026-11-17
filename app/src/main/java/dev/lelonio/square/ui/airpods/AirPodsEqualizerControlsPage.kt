// SPDX-License-Identifier: GPL-3.0-or-later
// Structure derived from LibrePods, Copyright (C) 2025 LibrePods contributors,
// EqualizerScreen.kt and AccessibilitySettingsScreen.kt, pinned 89356c08cea3db0b6b54792d9329d09a87cc85fa.
// Fluidify uses Engine controls, effective state and a final flush in addition to upstream debounce.
package dev.lelonio.square.ui.airpods

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidChoiceRow
import dev.antigravity.fluidengine.ui.fluid.FluidToggleRow
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.lelonio.square.R
import dev.lelonio.square.airpods.CommandProperty
import dev.lelonio.square.airpods.protocol.AirPodsProperties
import dev.lelonio.square.airpods.protocol.ControlEncoding
import dev.lelonio.square.airpods.protocol.PropertySpec
import dev.lelonio.square.airpods.protocol.PropertyValue
import dev.lelonio.square.airpods.protocol.propertyEffective

/** Every packet uses the effective quadruple, including untouched fields.
 * Defaults belong to the domain; this page never manufactures missing values. */
internal fun AirPodsUiState.equalizerWrite(
    change: (PropertyValue.Eq) -> PropertyValue.Eq,
): AirPodsUiAction.Write? {
    val current = domain.propertyEffective(CommandProperty.EQ)?.value as? PropertyValue.Eq ?: return null
    val next = change(current)
    return if (next != current && writeAllowed(CommandProperty.EQ, next))
        AirPodsUiAction.Write(domain.generation, CommandProperty.EQ, next) else null
}

@Composable internal fun EqEditor(s: AirPodsUiState, dispatch: (AirPodsUiAction) -> Unit) {
    val eq = s.domain.propertyEffective(CommandProperty.EQ)?.value as? PropertyValue.Eq ?: return
    // Dispose pending debounce on session replacement, even with identical values.
    key(s.domain.generation) {
        val current by rememberUpdatedState(s)
        val send by rememberUpdatedState(dispatch)
        val editable = s.writeAllowed(CommandProperty.EQ, eq)
        val write: ((PropertyValue.Eq) -> PropertyValue.Eq) -> Unit = { change ->
            current.equalizerWrite(change)?.let(send)
        }
        AirPodsReadableToggle(
            stringResource(R.string.airpods_feature_setting_device_eq), eq.enabled,
            { enabled -> write { it.copy(enabled = enabled) } },
            modifier = Modifier.testTag("airpods-switch-EQ"), enabled = editable,
        )
        FluidListDivider()
        AirPodsLiveSlider(eq.low, { value -> write { it.copy(low = value) } },
            Modifier.testTag("airpods-eq-low"), debounceMs = 100L, enabled = editable,
            title = stringResource(R.string.airpods_ui_low))
        AirPodsLiveSlider(eq.mid, { value -> write { it.copy(mid = value) } },
            Modifier.testTag("airpods-eq-mid"), debounceMs = 100L, enabled = editable,
            title = stringResource(R.string.airpods_ui_mid))
        AirPodsLiveSlider(eq.high, { value -> write { it.copy(high = value) } },
            Modifier.testTag("airpods-eq-high"), debounceMs = 100L, enabled = editable,
            title = stringResource(R.string.airpods_ui_high))
        PaneBlock {
            FluidButton(stringResource(R.string.airpods_cr_reset),
                { write { it.copy(low = 50, mid = 50, high = 50) } },
                enabled = editable, style = FluidButtonStyle.Tinted,
                modifier = Modifier.testTag("airpods-eq-reset"))
        }
    }
}

@Composable internal fun ColumnScope.EqualizerPage(state: AirPodsUiState, dispatch: (AirPodsUiAction) -> Unit) {
    Pane { EqEditor(state, dispatch) }
}

@Composable internal fun ColumnScope.ControlsPage(state: AirPodsUiState, dispatch: (AirPodsUiAction) -> Unit) {
    key(state.domain.generation) {
        // AccessibilitySettingsScreen order, excluding ATT-only settings.
        val order = listOf(CommandProperty.DOUBLE_CLICK_INTERVAL, CommandProperty.CLICK_HOLD_INTERVAL,
            CommandProperty.ONE_BUD_ANC_MODE, CommandProperty.CHIME_VOLUME,
            CommandProperty.VOLUME_SWIPE_MODE, CommandProperty.VOLUME_SWIPE_INTERVAL)
        order.mapNotNull(AirPodsProperties::spec).filter(state::applies).forEach { spec ->
            if (spec.options.isNotEmpty() && spec.encoding != ControlEncoding.BOOLEAN_1_2)
                GroupLabel(featureTitles.getValue(spec.catalogId))
            Pane { AccessibilityControl(state, spec, dispatch) }
        }
    }
}

@Composable private fun AccessibilityControl(
    state: AirPodsUiState, spec: PropertySpec, dispatch: (AirPodsUiAction) -> Unit,
) {
    val current by rememberUpdatedState(state)
    val send by rememberUpdatedState(dispatch)
    val value = effectiveNumber(state, spec.property) ?: return
    val title = stringResource(featureTitles.getValue(spec.catalogId))
    val write: (Int) -> Unit = { number ->
        val next = PropertyValue.Number(number)
        if (current.writeAllowed(spec.property, next))
            send(AirPodsUiAction.Write(current.domain.generation, spec.property, next))
    }
    when {
        spec.encoding == ControlEncoding.BOOLEAN_1_2 -> {
            val other = PropertyValue.Number(if (value == 1) 2 else 1)
            FluidToggleRow(title, value == 1, { write(if (it) 1 else 2) },
                modifier = Modifier.testTag("airpods-switch-${spec.property}"),
                enabled = state.writeAllowed(spec.property, other))
        }
        spec.property == CommandProperty.CHIME_VOLUME -> {
            AvailabilityReason(state, "property.${spec.property}")
            AirPodsLiveSlider(value, write, Modifier.testTag("airpods-chime-volume"),
                range = spec.range, debounceMs = 100L,
                enabled = state.writeAllowed(spec.property, PropertyValue.Number(value)), title = title)
        }
        else -> spec.options.entries.forEachIndexed { index, (number, label) ->
            if (index > 0) FluidListDivider()
            val selected = value == number
            val editable = state.writeAllowed(spec.property, PropertyValue.Number(number))
            FluidChoiceRow(stringResource(optionLabels.getValue(label)), selected,
                onSelect = { write(number) }, enabled = editable,
                modifier = Modifier.testTag("airpods-choice-${spec.property}-$number"))
        }
    }
}
