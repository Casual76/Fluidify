package dev.pampa.fluidify.wear.ui.player

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.haptics.FluidHapticEvent
import dev.antigravity.fluidengine.ui.haptics.LocalFluidHaptics
import dev.antigravity.fluidengine.wear.ambient.LocalFluidWearAmbient
import dev.antigravity.fluidengine.wear.ambient.fluidBurnInShift
import dev.antigravity.fluidengine.wear.components.FluidEdgeProgressRing
import dev.antigravity.fluidengine.wear.glass.fluidGlassBurst
import dev.antigravity.fluidengine.wear.glass.rememberFluidGlassBurstState
import dev.pampa.fluidify.wear.link.ArtStore
import dev.pampa.fluidify.wear.playback.PlaybackControls
import dev.pampa.fluidify.wear.ui.common.CoverLayer

/**
 * The cover and nothing else, like Spotify's swipe-down view.
 *
 * The whole screen is the control: one tap plays or pauses, two skip ahead. A
 * burst of light answers where the finger landed, since there is no button to
 * light up.
 */
@Composable
fun ImmersiveScreen(
    controls: PlaybackControls,
    art: ArtStore,
    modifier: Modifier = Modifier,
) {
    val now by controls.nowPlaying.collectAsStateWithLifecycle()
    val ambient = LocalFluidWearAmbient.current
    val haptics = LocalFluidHaptics.current
    val burst = rememberFluidGlassBurstState()
    val onDark = GlassDefaults.isDarkSurface()
    val snapshot = now.snapshot

    Box(
        modifier = modifier
            .fillMaxSize()
            .fluidGlassBurst(burst, onDarkSurface = onDark, enabled = !ambient.isAmbient)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { at ->
                        burst.fire(at)
                        haptics.play(FluidHapticEvent.Confirm)
                        controls.togglePlay()
                    },
                    onDoubleTap = { at ->
                        burst.fire(at)
                        haptics.play(FluidHapticEvent.Tap)
                        controls.next()
                    },
                )
            },
    ) {
        CoverLayer(
            artKey = snapshot?.track?.artKey,
            art = art,
            dimmed = ambient.isAmbient,
            modifier = Modifier.fillMaxSize().then(if (ambient.isAmbient) Modifier.fluidBurnInShift(ambient) else Modifier),
        )
        if (!ambient.isAmbient) {
            FluidEdgeProgressRing(
                positionMs = { now.positionAt(System.currentTimeMillis()) },
                durationMs = snapshot?.track?.durationMs ?: 0L,
                running = snapshot?.isPlaying == true && !snapshot.buffering,
            )
        }
    }
}
