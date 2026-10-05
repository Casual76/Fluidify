package dev.pampa.fluidify.wear.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import dev.antigravity.fluidengine.wear.ambient.LocalFluidWearAmbient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.snapshotFlow

/**
 * Always-on display for the whole app: the player, with the lights turned down.
 *
 * On the player itself nothing is put over it: [selfDrawn] is true and the player dims in place,
 * keeping its clock and title exactly where they were (see [PlayerScreen]). Anywhere else — a
 * list, a playlist, the Home — [ambientPlayer] fades in over the app, so always-on looks the same
 * whichever screen the wrist went down on. The app stays composed under it, neither drawn nor read
 * by accessibility, and comes back where it was: a list keeps its scroll, a search its query.
 *
 * The fade in is eased like the player's own dimming. A panel in always-on refreshes about once a
 * minute, so a fade can be frozen where it was when refreshing stopped; the first refresh after
 * entering puts it at its end.
 */
@Composable
internal fun WatchAmbientSurface(
    selfDrawn: Boolean,
    modifier: Modifier = Modifier,
    ambientPlayer: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val ambient = LocalFluidWearAmbient.current
    val inAmbient = ambient.isAmbient
    val covered = remember { Animatable(if (inAmbient && !selfDrawn) 1f else 0f) }
    LaunchedEffect(inAmbient, selfDrawn) {
        if (inAmbient && !selfDrawn) {
            val enteredAt = ambient.updateTick
            launch { covered.animateTo(1f, tween(CoverInMs, easing = FastOutSlowInEasing)) }
            snapshotFlow { ambient.updateTick }.first { it != enteredAt }
            covered.snapTo(1f)
        } else {
            covered.animateTo(0f, tween(CoverOutMs, easing = FastOutSlowInEasing))
        }
    }
    val showPlayer by remember { derivedStateOf { covered.value > 0f } }
    val hideApp by remember { derivedStateOf { covered.value >= 1f } }
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent { if (!hideApp) drawContent() }
                .then(if (inAmbient && !selfDrawn) Modifier.clearAndSetSemantics { } else Modifier),
        ) {
            content()
        }
        if (showPlayer) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = covered.value },
            ) {
                ambientPlayer()
            }
        }
    }
}

/** How long the always-on player takes to cover another screen, and to give it back. */
private const val CoverInMs = 420
private const val CoverOutMs = 260
