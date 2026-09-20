package dev.lelonio.square.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.GlassTint
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
 * The hand-over between the real pill and the travelling glass.
 *
 * Below it the pill in the bar is the real one and nothing of the journey is
 * drawn; above it the pill is cut and the glass is what stands in its place.
 * One number for both halves, and that is the point: it used to be two.
 *
 * The draw gate came first, to stop a flash — a node's first and last frame can
 * draw with default properties, which on a surface whose arrival is an alpha is
 * a flash at full strength, and an empty display list has none to replay. But
 * the pill came back at [MorphEpsilon], eight times smaller, so between the two
 * there was a stretch with the glass already gone and the pill not yet back:
 * the face of the pill drawn over the page with nothing behind it. Invisible
 * when the spring is quick and plain to see at the end of a drag, where the
 * tail of a critically damped spring crawls through exactly that range.
 *
 * So the two are the same number now. Everything that decides between the pill
 * and the journey reads this one: the bar, the face inside the surface, and the
 * surface itself.
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
 * How far a finger has to run, per axis, for one whole journey.
 *
 * The travelling edge and nothing else: upwards it is `pillBounds.top`, to the
 * left it is `pillBounds.left` — the distance that edge covers on its way to
 * zero. Zero on an axis means the surface does not open that way at all, which
 * is how the pill says that sideways is already the gesture for changing track.
 */
@Immutable
data class PlayerMorphTravel(val up: Float, val left: Float = 0f) {
    /** What a movement upwards is worth, in fractions of the journey. */
    fun vertical(dy: Float): Float = if (up > 1f) -dy / up else 0f

    /** And one to the left. Zero when this surface does not open that way. */
    fun horizontal(dx: Float): Float = if (left > 1f) -dx / left else 0f

    /**
     * What a movement is worth along one axis, the other ignored.
     *
     * One axis and not both added together, which is what this did first and is
     * the reason a pull to the left could not finish: the two runs are different
     * lengths, so the drift every sideways gesture carries downwards counted
     * against the journey four times harder than the sideways part counted for
     * it. Past halfway the only way on was to start pulling upwards, which is
     * precisely the gesture the sideways one exists to replace.
     */
    fun along(horizontal: Boolean, dx: Float, dy: Float): Float =
        if (horizontal) horizontal(dx) else vertical(dy)
}

/**
 * No journey is allowed to be shorter than a thumb.
 *
 * Not a preference. `pillBounds.top` on the side panel is the status bar plus
 * twelve points, and a whole journey inside a hundred pixels is not a gesture,
 * it is a touch that slipped — which is also why the full player on a tablet
 * used to close on a hundred pixels of drag. On the pill this never bites: its
 * own top edge is most of the screen, so a phone comes out of here exactly as
 * it went in.
 */
val MinMorphTravel = 220.dp

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
) = dragPlayerMorphBy(expand, -deltaPx / travelPx.coerceAtLeast(1f))

/** The same finger, having already been asked what its movement was worth. */
fun CoroutineScope.dragPlayerMorphBy(
    expand: Animatable<Float, AnimationVector1D>,
    units: Float,
) = launch {
    expand.snapTo(expand.value + units)
}

/**
 * The release, for a gesture that came in more than one direction.
 *
 * The velocity arrives already in journeys per second — projected through the
 * same [PlayerMorphTravel.units] the drag went through — so the sign and the
 * scale are the ones [playerMorphTarget] is written against.
 */
fun CoroutineScope.settlePlayerMorphUnits(
    expand: Animatable<Float, AnimationVector1D>,
    velocityUnitsPerSec: Float,
    haptics: HapticFeedback?,
) = launch {
    val target = playerMorphTarget(expand.value, velocityUnitsPerSec)
    if (target == 0f && expand.value > MorphEpsilon) {
        haptics?.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    expand.animateTo(target, PlayerMorphSpec, initialVelocity = velocityUnitsPerSec)
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
 * What the travelling surface is made of — and the pill, and the bar under it.
 *
 * One function for all three, which is the whole point: the window grows out of
 * the pill, and at the hand-over the two are on screen together for exactly one
 * frame. Sampled when they were two different films they differed by about
 * fifty-five levels of grey, and a step that size in one frame is precisely the
 * join this arrangement exists to hide.
 *
 * Which film follows the side the page is on, and both arms are scrims: the
 * family's own film is built for a flat page, and this pill spends its life over
 * covers.
 *
 * The light arm used to be that family film, on the argument that it lightens
 * anyway and so is already pointing the right way on paper. It is pointing the
 * right way and it does not go far enough — over a dark cover the pill settles
 * at a middling grey with dark letters on it, and over a bright one it vanishes
 * into the page. `lightFloatingTint` is the same white, laid on at the weight a
 * photograph asks for.
 *
 * The dark one used to be written out here by hand, because the engine decided
 * light from dark by the luminance of `colorScheme.surface` and this app files a
 * translucent film in that slot. That is fixed at the root now — see
 * `LocalFluidSurfaceSide`, provided by SquareTheme — so this is a name for a
 * decision rather than a way round a wrong answer.
 *
 * Asks the ink rather than the theme, like its sibling `pageBarTint`, and the
 * two have to pick the same way: the bar, the pill it carries and the window
 * that grows out of that pill are one material, and an app that moves one of the
 * three has built the join the morph exists to hide.
 */
@Composable
fun rememberPillMorphTint(): GlassTint =
    if (!dev.lelonio.square.ui.theme.onDarkPage) {
        dev.antigravity.fluidengine.ui.fluid.GlassDefaults.lightFloatingTint()
    } else {
        dev.antigravity.fluidengine.ui.fluid.GlassDefaults.darkFloatingTint()
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
