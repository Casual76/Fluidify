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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Icon
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.Heart
import com.adamglin.phosphoricons.fill.Pause
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.fill.SkipForward
import com.adamglin.phosphoricons.regular.Heart
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.antigravity.fluidengine.ui.haptics.FluidHapticEvent
import dev.antigravity.fluidengine.ui.haptics.LocalFluidHaptics
import dev.antigravity.fluidengine.wear.ambient.LocalFluidWearAmbient
import dev.antigravity.fluidengine.wear.components.fluidRotarySteps
import dev.antigravity.fluidengine.wear.glass.FluidGlassBadge
import dev.antigravity.fluidengine.wear.theme.FluidWearAccent
import dev.antigravity.fluidengine.wear.theme.FluidWearDimens
import dev.pampa.fluidify.wear.R
import dev.pampa.fluidify.wear.link.ArtStore
import dev.pampa.fluidify.wear.playback.PlaybackControls
import dev.pampa.fluidify.wear.playback.VolumeControl
import dev.pampa.fluidify.wear.ui.common.CoverLayer
import dev.pampa.fluidify.wear.ui.common.rememberArtworkAccent
import dev.pampa.fluidify.wear.ui.common.screenStarted
import kotlinx.coroutines.delay

/**
 * The cover and nothing else, like Spotify's swipe-down view.
 *
 * The whole screen is the control: one tap plays or pauses, two skip ahead, a long press likes the
 * song (or takes the like back). What was done shows
 * for a moment as a glass disc in the middle of the cover, with the glyph of what just happened,
 * and then the cover is alone again.
 *
 * A single tap waits out the double-tap time before it is believed, which is the price of having
 * both on one surface. Acting on the first tap and undoing it when a second came was tried and is
 * worse: with the music on the phone that is three commands over Bluetooth and a gap in the song
 * for every skip.
 *
 * @param active false while the page is off screen: the ring stops ticking.
 */
@Composable
fun ImmersiveScreen(
    controls: PlaybackControls,
    art: ArtStore,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    /** Taking a like back asks first, as the heart on the player does; see [LikeActions]. */
    onUnlike: (() -> Unit)? = null,
    volume: VolumeControl? = null,
) {
    val nowState = controls.nowPlaying.collectAsStateWithLifecycle()
    val ambient = LocalFluidWearAmbient.current
    if (ambient.isAmbient) {
        // Drawn by the screen that hosts this one, when it does (see WatchAmbientSurface); alone, here.
        if (!LocalAmbientHosted.current) AmbientNowPlaying(nowState.value, modifier, art)
        return
    }
    val started = screenStarted()
    val playing by remember { derivedStateOf { nowState.value.snapshot?.let { it.isPlaying || it.playWhenReady } == true } }
    val liked by remember { derivedStateOf { nowState.value.snapshot?.liked == true } }
    val likeable by remember { derivedStateOf { nowState.value.snapshot?.track.likeable() } }
    val artKey by remember { derivedStateOf { nowState.value.snapshot?.track?.artKey } }
    val durationMs by remember { derivedStateOf { nowState.value.snapshot?.track?.durationMs ?: 0L } }
    val deviceName by remember { derivedStateOf { nowState.value.snapshot?.device?.name } }
    val ticking by remember(active, started) {
        derivedStateOf {
            val snapshot = nowState.value.snapshot
            active && started && snapshot?.isPlaying == true && !snapshot.buffering
        }
    }
    // The same word as the player's own play button: playing means playing or about to.
    val playLabel = stringResource(if (playing) R.string.pause else R.string.play)
    val nextLabel = stringResource(R.string.next)
    val likeLabel = stringResource(if (liked) R.string.unlike else R.string.like)
    val haptics = LocalFluidHaptics.current
    val accent = rememberArtworkAccent(artKey, art)
    var sign by remember { mutableStateOf<ImageVector?>(null) }
    var signal by remember { mutableIntStateOf(0) }
    LaunchedEffect(signal) {
        if (signal == 0) return@LaunchedEffect
        delay(SignMillis)
        sign = null
    }

    FluidWearAccent(seed = accent) {
        val backdrop = rememberGlassBackdrop()
        Box(
            modifier = modifier
                .fillMaxSize()
                .then(if (volume != null) Modifier.fluidRotarySteps(onSteps = volume::turn) else Modifier)
                .semantics {
                    customActions = buildList {
                        add(CustomAccessibilityAction(playLabel) { controls.togglePlay(); true })
                        add(CustomAccessibilityAction(nextLabel) { controls.next(); true })
                        if (likeable) add(CustomAccessibilityAction(likeLabel) {
                            LikeActions(controls, onUnlike ?: { controls.setLiked(false) }).toggle(liked)
                            true
                        })
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            val nowPlaying = controls.nowPlaying.value.snapshot?.let { it.isPlaying || it.playWhenReady } == true
                            sign = if (nowPlaying) PhosphorIcons.Fill.Pause else PhosphorIcons.Fill.Play
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
                        // own cover view. Liking shows the filled heart on its disc; taking a like
                        // back asks first, as everywhere.
                        onLongPress = {
                            val snapshot = controls.nowPlaying.value.snapshot
                            if (snapshot?.track.likeable()) {
                                val isLiked = snapshot?.liked == true
                                if (isLiked && onUnlike != null) {
                                    haptics.play(FluidHapticEvent.Tap)
                                    onUnlike()
                                } else {
                                    sign = if (isLiked) PhosphorIcons.Regular.Heart else PhosphorIcons.Fill.Heart
                                    signal++
                                    haptics.play(if (isLiked) FluidHapticEvent.ToggleOff else FluidHapticEvent.ToggleOn)
                                    controls.setLiked(!isLiked)
                                }
                            }
                        },
                    )
                },
        ) {
            CoverLayer(
                artKey = artKey,
                art = art,
                modifier = Modifier
                    .fillMaxSize()
                    .glassBackdropSource(backdrop),
            )
            AudioReactiveRing(
                positionMs = { nowState.value.positionAt(System.currentTimeMillis()) },
                durationMs = durationMs,
                running = ticking,
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
            if (volume != null) {
                val visible by volume.visible.collectAsStateWithLifecycle()
                val level by volume.level.collectAsStateWithLifecycle()
                VolumeOverlay(visible = visible, level = level, device = deviceName, backdrop = backdrop)
            }
        }
    }
}

private const val SignMillis = 700L
private const val SignScale = 0.8f
