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
// Changed here, and only this:
//  - the thumb is where the device last said the mode is, and nowhere else. Upstream moves it to
//    the tapped or released slot at once; here a tap or a release only sends a request, the slot
//    asked for gets a ring while the answer is outstanding, and the thumb travels when the answer
//    arrives. With no mode received there is no thumb;
//  - the drag is read on the pill, not on the thumb. Upstream puts its `draggable` on the thumb
//    under a row of clickable marks, where a sibling above it takes the pointer first; on the pill
//    a tap still belongs to the mark and a horizontal movement to the drag. The thumb follows the
//    finger without the spring while it is held, and springs only on release;
//  - any number of modes, not only three or four (AirPods Pro 1 has no Adaptive);
//  - colours from the theme, the spring from FluidMotion, the capsule shape from the engine.
package dev.lelonio.square.ui.airpods

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
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
 * @param received the mode the device last reported in this session, or null. The thumb.
 * @param requested the mode a request still waiting for its answer asked for, or null. The ring.
 * @param onRequest returns the request for a mode, or null when that mode cannot be asked for
 *   now (or is the received one, which there is nothing to ask for). A slot without a request
 *   has no click action at all, and a release on it does nothing.
 * @param tagPrefix each slot is tagged "$tagPrefix-$wire".
 */
@Composable
internal fun AirPodsNoiseControl(
    modes: Collection<ListeningMode>,
    received: ListeningMode?,
    requested: ListeningMode?,
    onRequest: (ListeningMode) -> (() -> Unit)?,
    tagPrefix: String,
    modifier: Modifier = Modifier,
) {
    val shown = remember(modes) { noiseControlOrder.filter { it in modes } }
    if (shown.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    val ink = LocalContentColor.current
    val pill = ink.copy(alpha = 0.08f)
    val thumb = ink.copy(alpha = 0.18f)
    val divider = ink.copy(alpha = 0.12f)
    val awaitingRing = scheme.primary.copy(alpha = 0.7f)
    val pendingWords = stringResource(R.string.airpods_ui_pending)

    BoxWithConstraints(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        val density = LocalDensity.current
        val count = shown.size
        val slotWidth = maxWidth / count
        val slotWidthPx = with(density) { slotWidth.toPx() }
        val receivedIndex = shown.indexOf(received)
        val requestedIndex = shown.indexOf(requested)
        val targetOffset = slotWidthPx * receivedIndex.coerceAtLeast(0)

        var dragging by remember { mutableStateOf(false) }
        var dragOffset by remember { mutableFloatStateOf(0f) }
        val request by rememberUpdatedState(onRequest)
        val current by rememberUpdatedState(received)

        // Held, the thumb is under the finger; released, it springs to wherever the device says.
        val animatedOffset by animateFloatAsState(
            targetValue = if (dragging) dragOffset else targetOffset,
            animationSpec = if (dragging) snap() else FluidMotion.fluid(),
            label = "airpods noise control thumb",
        )
        val thumbPresence by animateFloatAsState(
            targetValue = if (receivedIndex >= 0) 1f else 0f,
            animationSpec = FluidMotion.standard(),
            label = "airpods noise control thumb presence",
        )
        val ringPresence by animateFloatAsState(
            targetValue = if (requestedIndex >= 0) 1f else 0f,
            animationSpec = FluidMotion.standard(),
            label = "airpods noise control request",
        )
        // The ring fades where it was: its slot is remembered for as long as it is still visible.
        var ringSlot by remember { mutableStateOf(0) }
        if (requestedIndex >= 0) ringSlot = requestedIndex

        Column(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .background(pill, FluidCapsuleShape)
                    .draggable(
                        orientation = Orientation.Horizontal,
                        // Nothing to drag while no mode has been received.
                        enabled = receivedIndex >= 0,
                        state = rememberDraggableState { delta ->
                            if (dragging) dragOffset = (dragOffset + delta).coerceIn(0f, slotWidthPx * (count - 1))
                        },
                        onDragStarted = { start ->
                            // Only the thumb is picked up, as upstream: a movement that begins on
                            // another slot is not a drag of this control.
                            if (noiseControlSlot(start.x - slotWidthPx / 2f, slotWidthPx, count) == receivedIndex) {
                                dragOffset = targetOffset
                                dragging = true
                            }
                        },
                        onDragStopped = {
                            if (dragging) {
                                dragging = false
                                val mode = shown[noiseControlSlot(dragOffset, slotWidthPx, count)]
                                // A release is a request, never a selection: the thumb goes back
                                // to the received mode and waits there for the device's answer.
                                if (mode != current) request(mode)?.invoke()
                            }
                        },
                    ),
            ) {
                Box(
                    Modifier
                        .width(slotWidth)
                        .fillMaxHeight()
                        .offset { IntOffset(animatedOffset.roundToInt(), 0) }
                        .graphicsLayer { alpha = thumbPresence },
                ) {
                    Box(Modifier.fillMaxSize().padding(3.dp).background(thumb, FluidCapsuleShape))
                }
                Box(
                    Modifier
                        .width(slotWidth)
                        .fillMaxHeight()
                        .offset { IntOffset((slotWidthPx * ringSlot).roundToInt(), 0) }
                        .graphicsLayer { alpha = ringPresence }
                        .padding(3.dp)
                        .border(1.5.dp, awaitingRing, FluidCapsuleShape),
                )

                Row(Modifier.fillMaxWidth()) {
                    shown.forEachIndexed { index, mode ->
                        if (index > 0) {
                            // A divider next to the thumb would cut into it.
                            val beside = receivedIndex >= 0 && (receivedIndex == index - 1 || receivedIndex == index)
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
                        val isReceived = mode == received
                        val isRequested = mode == requested
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .testTag("$tagPrefix-${mode.wire}")
                                .semantics(mergeDescendants = true) {
                                    text = AnnotatedString(label)
                                    role = Role.RadioButton
                                    selected = isReceived
                                    if (isRequested) stateDescription = pendingWords
                                    // The received mode has nothing to ask for; that is not "disabled".
                                    if (ask == null && !isReceived) disabled()
                                }
                                // The received mode stays legible while nothing can be asked for.
                                .alpha(if (ask != null || isReceived) 1f else 0.5f)
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

            Row(Modifier.fillMaxWidth().padding(top = 4.dp).clearAndSetSemantics { }) {
                shown.forEach { mode ->
                    Text(
                        text = modeLabel(mode.wire),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).padding(horizontal = 2.dp),
                    )
                }
            }
        }
    }
}
