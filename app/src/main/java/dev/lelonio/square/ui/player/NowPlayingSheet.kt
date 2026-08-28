package dev.lelonio.square.ui.player

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassRole
import dev.antigravity.fluidengine.ui.fluidphysics.FluidCornerRadii
import dev.antigravity.fluidengine.ui.fluidphysics.FluidForm
import dev.antigravity.fluidengine.ui.fluidphysics.FluidFormPresets
import dev.antigravity.fluidengine.ui.fluidphysics.FluidPhysicsContentRole
import dev.antigravity.fluidengine.ui.fluidphysics.fluidPhysicsClip
import dev.antigravity.fluidengine.ui.fluidphysics.fluidPhysicsContent
import dev.antigravity.fluidengine.ui.fluidphysics.fluidPhysicsSurface
import dev.antigravity.fluidengine.ui.fluidphysics.rememberFluidPhysicsState
import kotlinx.coroutines.launch

/**
 * The pill becoming the player, as one surface.
 *
 * Every previous version had two things on stage pretending to be one. As a
 * navigation destination it could only cross-fade. As a transform it was better
 * and still two: the pill faded out where it stood while a full-size player
 * faded in over it, and nothing ever travelled between them.
 *
 * Now there is a single pane of glass whose silhouette *is* the journey — a
 * capsule the size of the pill at 0, the whole window at 1 — and the number that
 * shapes it is the same `progress` a finger writes. So there is no "animation"
 * to play and no state to be caught between: every value of that number is a
 * finished picture, which is the only way a gesture can be held halfway and
 * still look right.
 *
 * The hard part is not the surface, it is that the two interfaces are different
 * things. They hand over in three disjoint stretches, and none of them is ever
 * stretched by the shape:
 *
 *  * the **pill's face** leaves on the engine's Outgoing contract and is gone by
 *    a third of the way;
 *  * the **artwork** fills the window edge to edge at every instant, opaque by
 *    just past half — it is what stops two readable layers existing at once, and
 *    what makes it honest for the glass to hold a still sample while it travels;
 *  * the **player's own chrome** arrives last, rising a fraction of the window's
 *    own travel so it climbs *with* the window rather than waiting for it.
 *
 * All three are clipped to the live silhouette, and that clip is the whole
 * reason any of it reads as one object. Deliberately *not* the engine's Incoming
 * contract: its zoom is clamped to a range that leaves a full-page layout
 * narrower than the shape for most of the journey, which shows as two uncovered
 * strips of glass down the sides.
 *
 * Nothing here reads `progress.value` in composition outside a `derivedStateOf`.
 * That rule is the file's oldest scar: read plainly, it recomposed the sheet, the
 * player and the bar on every frame — a 97ms median frame, 250ms at worst.
 */
