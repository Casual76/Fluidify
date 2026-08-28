package dev.lelonio.square.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Every law of the journey between the pill and the player, in one place.
 *
 * The journey is one number — `expand`, 0 at the pill and 1 at the page — and
 * one surface whose silhouette is that number. Everything here is a pure
 * function of it, so the same laws hold whether the number is being written by a
 * finger on the pill, a finger on the player, the back gesture or a spring.
 *
 * Nothing in this file reads state in composition. The functions are called from
 * draw and layer blocks, which is what keeps a screen carrying a video surface
 * and several panes of live glass from recomposing sixty times a second — the
 * mistake this replaced was measured at a 97ms median frame.
 */

/**
 * The one spring, identical in both directions.
 *
 * `ResponseSmooth` is the family's declared token for "sheets, expanding cards,
 * hero content", and this is the largest of those. Critically damped on purpose:
 * a whole page that bounces reads as loose layout, and the geometry underneath
 * does not clamp below zero — an under-damped spring would extrapolate a capsule
 * *smaller* than the pill, on top of the real pill that has already come back.
 *
 * The visibility threshold is not a refinement. At the Float default of 0.01 the
 * spring stops at 0.99, the journey never reports itself finished, and a
 * full-screen pane of glass never stops sampling.
 */
val PlayerMorphSpec: SpringSpec<Float> = spring(
    dampingRatio = FluidMotion.DampingChrome,
    stiffness = FluidMotion.ResponseSmooth,
    visibilityThreshold = 0.0001f,
)

/** Close enough to an end to count as being there; gates composition, not drawing. */
const val MorphEpsilon = 0.0005f

/**
 * The draw gate.
 *
 * A node's first and last frame can draw with default properties, which on a
 * surface whose arrival is an alpha is a flash at full strength. Nothing is
 * recorded below this, and an empty display list has no flash to replay.
 */
const val MorphDrawGate = 0.004f

/** Past this the pill's own face is already gone, by the engine's Outgoing contract. */
const val PillFaceGate = 0.36f

/** How far in the shadow of the pill follows the surface before letting go. */
const val ShadowFadeEnd = 0.25f

/** How much of the window's travel the arriving page climbs on its own. */
const val ArrivalRise = 0.14f

/** Where the glass is allowed to sample live again: at rest, and nowhere else. */
const val GlassLiveAt = 0.999f

/**
 * How fast a flick has to be, in journeys per second, to decide on its own.
 *
 * Just over half a journey a second. Below it the release is decided by where
 * the finger left the number — projected forward a little, so a slow push in a
 * clear direction is not treated as indecision.
 */
private const val FlingUnitsPerSecond = 0.55f

/** How far ahead a release is projected before its position is read. */
private const val ProjectionSeconds = 0.15f

/** The halfway point, and the same one in both directions. */
private const val PositionalPoint = 0.5f

/**
 * How much of the window the artwork has already filled. Opaque at 55%.
 *
 * This is what stops two readable layers ever being on screen together, and it
 * is also what makes it honest for the glass to hold a stale sample: underneath
 * the surface there is an opaque picture, not the page the pill was standing on.
 */
fun morphFloorAlpha(t: Float): Float = ((t - 0.06f) / 0.49f).coerceIn(0f, 1f)

/** How much of the player's own chrome has arrived. Born at 48%, whole at 95%. */
fun morphChromeAlpha(t: Float): Float = ((t - 0.48f) / 0.47f).coerceIn(0f, 1f)

/** The top edge of the silhouette, in the host's coordinates. */
fun morphTop(pillTopPx: Float, t: Float): Float = pillTopPx * (1f - t)

/**
 * Where a release goes: not the position, the *projected* position.
 *
 * A flick decides on its own sign. Anything slower is carried forward for a
 * seventh of a second and then asked which half it is in — which is how a slow
 * but deliberate push finishes the journey it was clearly making, while a hand
 * that stopped and let go goes back to wherever it was nearest.
 */
fun playerMorphTarget(progress: Float, progressVelocity: Float): Float = when {
    progressVelocity > FlingUnitsPerSecond -> 1f
    progressVelocity < -FlingUnitsPerSecond -> 0f
    progress + progressVelocity * ProjectionSeconds > PositionalPoint -> 1f
    else -> 0f
}

/**
 * The finger. `deltaPx` positive downwards, one formula for both ends.
 *
 * `snapTo` rather than an animation: while the finger is down it owns the
 * number outright, and a spring running underneath it is a second opinion.
 */
fun CoroutineScope.dragPlayerMorph(
    expand: Animatable<Float, AnimationVector1D>,
    deltaPx: Float,
    travelPx: Float,
) = launch {
    expand.snapTo(expand.value - deltaPx / travelPx.coerceAtLeast(1f))
}

/**
 * The release, for every place that releases.
 *
 * The finger's velocity goes *into* the spring. Without that the journey stops
 * dead at the moment of release and then starts again, which is the lie the
 * previous version told: it read the velocity to pick a target and then threw it
 * away.
 */
fun CoroutineScope.settlePlayerMorph(
    expand: Animatable<Float, AnimationVector1D>,
    velocityPxPerSec: Float,
    travelPx: Float,
    haptics: HapticFeedback?,
) = launch {
    val velocity = -velocityPxPerSec / travelPx.coerceAtLeast(1f)
    val target = playerMorphTarget(expand.value, velocity)
    // One knock, and only when the journey actually ends somewhere it was not.
    if (target == 0f && expand.value > MorphEpsilon) {
        haptics?.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    expand.animateTo(target, PlayerMorphSpec, initialVelocity = velocity)
}

/**
 * Lays content on the frame of one end of the journey.
 *
 * The frame is in the host's own pixels, so this converts once and places
 * absolutely: content belonging to an end has to sit exactly where that end is,
 * not wherever a layout would have put it.
 */
@Composable
fun MorphFrameBox(frame: Rect, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    Box(
        Modifier
            .offset(
                x = with(density) { frame.left.toDp() },
                y = with(density) { frame.top.toDp() },
            )
            .size(
                width = with(density) { frame.width.toDp() },
                height = with(density) { frame.height.toDp() },
            ),
    ) {
        content()
    }
}
