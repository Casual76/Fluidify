package dev.lelonio.square.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal fun playerUsesTwoPanes(width: Dp, height: Dp, fontScale: Float) =
    width >= 840.dp && height >= 480.dp && fontScale < 1.5f

/** Changes measurement and placement only: resizing never reparents a video or panel. */
@Composable
internal fun AdaptivePlayerLayout(
    twoPane: Boolean,
    panelOpen: Boolean,
    modifier: Modifier = Modifier,
    media: @Composable () -> Unit,
    detail: @Composable () -> Unit,
    controls: @Composable () -> Unit,
) {
    Layout(
        modifier = modifier,
        content = {
            Box { media() }
            Box { detail() }
            Box { controls() }
        },
    ) { children, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val contentWidth = if (twoPane) {
            // Cap the complete pair as well as its control column, retaining the 45/55 ratio.
            minOf(width, 1200.dp.roundToPx(), (560.dp.toPx() / .55f + 24.dp.toPx()).roundToInt())
        } else minOf(width, 560.dp.roundToPx())
        val gap = if (twoPane) 24.dp.roundToPx() else 0
        val mediaWidth = if (twoPane) ((contentWidth - gap) * .45f).roundToInt() else contentWidth
        val controlsWidth = if (twoPane) minOf(contentWidth - gap - mediaWidth, 560.dp.roundToPx()) else contentWidth
        // The scroll viewport always has finite constraints. On a short window the artwork
        // yields first; panels retain a usable viewport and transport/actions scroll together.
        val minimumStage = if (twoPane && panelOpen) {
            // A real reading/list viewport, even when lyrics, a long title or video
            // actions make the controls scroll. Never leave the panel the scraps.
            maxOf(192.dp.roundToPx(), (height * .42f).roundToInt()).coerceAtMost(height / 2)
        } else minOf(height / 2, (if (panelOpen) 144.dp else 72.dp).roundToPx())
        val controlLimit = if (twoPane && !panelOpen) height else (height - minimumStage).coerceAtLeast(0)
        val control = children[2].measure(Constraints(maxWidth = controlsWidth, minWidth = controlsWidth, maxHeight = controlLimit))
        val stageHeight = (height - control.height).coerceAtLeast(0)
        val art = children[0].measure(Constraints.fixed(mediaWidth, if (twoPane) height else stageHeight))
        val detailWidth = if (twoPane && panelOpen) controlsWidth else mediaWidth
        val detailHeight = if (twoPane && !panelOpen) height else stageHeight
        val panel = children[1].measure(Constraints.fixed(detailWidth, detailHeight))
        layout(width, height) {
            val usedWidth = if (twoPane) mediaWidth + gap + controlsWidth else contentWidth
            val start = (width - usedWidth) / 2
            art.placeRelative(start, 0)
            if (twoPane) {
                val right = start + mediaWidth + gap
                control.placeRelative(right, if (panelOpen) 0 else (height - control.height) / 2)
                panel.placeRelative(if (panelOpen) right else start, if (panelOpen) control.height else 0)
            } else {
                panel.placeRelative(start, 0)
                control.placeRelative(start, stageHeight)
            }
        }
    }
}
