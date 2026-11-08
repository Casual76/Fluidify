// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lelonio.square.ui.airpods



import androidx.compose.foundation.Canvas

import androidx.compose.foundation.layout.*

import androidx.compose.foundation.rememberScrollState

import androidx.compose.foundation.verticalScroll

import androidx.compose.material3.*

import androidx.compose.runtime.*

import androidx.compose.ui.Modifier

import androidx.compose.ui.Alignment

import androidx.compose.ui.geometry.Rect

import androidx.compose.ui.geometry.Offset

import androidx.compose.ui.graphics.Path

import androidx.compose.ui.graphics.drawscope.Stroke

import androidx.compose.ui.res.stringResource

import androidx.compose.ui.unit.dp

import androidx.compose.ui.platform.LocalDensity

import androidx.compose.ui.platform.testTag

import androidx.compose.ui.semantics.selected

import dev.antigravity.fluidengine.ui.fluid.*

import dev.antigravity.fluidengine.ui.theme.FluidListGroup

import dev.antigravity.fluidengine.ui.theme.FluidListRow

import dev.lelonio.square.R

import dev.lelonio.square.airpods.*

import dev.lelonio.square.airpods.protocol.*

import kotlin.math.roundToInt

import androidx.compose.ui.semantics.semantics

import androidx.compose.ui.semantics.contentDescription

import kotlinx.coroutines.delay

import kotlinx.coroutines.launch

import kotlinx.coroutines.Job

import androidx.compose.ui.layout.onGloballyPositioned

import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.AnnotatedString



@Composable

fun AirPodsCapsule(binding: AirPodsUiBinding, modifier: Modifier = Modifier,

    backdrop: GlassBackdropState = currentGlassBackdrop()) {

    val s = binding.state

    var origin by remember { mutableStateOf<Rect?>(null) }

    val title = stringResource(if(s.profileConnected) R.string.airpods_feature_settings_accessibility else R.string.airpods_feature_connect)

    val action = stringResource(R.string.airpods_ui_quick_action)
    val batteryLabel = capsuleBatteryLabel(s)
    val accessibleBattery = stringResource(R.string.airpods_ui_capsule_battery, batteryLabel)

    FluidFloatingCapsule(modifier = modifier.testTag("airpods-home-capsule").onGloballyPositioned { origin=it.boundsInWindow() }, backdrop = backdrop, contentPadding = PaddingValues(0.dp), minHeight = 48.dp) {
        // One child avoids the engine capsule's inter-item gap: two 48dp targets fit in 96dp.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {

        Box(Modifier.weight(1f).widthIn(min = 48.dp).heightIn(min = 48.dp).testTag("airpods-capsule-body")

            .fluidPressable(onClick = { if(s.profileConnected) { val rect=origin; val open=binding.openQuickFrom; if(rect != null && open != null) open(rect) else binding.openQuick() } else if(s.allowed("setup.CONNECT")) binding.dispatch(AirPodsUiAction.Setup(SetupAction.CONNECT)) })

            .semantics { contentDescription = "$title. $accessibleBattery" }, contentAlignment = Alignment.Center) {

            val ink = MaterialTheme.colorScheme.onSurface

            // Original vector drawing: no upstream or network asset.

            BoxWithConstraints(Modifier.fillMaxWidth().height(48.dp)) {
            val style = MaterialTheme.typography.labelSmall
            val measurer = rememberTextMeasurer()
            val measured = measurer.measure(AnnotatedString(batteryLabel), style = style,
                softWrap = false, maxLines = 1, constraints = Constraints())
            val density = LocalDensity.current
            val fits = with(density) {
                measured.size.width <= maxWidth.roundToPx() &&
                    measured.size.height + 20.dp.roundToPx() <= this@BoxWithConstraints.maxHeight.roundToPx()
            }
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {

            Canvas(Modifier.size(20.dp)) {

                drawCircle(ink, 4.dp.toPx(), Offset(size.width*.28f,size.height*.3f), style=Stroke(2.dp.toPx()))

                drawCircle(ink, 4.dp.toPx(), Offset(size.width*.72f,size.height*.3f), style=Stroke(2.dp.toPx()))

                drawLine(ink,Offset(size.width*.3f,size.height*.4f),Offset(size.width*.3f,size.height*.85f),2.dp.toPx())

                drawLine(ink,Offset(size.width*.7f,size.height*.4f),Offset(size.width*.7f,size.height*.85f),2.dp.toPx())

            }

            if (fits) Text(batteryLabel, style = style, maxLines = 1, softWrap = false,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().testTag("airpods-capsule-battery-text"))

            }
            }

        }

        FluidButton(text = "\u2194", onClick = { s.quickTarget?.let { binding.dispatch(AirPodsUiAction.Write(s.domain.generation, CommandProperty.MODE, PropertyValue.Number(it.wire))) } },

            enabled = s.quickTarget != null, modifier = Modifier.width(48.dp).heightIn(min = 48.dp).testTag("airpods-capsule-quick").semantics { contentDescription = action },

            style = FluidButtonStyle.Plain)

        }
    }

}