@Composable
fun NowPlayingSheet(
    progress: Animatable<Float, AnimationVector1D>,
    /** Where the pill is, in the window's own pixels. The journey starts there. */
    pillBounds: Rect,
    /** What the travelling surface refracts: the page it is leaving. */
    backdrop: GlassBackdropState,
    /**
     * The pill's own face, drawn again inside the surface.
     *
     * A copy rather than the pill itself, because the pill belongs to the bar's
     * layout and this has to sit on the journey's own frame. The real one is cut
     * dead the instant the journey starts; this is what the eye follows.
     */
    pillFace: @Composable () -> Unit,
    /**
     * The artwork wash under the player, which is also the window's floor.
     *
     * The player used to be a destination stacked over the app's own backdrop
     * and so had no background of its own. Inside a window that grows, it is the
     * thing that makes the window opaque.
     */
    background: @Composable () -> Unit,
    expandedContent: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val physics = rememberFluidPhysicsState(FluidForm.circle(Offset(1f, 1f), 1f))

    var hostBounds by remember { mutableStateOf(Rect.Zero) }
    // The last place the pill was seen, kept across the journey: the pill itself
    // is cut the moment the journey starts, so its bounds stop being reported
    // exactly when they are needed.
    var lastPill by remember { mutableStateOf(Rect.Zero) }
    if (pillBounds.width > 0f) lastPill = pillBounds

    val ready = lastPill.width > 0f && hostBounds.width > 0f
    val from = remember(lastPill, hostBounds) {
        FluidFormPresets.capsule(lastPill.translate(-hostBounds.left, -hostBounds.top))
    }
    val to = remember(hostBounds) {
        FluidForm.Slab(Rect(0f, 0f, hostBounds.width, hostBounds.height), FluidCornerRadii.Zero)
    }
    // A stable lambda: the drive de-duplicates on its endpoints and ignores this,
    // so handing it a new one every recomposition would silently keep the old.
    val driveProgress = remember { { progress.value } }

    // SideEffect and not LaunchedEffect. A launched effect is dispatched to a
    // coroutine and runs *after* the frame, so the first frame of every journey
    // would find no drive installed and draw the resting shape instead — a two
    // pixel circle in the corner, at full material.
    SideEffect { if (ready) physics.driveExternally(from = from, to = to, progress = driveProgress) }

    val travelling by remember {
        derivedStateOf { progress.value > MorphEpsilon && progress.value < 1f - MorphEpsilon }
    }
    val contentLive by remember { derivedStateOf { progress.value > MorphEpsilon } }
    val pillFaceLive by remember { derivedStateOf { progress.value < PillFaceGate } }
    // True only at the two ends of the travel; see LocalGlassEnabled.
    val settled by remember {
        derivedStateOf { progress.value <= MorphEpsilon || progress.value >= GlassLiveAt }
    }

    // Composed always, so the host's bounds are known from the app's first layout
    // rather than from the first frame of the first gesture — otherwise `to` is
    // an empty rectangle for exactly one frame, which is one frame too many.
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { hostBounds = it.boundsInRoot() },
    ) {
        // The system's back gesture, driving the collapse itself.
        //
        // Not a command that plays an animation afterwards: the finger owns the
        // progress while it is down, so the player leaves at the speed of the
        // hand and comes back if the hand changes its mind. Only part of the
        // travel is given to the preview — a gesture that could finish the
        // journey on its own would leave nothing for the commit to do.
        PredictiveBackHandler(enabled = contentLive) { events ->
            try {
                events.collect { event ->
                    progress.snapTo(1f - BACK_PREVIEW * event.progress.coerceIn(0f, 1f))
                }
                scope.launch { progress.animateTo(0f, PlayerMorphSpec) }
            } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
                // `scope` and not this one, which is being cancelled.
                scope.launch { progress.animateTo(1f, PlayerMorphSpec) }
            }
        }

        if (ready && travelling) {
            // The travelling pane, empty. Exactly the shape of the modal's own
            // window: the glass is one thing and what stands on it is another.
            Box(
                Modifier
                    .fillMaxSize()
                    // The first and last frame of a node can draw with default
                    // properties, which here would be the finished window at
                    // full material for one frame.
                    .drawWithContent { if (progress.value > MorphDrawGate) drawContent() }
                    .fluidPhysicsSurface(
                        state = physics,
                        backdrop = backdrop,
                        tint = GlassDefaults.floatingTint(),
                        role = GlassRole.Floating,
                        // Sampled once for the whole journey, and the intensity
                        // held constant to make that possible: intensity scales
                        // the blur radius, the radius is the capture's padding,
                        // and a padding that changes is the one thing that
                        // dirties a capture. Constant plus sampleOnce is one
                        // recording per journey instead of sixty.
                        sampleOnce = true,
                    ),
            )
        }

        if (contentLive) {
            Box(
                Modifier
                    .fillMaxSize()
                    // Clipped only while there is a silhouette to clip to. At rest
                    // the shape is the window itself, so an outline there is work
                    // for nothing — and before the pill has ever been measured
                    // there is no shape at all, which is the state the app comes
                    // back in when it was left with the player open.
                    .fluidPhysicsClip(physics) { ready && progress.value < 1f - MorphEpsilon },
            ) {
                val pillTop = lastPill.top - hostBounds.top

                // The floor. Fills the window to its edge at every instant: no
                // scale, no translation. Nothing else in this stack is allowed
                // to be opaque, so this is the whole of why the window is not a
                // hole while it grows.
                Box(
                    Modifier
                        .fillMaxSize()
                        .drawWithContent {
                            if (morphFloorAlpha(progress.value) > MorphDrawGate) drawContent()
                        }
                        .graphicsLayer { alpha = morphFloorAlpha(progress.value) },
                ) {
                    background()
                }

                // The page. Rises with the window instead of waiting for it, by
                // a fraction of the window's own travel — which is by
                // construction less than the silhouette's top edge, so it never
                // uncovers the floor beneath it.
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val t = progress.value
                            alpha = morphChromeAlpha(t)
                            translationY = ArrivalRise * morphTop(pillTop, t)
                            val scale = 0.97f + 0.03f * t
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin(0.5f, 1f)
                            // Modulated rather than composited: the default
                            // draws the whole subtree into an offscreen buffer
                            // the size of the window before applying alpha.
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                        },
                ) {
                    CompositionLocalProvider(LocalGlassEnabled provides settled) {
                        expandedContent()
                    }
                }

                // The face that is leaving, over everything: it is the outgoing
                // content, and the floor rising underneath must not veil it.
                if (pillFaceLive) {
                    MorphFrameBox(from.frame) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .fluidPhysicsContent(physics, FluidPhysicsContentRole.Outgoing),
                        ) {
                            pillFace()
                        }
                    }
                }
            }
        }
    }
}

/**
 * How much of the collapse the back gesture may show before it is committed.
 *
 * Half: enough that the page behind is unmistakably there and the gesture is
 * clearly doing something, and not so much that letting go feels like it
 * changed nothing.
 */
private const val BACK_PREVIEW = 0.5f
