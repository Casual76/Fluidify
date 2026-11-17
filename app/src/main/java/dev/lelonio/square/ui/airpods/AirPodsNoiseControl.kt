// SPDX-License-Identifier: GPL-3.0-or-later
//
// The listening-mode selector: a pill of marks with a thumb that can be dragged along it.
//
// Ported from LibrePods, Copyright (C) 2025 LibrePods contributors, GPL-3.0-or-later, pinned at
// 89356c08cea3db0b6b54792d9329d09a87cc85fa:
//   android/app/src/main/java/me/kavishdevar/librepods/presentation/components/NoiseControlSettings.kt
//     (the `DesignSystem.Apple` branch: the one with the draggable thumb)
//   android/app/src/main/java/me/kavishdevar/librepods/presentation/components/NoiseControlButton.kt
//
// Kept from upstream: the 60 dp pill, one equal slot per mode in the order Off, Transparency,
// Adaptive, Noise Cancellation, the 40 dp marks, the thumb inset by 3 dp, the 1 dp dividers inset
// by 10 dp that disappear beside the thumb, the labels under the pill, the horizontal `draggable`
// clamped to the pill with the release rounding to the nearest slot, and a low-bounce spring.
//
// Changed here: shared effective state owns selection (local accepted intent, then RX), with no
// waiting ring. The draggable stays on the containing pill, above the clickable marks in the
// pointer ancestry. During a drag the thumb follows the finger directly; after release it follows
// the effective state. A later contradictory RX animates it to the newly effective slot.
// Model filtering, theme colours, FluidMotion and the capsule shape remain Fluidify's adapters.
package dev.lelonio.square.ui.airpods

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.fluidRowPressable
import dev.lelonio.square.R
import dev.lelonio.square.airpods.ListeningMode
import kotlin.math.roundToInt

/** Upstream's reading order of the modes, whatever order the wire values are in. */
internal val noiseControlOrder: List<ListeningMode> =
    listOf(ListeningMode.OFF, ListeningMode.TRANSPARENCY, ListeningMode.ADAPTIVE, ListeningMode.ANC)

/** The slot a release at [offsetPx] lands on: the nearest one, inside the pill. */
internal fun noiseControlSlot(offsetPx: Float, slotWidthPx: Float, count: Int): Int =
    if (slotWidthPx <= 0f || count <= 0) 0 else (offsetPx / slotWidthPx).roundToInt().coerceIn(0, count - 1)

/**
 * @param modes the modes this device has, in any order; drawn in [noiseControlOrder].
 * @param effective the shared display value, including accepted local intent; null when unavailable.
 * @param onRequest returns the request for a mode, or null when that mode cannot be asked for
 *   now (or is already effective, which there is nothing to ask for). A slot without a request
 *   has no click action at all, and a release on it does nothing.
 * @param tagPrefix each slot is tagged "$tagPrefix-$wire".
 */