@Composable

fun AirPodsQuickOverlay(state: AirPodsUiState, visible: Boolean, onDismiss: () -> Unit,

    dispatch: (AirPodsUiAction) -> Unit, onOpenSettings: () -> Unit, origin: () -> Rect? = { null }) {

    FluidGlassModalPortal(visible = visible, onDismissRequest = onDismiss, origin = origin,

        paneTitle = stringResource(R.string.airpods_ui_quick)) {

        FluidSheetScaffold(title = stringResource(R.string.airpods_ui_quick), maxWidth = 560.dp,

            insetBottom = true,

            actions = {

                FluidButton(stringResource(R.string.airpods_ui_full), { onDismiss(); onOpenSettings() },

                    fillWidth = true, modifier = Modifier.heightIn(min = 48.dp).testTag("airpods-all-settings"))

            }) {

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

                BatterySummary(state)
                if(state.domain.stage == AacpStage.ERROR) Text(stringResource(R.string.airpods_ui_aacp_error))

                QuickProperty(state, CommandProperty.MODE, dispatch)

                PreferredModePicker(state, dispatch)

                QuickProperty(state, CommandProperty.CONVERSATION, dispatch)

                HostAutomationSummary(state)

                NumberEditor(stringResource(R.string.airpods_feature_setting_host_volume), state.localVolumePercent,

                    0..100, state.allowed("host.volume"), reason = state.availability["host.volume"]?.reason) { dispatch(AirPodsUiAction.LocalVolume(it)) }

            }

        }

    }

}



/** Page BODY: navigation, title and real modal backdrop are owned by SquareApp. */

@Composable

