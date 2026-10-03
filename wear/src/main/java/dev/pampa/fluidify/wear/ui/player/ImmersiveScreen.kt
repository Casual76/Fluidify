package dev.pampa.fluidify.wear.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Icon
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.Heart
import com.adamglin.phosphoricons.regular.Heart
import com.adamglin.phosphoricons.fill.Pause
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.fill.SkipForward
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.antigravity.fluidengine.ui.haptics.FluidHapticEvent
import dev.antigravity.fluidengine.ui.haptics.LocalFluidHaptics
import dev.antigravity.fluidengine.wear.ambient.LocalFluidWearAmbient
import dev.antigravity.fluidengine.wear.ambient.fluidBurnInShift
import dev.antigravity.fluidengine.wear.components.FluidEdgeGlowRing
import dev.antigravity.fluidengine.wear.glass.FluidGlassBadge
import dev.antigravity.fluidengine.wear.theme.FluidWearAccent
import dev.antigravity.fluidengine.wear.theme.FluidWearDimens
import dev.pampa.fluidify.wear.link.ArtStore
import dev.pampa.fluidify.wear.playback.PlaybackControls
import dev.pampa.fluidify.wear.ui.common.CoverLayer
import dev.pampa.fluidify.wear.ui.common.rememberArtworkAccent
import kotlinx.coroutines.delay

/**
 * The cover and nothing else, like Spotify's swipe-down view.
 *
 * The whole screen is the control: one tap plays or pauses, two skip ahead, a long press likes the
 * song (or takes the like back). What was done shows
 * for a moment as a glass disc in the middle of the cover, with the glyph of what just happened,
 * and then the cover is alone again.
 *
 * @param active false while the page is off screen: the ring stops ticking.
 */
@Composable
fun ImmersiveScreen(
    controls: PlaybackControls,
    art: ArtStore,
    modifier: Modifier = Modifier,
    active: Boolean = true,
) {
    val now by controls.nowPlaying.collectAsStateWithLifecycle()
    val ambient = LocalFluidWearAmbient.current
    val haptics = LocalFluidHaptics.current
    val snapshot = now.snapshot
    val accent = rememberArtworkAccent(snapshot?.track?.artKey, art)
    var sign by remember { mutableStateOf<ImageVector?>(null) }
    var signal by remember { mutableIntStateOf(0) }
    LaunchedEffect(signal) {
        if (signal == 0) return@LaunchedEffect
        delay(SignMillis)
        sign = null
    }

    FluidWearAccent(seed = accent.takeUnless { ambient.isAmbient }) {
        val backdrop = rememberGlassBackdrop()
        Box(
            modifier = modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            val playing = controls.nowPlaying.value.snapshot?.let { it.isPlaying || it.playWhenReady } == true
                            sign = if (playing) PhosphorIcons.Fill.Pause else PhosphorIcons.Fill.Play
                            signal++
                            haptics.play(FluidHapticEvent.Confirm)
                            controls.togglePlay()
                        },
                        onDoubleTap = {
                            sign = PhosphorIcons.Fill.SkipForward
                            signal++
                            haptics.play(FluidHapticEvent.Tap)
                            controls.next()
                        },
                        // A long press on the cover is the heart, as a double tap is in Spotify's
                        // own cover view; the disc says which way it went.
                        onLongPress = {
                            val snapshot = controls.nowPlaying.value.snapshot
                            if (snapshot?.track?.uri?.startsWith("spotify:track:") == true) {
                                val liked = snapshot.liked == true
                                sign = if (liked) PhosphorIcons.Regular.Heart else PhosphorIcons.Fill.Heart
                                signal++
                                haptics.play(if (liked) FluidHapticEvent.ToggleOff else FluidHapticEvent.ToggleOn)
                                controls.setLiked(!liked)
                            }
                        },
                    )
                },
        ) {
            CoverLayer(
                artKey = snapshot?.track?.artKey,
                art = art,
                dimmed = ambient.isAmbient,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (ambient.isAmbient) Modifier.fluidBurnInShift(ambient) else Modifier.glassBackdropSource(backdrop)),
            )
            if (!ambient.isAmbient) {
                FluidEdgeGlowRing(
                    positionMs = { now.positionAt(System.currentTimeMillis()) },
                    durationMs = snapshot?.track?.durationMs ?: 0L,
                    running = active && snapshot?.isPlaying == true && !snapshot.buffering,
                )
                val shown = sign
                AnimatedVisibility(
                    visible = shown != null,
                    enter = fadeIn(FluidMotion.fadeIn()) + scaleIn(FluidMotion.snappy(), initialScale = SignScale),
                    exit = fadeOut(FluidMotion.fadeOut()) + scaleOut(targetScale = SignScale),
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    // Keeps the last glyph through the exit, instead of going blank as it leaves.
                    val glyph = remember { mutableStateOf(shown) }
                    if (shown != null) glyph.value = shown
                    FluidGlassBadge(backdrop = backdrop) {
                        glyph.value?.let { Icon(it, contentDescription = null, modifier = Modifier.size(FluidWearDimens.IconLarge)) }
                    }
                }
            }
        }
    }
}

private const val SignMillis = 700L
private const val SignScale = 0.8f
