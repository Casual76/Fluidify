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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.*
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.lelonio.square.R
import dev.lelonio.square.airpods.*
import dev.lelonio.square.airpods.protocol.*
import kotlinx.coroutines.flow.collect

/** One long-press side on the main page: what is saved for it, and the page where it is chosen. */
@Composable internal fun LongPressRow(s: AirPodsUiState, side: GestureSide, page: AirPodsSettingsPage,
    onOpen: (AirPodsSettingsPage) -> Unit) {
    val saved = s.gestureAssignments[side]?.takeIf { it in gestureOptions(side) }
    OpenRow(if (side == GestureSide.LEFT) R.string.airpods_ui_left else R.string.airpods_ui_right, page, onOpen,
        value = saved?.let { stringResource(gestureLabels.getValue(it)) } ?: stringResource(R.string.airpods_ui_choose_action),
        tag = "airpods-gesture-$side")
}

/**
 * Upstream's `LongPress` page: what holding this stem does, and which modes a hold cycles through.
 *
 * The side choice comes exclusively from local preferences; the shared cycle uses effective 0x1a.
 */
@Composable internal fun ColumnScope.LongPressPage(s: AirPodsUiState, side: GestureSide, dispatch: (AirPodsUiAction) -> Unit) {
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
    if (saved == GestureAction.NOISE_CONTROL) {
        LongPressModeSwitches(s, dispatch)
    }
}

@Composable internal fun ColumnScope.CallsPage(state: AirPodsUiState, dispatch: (AirPodsUiAction) -> Unit) {
    val callSpec = AirPodsProperties.spec(CommandProperty.CALL_MANAGEMENT_CONFIG)!!
    if (state.applies(callSpec)) {
        CallPressChoices(state, R.string.airpods_c3_mute, mute = true, dispatch = dispatch)
        CallPressChoices(state, R.string.airpods_c3_end_call, mute = false, dispatch = dispatch)
    }
    val mic = AirPodsProperties.spec(CommandProperty.MIC_MODE)!!
    if (state.applies(mic)) {
        GroupLabel(featureTitles.getValue(mic.catalogId))
        AvailabilityReason(state, "property.${mic.property}")
        Pane {
            listOf(0 to R.string.airpods_ui_automatic, 1 to R.string.airpods_c3_always_right,
                2 to R.string.airpods_c3_always_left).forEachIndexed { index, (value, label) ->
                if (index > 0) FluidListDivider()
                GesturePropertyChoice(state, mic.property, value, label, dispatch)
            }
        }
    }
    Pane {
        PaneBlock {
            Text(stringResource(R.string.wf_i03_call_gestures), style = MaterialTheme.typography.bodyMedium)
            StatusLine(stringResource(state.callAccess.summary()),
                if (state.callAccess.granted) StatusKind.Confirmed else StatusKind.Problem,
                modifier = Modifier.testTag("airpods-call-access-state"))
        }
        if (!state.callAccess.granted) {
            FluidListDivider()
            SetupButton(state, SetupAction.CALL_PERMISSION, R.string.wf_i03_allow_call_access, dispatch)
        }
    }
}

@Composable internal fun ColumnScope.HeadGesturesPage(state: AirPodsUiState, dispatch: (AirPodsUiAction) -> Unit) {
    Pane {
        LocalToggleRows(state, dispatch, gestureToggles)
        FluidListDivider()
        GesturePicker(state, GestureSide.NOD, dispatch)
        FluidListDivider()
        GesturePicker(state, GestureSide.SHAKE, dispatch)
    }
}

/** Mounted only by DiagnosticsPage. No changes to the source or detector stream. */
@Composable internal fun ColumnScope.HeadTrackingDiagnostics(state: AirPodsUiState, dispatch: (AirPodsUiAction) -> Unit) {
    Pane(Modifier.testTag("airpods-head-diagnostics")) {
        PaneBlock {
            Text(stringResource(R.string.airpods_ui_raw_plot), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TrackingSamplePlot(state)
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
    )
    if (!stem) AvailabilityReason(s, "gesture.$side",
        Modifier.padding(start = PaneInset, end = PaneInset, bottom = 12.dp))
}

/** Local, bounded visualization history. The ordered detector stream is never sampled here. */
@Composable private fun TrackingSamplePlot(state: AirPodsUiState) {
    val source = state.headSamples
    val history = remember(source, state.domain.generation) { mutableStateListOf<HeadSample>() }
    LaunchedEffect(source, state.domain.generation) {
        source?.collect { sample ->
            if (sample == null) history.clear()
            else if (sample.generation == state.domain.generation) {
                history.add(sample.value)
                while (history.size > 120) history.removeAt(0)
            }
        }
    }
    TrackingPlot(if (source == null) state.headHistory else history)
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