fun AirPodsSettingsContent(state: AirPodsUiState, dispatch: (AirPodsUiAction) -> Unit, modifier: Modifier = Modifier) {

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

        Text(stringResource(R.string.airpods_feature_exclusive_summary))

        if(!state.productGatePassed) Text(stringResource(R.string.airpods_ui_gate), color = MaterialTheme.colorScheme.error)

        Text(stringResource(R.string.airpods_ui_setup), style = MaterialTheme.typography.titleLarge)

        FluidListGroup {

            Text(stringResource(R.string.airpods_ui_choose), modifier = Modifier.padding(16.dp))

            state.candidates.forEach { candidate ->

                FluidListRow(candidate.displayName, candidate.identityHint, selected = candidate.id == state.selectedCandidateId,

                    onClick = { dispatch(AirPodsUiAction.SelectPair(candidate.id)) })

            }

            SetupButton(state, SetupAction.BLUETOOTH_PERMISSION, R.string.airpods_feature_bluetooth_access, dispatch)

            SetupButton(state, SetupAction.VERIFY_SELECTED, R.string.airpods_ui_verify, dispatch)

            SetupButton(state, SetupAction.ENABLE, R.string.airpods_ui_enable, dispatch)

            SetupButton(state, SetupAction.DISABLE, R.string.airpods_ui_disable, dispatch)

            SetupButton(state, SetupAction.STOP, R.string.airpods_ui_stop, dispatch)

            SetupButton(state, SetupAction.RESUME, R.string.airpods_ui_resume, dispatch)

            if (state.pendingCleanupCount > 0) {
                Text(stringResource(R.string.airpods_cleanup_pending, state.pendingCleanupCount), Modifier.padding(16.dp))
                SetupButton(state, SetupAction.RETRY_CLEANUP, R.string.airpods_cleanup_retry, dispatch)
            }

            SetupButton(state, SetupAction.NOTIFICATION_PERMISSION, R.string.airpods_ui_notifications, dispatch)

            SetupButton(state, SetupAction.CALL_PERMISSION, R.string.airpods_call_permission, dispatch)
            Text(stringResource(R.string.airpods_call_explanation))

            SetupButton(state, SetupAction.MEDIA_ACCESS, R.string.airpods_feature_media_access, dispatch)

            SetupButton(state, SetupAction.COMPANION_ASSOCIATION, R.string.airpods_feature_companion_setup, dispatch)

            SetupButton(state, SetupAction.BLUETOOTH_SETTINGS, R.string.airpods_feature_open_bluetooth, dispatch)

        }

        Text(stringResource(R.string.airpods_ui_background_note))

        LocalToggle.entries.forEach { toggle ->

            val checked = state.pair.localToggleValue(toggle)

            FluidToggleRow(stringResource(toggleLabels.getValue(toggle)), checked,

                { dispatch(AirPodsUiAction.Toggle(toggle,it)) }, enabled = state.allowed("local.$toggle"),

                subtitle = if(toggle == LocalToggle.OTHER_APPS) listOfNotNull(

                    stringResource(R.string.airpods_ui_other_apps_consent), state.availability["local.$toggle"]?.reason).joinToString("\n")

                else state.availability["local.$toggle"]?.reason ?: stringResource(R.string.airpods_ui_local_note))

        }

        NumberEditor(stringResource(R.string.airpods_feature_setting_host_ca_volume), state.pair.host.conversationVolumePercent, 10..85,

            state.allowed("host.ca_volume"), reason = state.availability["host.ca_volume"]?.reason) { dispatch(AirPodsUiAction.ConversationVolume(it)) }

        Text(stringResource(R.string.airpods_ui_quick_preference), style = MaterialTheme.typography.titleMedium)

        state.modes.filter { it != ListeningMode.ANC }.forEach { mode ->

            FluidListRow(modeLabel(mode.wire), stringResource(R.string.airpods_ui_local_note), selected = state.preferredMode == mode,

                onClick = if(state.allowed("local.preferredMode")) ({ dispatch(AirPodsUiAction.PreferredMode(mode)) }) else null)

        }

        AvailabilityReason(state, "local.preferredMode")

        // Registry owns membership/grouping; no handwritten list can silently omit a new control.

        AirPodsProperties.controls.filterNot { it.internalDependency }.groupBy { it.group }.forEach { (group, specs) ->

            Text(groupLabel(group), style = MaterialTheme.typography.titleLarge)

            FluidListGroup { specs.forEach { PropertyEditor(state, it, dispatch) } }

        }

        Text(stringResource(R.string.airpods_feature_device_group), style = MaterialTheme.typography.titleLarge)

        BatteryRows(state)

        state.identity?.let { id ->

            // Explicit information page may display identity; never include it in diagnostics.

            Text(listOfNotNull(id.manufacturer,id.name,id.modelNumber,id.firmware,id.version2,id.version3,id.hardwareRevision,id.updaterIdentifier,id.serialNumber,id.leftSerialNumber,id.rightSerialNumber).joinToString(" \u00b7 "))

        }

        SetupButton(state, SetupAction.INFORMATION, R.string.airpods_ui_information, dispatch)

        RenameEditor(state, dispatch)

        EqEditor(state, dispatch)

        run { val value = state.localVolumePercent; NumberEditor(stringResource(R.string.airpods_feature_setting_host_volume), value, 0..100, state.allowed("host.volume"), reason = state.availability["host.volume"]?.reason) { dispatch(AirPodsUiAction.LocalVolume(it)) } }

        Text(stringResource(R.string.airpods_feature_controls_group), style = MaterialTheme.typography.titleLarge)

        GestureSide.entries.forEach { side -> GesturePicker(state, side, dispatch) }

        Text(stringResource(R.string.airpods_ui_raw_plot))

        TrackingPlot(state.headHistory)

        FluidButton(stringResource(R.string.airpods_ui_tracking_start), { dispatch(AirPodsUiAction.Tracking(true)) }, enabled = state.allowed("tracking.start"), modifier = Modifier.testTag("airpods-tracking-start"), grip = FluidPressGrip.YieldsToScroll)
        AvailabilityReason(state, "tracking.start")

        FluidButton(stringResource(R.string.airpods_ui_tracking_stop), { dispatch(AirPodsUiAction.Tracking(false)) }, enabled = state.allowed("tracking.stop"), modifier = Modifier.testTag("airpods-tracking-stop"), grip = FluidPressGrip.YieldsToScroll)
        AvailabilityReason(state, "tracking.stop")

        Text(stringResource(R.string.airpods_audio_explanation))
        SetupButton(state, SetupAction.CONNECT, R.string.airpods_audio_connect, dispatch)

        SetupButton(state, SetupAction.DISCONNECT, R.string.airpods_audio_disconnect, dispatch)

        Text(stringResource(R.string.airpods_ui_tile))

        SetupButton(state, SetupAction.TILE_INSTRUCTIONS, R.string.airpods_feature_setting_extra_qs, dispatch)

        Text(stringResource(R.string.airpods_ui_hearing_excluded))

        Text(stringResource(R.string.airpods_feature_diagnostics_group), style = MaterialTheme.typography.titleLarge)

        state.diagnostics.forEach { Text(it) }

        SetupButton(state, SetupAction.START_DIAGNOSTICS, R.string.airpods_ui_collect, dispatch)

        SetupButton(state, SetupAction.STOP_DIAGNOSTICS, R.string.airpods_ui_collect_stop, dispatch)

        SetupButton(state, SetupAction.EXPORT_DIAGNOSTICS, R.string.airpods_ui_export, dispatch)

        SetupButton(state, SetupAction.DELETE_DIAGNOSTICS, R.string.airpods_ui_delete, dispatch)

        var forget by remember { mutableStateOf(false) }

        FluidButton(stringResource(R.string.airpods_feature_forget), { forget = true }, enabled = state.allowed("setup.FORGET"), style = FluidButtonStyle.Destructive)

        if (forget) FluidAlert( onDismissRequest = { forget = false }, title = stringResource(R.string.airpods_feature_forget),

            message = stringResource(R.string.airpods_ui_forget_confirm), actions = listOf(

                FluidAlertAction(stringResource(R.string.airpods_ui_cancel), { forget = false }),

                FluidAlertAction(stringResource(R.string.airpods_feature_forget), { forget = false; dispatch(AirPodsUiAction.Setup(SetupAction.FORGET)) })))

    }

}