@Composable
internal fun AirPodsNoiseControl(
    modes: Collection<ListeningMode>,
    effective: ListeningMode?,
    onRequest: (ListeningMode) -> (() -> Unit)?,
    tagPrefix: String,
    modifier: Modifier = Modifier,
) {
    val shown = remember(modes) { noiseControlOrder.filter { it in modes } }
    if (shown.isEmpty()) return
    val ink = LocalContentColor.current
    val pill = ink.copy(alpha = 0.08f)
    val thumb = ink.copy(alpha = 0.18f)
    val divider = ink.copy(alpha = 0.12f)
    BoxWithConstraints(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        val density = LocalDensity.current
        val count = shown.size
        val slotWidth = maxWidth / count
        val slotWidthPx = with(density) { slotWidth.toPx() }
        val legendTextWidthPx = with(density) { (maxWidth - 28.dp).toPx() }
        val measurer = rememberTextMeasurer()
        val labelStyle = MaterialTheme.typography.labelSmall
        val narrow = density.fontScale >= 1.5f
        val effectiveIndex = shown.indexOf(effective)
        val targetOffset = slotWidthPx * effectiveIndex.coerceAtLeast(0)

        var dragging by remember(shown) { mutableStateOf(false) }
        var dragOffset by remember(shown) { mutableFloatStateOf(0f) }
        val request by rememberUpdatedState(onRequest)
        val current by rememberUpdatedState(effective)

        // Read dragOffset directly in layout: no animation frame may trail the finger.
        val position = remember(shown) { Animatable(targetOffset) }
        val motion = FluidMotion.fluid<Float>()
        LaunchedEffect(targetOffset, dragging) {
            if (!dragging) position.animateTo(targetOffset, motion)
        }
        val thumbPresence by animateFloatAsState(
            targetValue = if (effectiveIndex >= 0) 1f else 0f,
            animationSpec = FluidMotion.standard(),
            label = "airpods noise control thumb presence",
        )
        Column(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .testTag("$tagPrefix-track")
                    .background(pill, FluidCapsuleShape)
                    .draggable(
                        orientation = Orientation.Horizontal,
                        // Admission gates stay authoritative; pending never disables a gesture.
                        enabled = effectiveIndex >= 0 && shown.any { onRequest(it) != null },
                        state = rememberDraggableState { delta ->
                            if (dragging) dragOffset = (dragOffset + delta).coerceIn(0f, slotWidthPx * (count - 1))
                        },
                        onDragStarted = { start ->
                            // Only the thumb is picked up, as upstream: a movement that begins on
                            // another slot is not a drag of this control.
                            if (noiseControlSlot(start.x - slotWidthPx / 2f, slotWidthPx, count) == noiseControlSlot(position.value, slotWidthPx, count)) {
                                dragOffset = position.value
                                dragging = true
                                position.stop()
                            }
                        },
                        onDragStopped = {
                            if (dragging) {
                                val mode = shown[noiseControlSlot(dragOffset, slotWidthPx, count)]
                                position.snapTo(dragOffset)
                                // Dispatch before releasing the draft. Only the shared effective value
                                // may settle selection; dispatch itself is not an acceptance receipt.
                                if (mode != current) request(mode)?.invoke()
                                dragging = false
                            }
                        },
                    ),
            ) {
                Box(
                    Modifier
                        .width(slotWidth)
                        .fillMaxHeight()
                        .offset { IntOffset((if (dragging) dragOffset else position.value).roundToInt(), 0) }
                        .testTag("$tagPrefix-thumb")
                        .graphicsLayer { alpha = thumbPresence },
                ) {
                    Box(Modifier.fillMaxSize().padding(3.dp).background(thumb, FluidCapsuleShape))
                }
                Row(Modifier.fillMaxWidth()) {
                    shown.forEachIndexed { index, mode ->
                        if (index > 0) {
                            // A divider next to the thumb would cut into it.
                            val beside = effectiveIndex >= 0 && (effectiveIndex == index - 1 || effectiveIndex == index)
                            Box(
                                Modifier
                                    .padding(vertical = 10.dp)
                                    .width(1.dp)
                                    .fillMaxHeight()
                                    .alpha(if (beside) 0f else 1f)
                                    .background(divider),
                            )
                        }
                        val label = modeLabel(mode.wire)
                        val ask = onRequest(mode)
                        val isEffective = mode == effective
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .testTag("$tagPrefix-${mode.wire}")
                                .semantics(mergeDescendants = true) {
                                    text = AnnotatedString(label)
                                    role = Role.RadioButton
                                    selected = isEffective
                                    // The effective mode has nothing to ask for; that is not "disabled".
                                    if (ask == null && !isEffective) disabled()
                                }
                                // The effective mode stays legible while nothing can be asked for.
                                .alpha(if (ask != null || isEffective) 1f else 0.5f)
                                .fluidRowPressable(onClick = ask, shape = FluidCapsuleShape),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                painter = painterResource(modeIconRes(mode.wire)),
                                contentDescription = null,
                                tint = ink,
                                modifier = Modifier.size(40.dp),
                            )
                        }
                    }
                }
            }

            if (narrow) {
                Column(Modifier.fillMaxWidth().padding(top = 4.dp).testTag("$tagPrefix-legend")) {
                    shown.forEach { mode ->
                        val ask = onRequest(mode)
                        Row(Modifier.fillMaxWidth().testTag("$tagPrefix-legend-${mode.wire}")
                            // The track owns the accessible choices at every font scale.
                            // Keep legend pointer input, but suppress its duplicate announcements.
                            .clearAndSetSemantics { }
                            .fluidRowPressable(onClick = ask, shape = FluidCapsuleShape)
                            .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(painterResource(modeIconRes(mode.wire)), null,
                                modifier = Modifier.size(20.dp))
                            val label = modeLabel(mode.wire)
                            val wordFits = label.split(' ').all {
                                measurer.measure(it, labelStyle).size.width <= legendTextWidthPx
                            }
                            Text(label, style = labelStyle, modifier = Modifier.weight(1f),
                                softWrap = wordFits,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                maxLines = if (wordFits) Int.MAX_VALUE else 1)
                        }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp).testTag("$tagPrefix-labels").clearAndSetSemantics { }) {
                    shown.forEach { mode ->
                        val lines = noiseControlLabelLines(modeLabel(mode.wire),
                            slotWidthPx - with(density) { 4.dp.toPx() }) { measurer.measure(it, labelStyle).size.width.toFloat() }
                            .split('\n')
                        Column(Modifier.weight(1f).padding(horizontal = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            lines.take(2).forEachIndexed { index, line ->
                                Text(text = if (index == 1) lines.drop(1).joinToString(" ") else line,
                                    style = labelStyle, softWrap = false, maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Explicit word boundaries prevent Compose from breaking long words inside narrow slots.
 * Each overlong line uses Text's ellipsis; the complete name remains on the radio slot. */
internal fun noiseControlLabelLines(label: String, width: Float, measure: (String) -> Float): String {
    val lines = mutableListOf<String>()
    for (word in label.split(' ').filter { it.isNotEmpty() }) {
        if (lines.isNotEmpty() && measure(lines.last() + " " + word) <= width)
            lines[lines.lastIndex] += " " + word
        else lines += word
    }
    return lines.joinToString("\n")
}
