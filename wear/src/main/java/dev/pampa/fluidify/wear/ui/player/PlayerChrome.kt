package dev.pampa.fluidify.wear.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
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
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.haptics.FluidHapticEvent
import dev.antigravity.fluidengine.ui.haptics.LocalFluidHaptics
import dev.antigravity.fluidengine.wear.glass.FluidGlassBadge
import dev.antigravity.fluidengine.wear.theme.FluidWearDimens
import dev.pampa.fluidify.wear.R
import dev.pampa.fluidify.wear.playback.NowPlaying
import dev.pampa.fluidify.wear.playback.PlaybackControls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Whether the player's controls are on screen: 0 shown, 1 hidden, anything between while a finger
 * is moving them.
 *
 * Hiding them is not a page. The cover behind is the same picture either way, and a page of its
 * own above the player meant the whole screen slid away and an identical cover slid in, a second
 * screen pretending to be the first. Now the cover stays exactly where it is and only the controls
 * go: they follow the finger pulling down on the player, and come back with the finger pushing up.
 * With them gone the whole cover is the control (see [ImmersiveGestures]).
 */
@Stable
class PlayerChrome internal constructor(private val scope: CoroutineScope) {
    var hidden by mutableFloatStateOf(0f)
        private set

    /** Fully out of the way: the cover answers touches, the controls do not. */
    val isHidden: Boolean get() = hidden >= HALFWAY

    private var settling: Job? = null

    /** Follows a drag: [fraction] of the full travel, positive towards hidden. */
    internal fun dragBy(fraction: Float) {
        settling?.cancel()
        hidden = (hidden + fraction).coerceIn(0f, 1f)
    }

    /** Lets go: on to the end the finger was heading for, or back where it started. */
    internal fun settle(velocity: Float) {
        val target = when {
            velocity > FLING_VELOCITY -> 1f
            velocity < -FLING_VELOCITY -> 0f
            hidden >= HALFWAY -> 1f
            else -> 0f
        }
        animateTo(target, velocity)
    }

    fun show() = animateTo(0f)

    fun hide() = animateTo(1f)

    private fun animateTo(target: Float, velocity: Float = 0f) {
        settling?.cancel()
        if (hidden == target) return
        settling = scope.launch {
            animate(
                initialValue = hidden,
                targetValue = target,
                initialVelocity = velocity / TRAVEL_VELOCITY_SCALE,
                animationSpec = FluidMotion.fluid(),
            ) { value, _ -> hidden = value.coerceIn(0f, 1f) }
        }
    }

    private companion object {
        const val HALFWAY = 0.5f

        /** Pixels a second: a flick faster than this decides, whatever the distance. */
        const val FLING_VELOCITY = 600f

        /** A fling's pixels a second, as travel fractions a second, roughly. */
        const val TRAVEL_VELOCITY_SCALE = 400f
    }
}

@Composable
fun rememberPlayerChrome(): PlayerChrome {
    val scope = rememberCoroutineScope()
    return remember(scope) { PlayerChrome(scope) }
}

/**
 * The drag that hides and shows the controls, read from the pager the player sits in.
 *
 * Pulling down on the player is a scroll the pager cannot use (the player is its first page), so it
 * arrives here as what is left over. Pushing up while the controls are away is taken before the
 * pager sees it, so it brings them back instead of moving on to the Home. Once a drag has started
 * moving the controls it keeps them to the end, both ways: a finger that changes its mind halfway
 * does not suddenly start turning the page.
 *
 * @param enabled whether the player is the page on screen and still: anywhere else, every scroll
 *   belongs to the pager.
 */
class PlayerChromeDrag(
    private val chrome: PlayerChrome,
    private val travelPx: Float,
    private val enabled: () -> Boolean,
) : NestedScrollConnection {
    private var dragging = false

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || !enabled()) return Offset.Zero
        if (dragging || (available.y < 0f && chrome.hidden > 0f)) {
            move(available.y)
            return Offset(0f, available.y)
        }
        return Offset.Zero
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || !enabled()) return Offset.Zero
        if (available.y > 0f) {
            move(available.y)
            return Offset(0f, available.y)
        }
        return Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (!dragging) return Velocity.Zero
        dragging = false
        chrome.settle(available.y)
        return available
    }

    private fun move(dy: Float) {
        dragging = true
        if (abs(dy) > 0f) chrome.dragBy(dy / travelPx)
    }
}

@Composable
fun rememberPlayerChromeDrag(chrome: PlayerChrome, enabled: () -> Boolean): PlayerChromeDrag {
    val travel = with(LocalDensity.current) { ChromeTravel.toPx() }
    return remember(chrome, travel) { PlayerChromeDrag(chrome, travel, enabled) }
}

/** How far the finger travels to take the controls all the way away. */
private val ChromeTravel = 72.dp

/**
 * The whole cover as the control, while the player's controls are hidden: one tap plays or pauses,
 * two skip ahead, a long press likes the song (or takes the like back). What was done shows for a
 * moment as a glass disc in the middle of the cover, with the glyph of what just happened.
 *
 * A single tap waits out the double-tap time before it is believed, which is the price of having
 * both on one surface. Acting on the first tap and undoing it when a second came was tried and is
 * worse: with the music on the phone that is three commands over Bluetooth and a gap in the song
 * for every skip.
 */
@Composable
internal fun ImmersiveGestures(
    controls: PlaybackControls,
    nowState: State<NowPlaying>,
    backdrop: GlassBackdropState,
    chrome: PlayerChrome,
    onUnlike: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalFluidHaptics.current
    val playing by remember { derivedStateOf { nowState.value.snapshot?.let { it.isPlaying || it.playWhenReady } == true } }
    val liked by remember { derivedStateOf { nowState.value.snapshot?.liked == true } }
    val likeable by remember { derivedStateOf { nowState.value.snapshot?.track.likeable() } }
    val playLabel = stringResource(if (playing) R.string.pause else R.string.play)
    val nextLabel = stringResource(R.string.next)
    val likeLabel = stringResource(if (liked) R.string.unlike else R.string.like)
    val showLabel = stringResource(R.string.show_controls)
    var sign by remember { mutableStateOf<ImageVector?>(null) }
    var signal by remember { mutableIntStateOf(0) }
    LaunchedEffect(signal) {
        if (signal == 0) return@LaunchedEffect
        delay(SignMillis)
        sign = null
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .semantics {
                customActions = buildList {
                    add(CustomAccessibilityAction(showLabel) { chrome.show(); true })
                    add(CustomAccessibilityAction(playLabel) { controls.togglePlay(); true })
                    add(CustomAccessibilityAction(nextLabel) { controls.next(); true })
                    if (likeable) {
                        add(
                            CustomAccessibilityAction(likeLabel) {
                                LikeActions(controls, onUnlike ?: { controls.setLiked(false) }).toggle(liked)
                                true
                            },
                        )
                    }
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
                    // A long press is the heart, as a double tap is in Spotify's own cover view.
                    // Liking shows the filled heart on its disc; taking a like back asks first.
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

private const val SignMillis = 700L
private const val SignScale = 0.8f