@Composable internal fun SetupButton(s: AirPodsUiState, action: SetupAction, label: Int, dispatch: (AirPodsUiAction) -> Unit) {

    FluidListRow(stringResource(label), if (s.allowed("setup.$action")) ""
        else s.availability["setup.$action"]?.reason ?: stringResource(R.string.airpods_ui_unavailable),
        modifier = Modifier.testTag("airpods-setup-$action"),

        onClick = if(s.allowed("setup.$action")) ({ dispatch(AirPodsUiAction.Setup(action)) }) else null)

}

private val batteryComponents = listOf(4 to R.string.airpods_feature_left, 2 to R.string.airpods_feature_right, 8 to R.string.airpods_feature_case)



@Composable private fun batteryValue(s: AirPodsUiState, component: Int): String =

    s.freshBattery(component)?.percent?.let { stringResource(R.string.airpods_feature_percent, it) }

        ?: stringResource(R.string.airpods_ui_missing)



@Composable private fun capsuleBatteryLabel(s: AirPodsUiState): String {

    val reading = s.capsuleBatteryReading() ?: return stringResource(R.string.airpods_ui_missing)
    val percent = stringResource(R.string.airpods_feature_percent, reading.percent)
    return when(reading.component) {
        4 -> stringResource(R.string.airpods_ui_left_short) + " " + percent
        2 -> stringResource(R.string.airpods_ui_right_short) + " " + percent
        8 -> stringResource(R.string.airpods_ui_case_short) + " " + percent
        else -> percent
    }

}



@Composable private fun BatterySummary(s: AirPodsUiState) {

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {

        batteryComponents.forEach { (component, title) ->

            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {

                Text(stringResource(title), style = MaterialTheme.typography.labelMedium)

                Text(batteryValue(s, component), style = MaterialTheme.typography.titleMedium)

                if(s.freshBattery(component)?.status == 1)

                    Text(stringResource(R.string.airpods_feature_charging), style = MaterialTheme.typography.labelSmall)

            }

        }

    }

}



@Composable private fun ageLabel(nowMs: Long, atMs: Long): String {

    val age = (nowMs - atMs).takeIf { it >= 0 } ?: return stringResource(R.string.airpods_ui_age_unknown)

    return when {

        age < 60_000 -> stringResource(R.string.airpods_ui_age_now)

        age < 3_600_000 -> stringResource(R.string.airpods_ui_age_minutes, age / 60_000)

        age < 86_400_000 -> stringResource(R.string.airpods_ui_age_hours, age / 3_600_000)

        else -> stringResource(R.string.airpods_ui_age_days, age / 86_400_000)

    }

}



@Composable private fun BatteryRows(s: AirPodsUiState) {

    BatterySummary(s)

    // History is exclusive to details, and never duplicates a live component.

    batteryComponents.forEach { (component, title) ->

        if(s.freshBattery(component) == null) s.domain.batteries[component]?.let { rx ->

            val percent = rx.value.percent?.let { stringResource(R.string.airpods_feature_percent, it) }

                ?: stringResource(R.string.airpods_ui_missing)

            Text(stringResource(R.string.airpods_ui_battery_history, stringResource(title), percent,

                ageLabel(s.nowMs, rx.atMs)), style = MaterialTheme.typography.bodySmall)

        }

    }

}



@Composable private fun QuickProperty(s: AirPodsUiState, property: CommandProperty, dispatch: (AirPodsUiAction) -> Unit) {

    val spec = AirPodsProperties.spec(property) ?: return

    val view = s.domain.propertyView(property) ?: return

    val value = if(property == CommandProperty.MODE) s.currentMode?.wire else

        (view.observed?.value as? PropertyValue.Number)?.value

    Text(stringResource(featureTitles.getValue(spec.catalogId)), style = MaterialTheme.typography.titleMedium)

    CommandStatusSummary(s, property)
    AvailabilityReason(s, "property.$property")

    BoxWithConstraints {

        val columns = if(LocalDensity.current.fontScale >= 1.8f || maxWidth < 300.dp) 1 else 2

        val options = spec.options.filter { property != CommandProperty.MODE || s.modes.any { mode -> mode.wire == it.key } }.toList()

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {

            options.chunked(columns).forEach { row ->

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {

                    row.forEach { (v, label) ->

                        FluidButton(if(property == CommandProperty.MODE) modeLabel(v) else stringResource(optionLabels.getValue(label)),

                            { dispatch(AirPodsUiAction.Write(s.domain.generation, property, PropertyValue.Number(v))) },

                            enabled = s.writeAllowed(property, PropertyValue.Number(v)),

                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("airpods-quick-$property-$v").semantics { selected = value == v },

                            style = if(value == v) FluidButtonStyle.Tinted else FluidButtonStyle.Plain,

                            grip = FluidPressGrip.YieldsToScroll)

                    }

                    if(row.size < columns) Spacer(Modifier.weight(1f))

                }

            }

        }

    }

}



@Composable private fun PreferredModePicker(s: AirPodsUiState, dispatch: (AirPodsUiAction) -> Unit) {

    val options = s.modes.filter { it != ListeningMode.ANC }.map { mode ->

        FluidContextAction(modeLabel(mode.wire), selected = s.preferredMode == mode) { dispatch(AirPodsUiAction.PreferredMode(mode)) }

    }

    val menu = rememberFluidContextMenu(actions = { options })

    FluidButton(stringResource(R.string.airpods_ui_quick_preference) + " \u00b7 " +

        (s.preferredMode?.let { modeLabel(it.wire) } ?: stringResource(R.string.airpods_ui_missing)),

        { menu.open() }, enabled = s.allowed("local.preferredMode") && options.isNotEmpty(),

        style = FluidButtonStyle.Plain, grip = FluidPressGrip.YieldsToScroll,

        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("airpods-preferred-mode").fluidContextMenuAnchor(menu))
    AvailabilityReason(s, "local.preferredMode")

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

    FluidButton(title + " \u00b7 " + (saved?.let { stringResource(gestureLabels.getValue(it)) } ?: stringResource(R.string.airpods_ui_choose_action)),

        { menu.open() }, enabled = s.allowed("gesture.$side"), style = FluidButtonStyle.Tinted,

        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("airpods-gesture-$side").fluidContextMenuAnchor(menu),

        grip = FluidPressGrip.YieldsToScroll)

    s.availability["gesture.$side"]?.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

}



@Composable internal fun PropertyEditor(s: AirPodsUiState, spec: PropertySpec, dispatch: (AirPodsUiAction) -> Unit) {

    val view = s.domain.propertyView(spec.property) ?: return

    val value = if(spec.property == CommandProperty.MODE) s.currentMode?.wire else (view.observed?.value as? PropertyValue.Number)?.value

    val status = when(view.status) { PropertyStatus.UNKNOWN -> R.string.airpods_ui_unknown; PropertyStatus.CANDIDATE -> R.string.airpods_ui_candidate; PropertyStatus.OBSERVED -> R.string.airpods_ui_observed; PropertyStatus.PENDING -> R.string.airpods_ui_pending; PropertyStatus.NOT_CONFIRMED -> R.string.airpods_ui_not_confirmed; PropertyStatus.DEPENDENCY_EXCLUDED -> R.string.airpods_ui_excluded; PropertyStatus.UNAVAILABLE -> R.string.airpods_ui_unavailable }

    Text(stringResource(featureTitles.getValue(spec.catalogId)), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(12.dp))

    val displayStatus = if(spec.property == CommandProperty.MODE && s.currentMode != null &&
        view.status == PropertyStatus.CANDIDATE) R.string.airpods_ui_observed else status
    Text(stringResource(displayStatus), modifier = Modifier.padding(horizontal = 12.dp))
    // Owner-localized current cause, separate from candidate/observed/pending status.
    if (spec.options.isNotEmpty() || spec.encoding == ControlEncoding.MODE_MASK)
        AvailabilityReason(s, "property.${spec.property}")



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

    if(spec.encoding == ControlEncoding.MODE_MASK) {

        var draftMask by remember(value,s.domain.generation) { mutableStateOf<Int?>(null) }

        s.modes.forEach { mode ->

            val bit = 1 shl (mode.wire-1)

            val mask = draftMask ?: value

            FluidToggleRow(modeLabel(mode.wire), mask != null && mask and bit != 0,

                { draftMask = (mask ?: 0) xor bit },

                enabled = s.controlsReady && spec.property !in s.domain.pending && s.identity?.let(spec::candidate) == true)

        }

        FluidButton(stringResource(R.string.airpods_ui_apply), { draftMask?.let(::send) },

            enabled = draftMask?.let { s.writeAllowed(spec.property,PropertyValue.Number(it)) } == true)

    } else if(spec.options.isNotEmpty()) {

        spec.options.forEach { (v,label) ->

            if(spec.property != CommandProperty.MODE || s.modes.any { it.wire == v })

                FluidListRow(if(spec.property == CommandProperty.MODE) modeLabel(v) else stringResource(optionLabels.getValue(label)), "",

                    selected = value == v, onClick = if(s.writeAllowed(spec.property,PropertyValue.Number(v))) ({send(v)}) else null)

        }

    } else {

        // An absent RX has no selected default. A draft is created only by explicit interaction.

        NumberEditor(stringResource(featureTitles.getValue(spec.catalogId)),value,spec.range,

            s.controlsReady && spec.property !in s.domain.pending && s.identity?.let(spec::candidate) == true,

            reason = s.availability["property.${spec.property}"]?.reason,
            reasonTag = "airpods-reason-property.${spec.property}", onApply = ::send)

    }

}

@Composable private fun AvailabilityReason(s: AirPodsUiState, key: String) {
    s.availability[key]?.takeIf { !it.enabled }?.reason?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("airpods-reason-$key"))
    }
}

@Composable private fun NumberEditor(label: String, value: Int?, range: IntRange, enabled: Boolean,
    reason: String? = null, reasonTag: String = "airpods-number-reason", onApply: (Int) -> Unit) {

    var draft by remember(value) { mutableStateOf<Int?>(null) }

    Text(label + " \u00b7 " + (value?.toString() ?: stringResource(R.string.airpods_ui_missing)))

    if (!enabled) reason?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(reasonTag))
    }
    if(!enabled && value == null) return

    if(value == null) {

        var entry by remember { mutableStateOf("") }

        OutlinedTextField(entry, { entry=it; draft=it.toIntOrNull()?.takeIf { n -> n in range } },

            label={Text(label)}, enabled=enabled, singleLine=true, modifier=Modifier.fillMaxWidth())

    } else FluidSlider(value = (draft ?: value!!).toFloat(), onValueChange = { draft = it.roundToInt() },

        valueRange = range.first.toFloat()..range.last.toFloat(), enabled = enabled, contentDescription = label)

    FluidButton(stringResource(R.string.airpods_ui_apply), { draft?.let(onApply); draft = null }, enabled = enabled && draft != null,

        modifier = Modifier.heightIn(min = 48.dp), grip = FluidPressGrip.YieldsToScroll)

}

@Composable private fun RenameEditor(s: AirPodsUiState, dispatch: (AirPodsUiAction) -> Unit) {

    CommandStatusSummary(s,CommandProperty.NAME)

    var name by remember(s.domain.generation) { mutableStateOf("") }

    OutlinedTextField(name,{name=it}, label = {Text(stringResource(R.string.airpods_ui_name))}, modifier = Modifier.fillMaxWidth(), singleLine = true)

    val value = PropertyValue.Name(name)

    FluidButton(stringResource(R.string.airpods_feature_setting_device_rename), {dispatch(AirPodsUiAction.Write(s.domain.generation,CommandProperty.NAME,value))},enabled = s.writeAllowed(CommandProperty.NAME,value))

}

@Composable private fun EqEditor(s: AirPodsUiState, dispatch: (AirPodsUiAction) -> Unit) {

    val rx = s.domain.propertyObserved(CommandProperty.EQ)?.value as? PropertyValue.Eq

    var enabled by remember(rx,s.domain.generation) { mutableStateOf(rx?.enabled) }

    var low by remember(rx,s.domain.generation) { mutableStateOf(rx?.low) }

    var mid by remember(rx,s.domain.generation) { mutableStateOf(rx?.mid) }

    var high by remember(rx,s.domain.generation) { mutableStateOf(rx?.high) }

    Text(stringResource(R.string.airpods_feature_setting_device_eq))

    CommandStatusSummary(s,CommandProperty.EQ)

    listOf(true,false).forEach { v -> FluidListRow(stringResource(if(v) R.string.airpods_ui_on else R.string.airpods_ui_off), "",

        selected = enabled == v, onClick = if(s.controlsReady && CommandProperty.EQ !in s.domain.pending) ({enabled=v}) else null) }

    val editable = s.controlsReady && CommandProperty.EQ !in s.domain.pending

    NumberEditor(stringResource(R.string.airpods_ui_low),low,0..100,editable) {low=it}

    NumberEditor(stringResource(R.string.airpods_ui_mid),mid,0..100,editable) {mid=it}

    NumberEditor(stringResource(R.string.airpods_ui_high),high,0..100,editable) {high=it}

    val eq = if(enabled != null && low != null && mid != null && high != null) PropertyValue.Eq(enabled!!,low!!,mid!!,high!!) else null

    FluidButton(stringResource(R.string.airpods_ui_apply),{eq?.let {dispatch(AirPodsUiAction.Write(s.domain.generation,CommandProperty.EQ,it))}},

        enabled=eq != null && s.writeAllowed(CommandProperty.EQ,eq) && eq != rx)

}

@Composable private fun CommandStatusSummary(s: AirPodsUiState, property: CommandProperty) {

    Text(stringResource(when {

        property in s.domain.pending -> R.string.airpods_ui_pending

        s.domain.outcomes[property] == CommandOutcome.NOT_CONFIRMED -> R.string.airpods_ui_not_confirmed

        property == CommandProperty.MODE && s.currentMode != null -> R.string.airpods_ui_observed

        s.domain.propertyObserved(property) != null -> R.string.airpods_ui_observed

        else -> R.string.airpods_ui_candidate

    }))



}

@Composable private fun TrackingPlot(samples: List<HeadSample>) {

    val color = MaterialTheme.colorScheme.primary

    Canvas(Modifier.fillMaxWidth().height(120.dp)) {

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

@Composable private fun groupLabel(group: String) = stringResource(when(group) {

    "microphone","calls" -> R.string.airpods_feature_calls_group

    "accessibility" -> R.string.airpods_feature_accessibility_group

    "gestures" -> R.string.airpods_feature_controls_group

    "automation" -> R.string.airpods_feature_automations_group

    "hearing","charging" -> R.string.airpods_feature_protection_group

    "connection" -> R.string.airpods_feature_service_group

    else -> R.string.airpods_feature_audio_group

})


/** Saved choices and host prerequisites only; C6 cannot certify another app's route/effect. */
@Composable internal fun HostAutomationSummary(state: AirPodsUiState) {
    Column(Modifier.fillMaxWidth().testTag("airpods-host-summary"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.airpods_ui_host_summary), style = MaterialTheme.typography.titleMedium)
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
            Text(stringResource(label) + ": " + description,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("airpods-host-summary-$toggle"))
        }
        Text(stringResource(R.string.airpods_ui_host_observational), style = MaterialTheme.typography.bodySmall)
    }
}
