package dev.lelonio.square.ui.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.Pause
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.fill.SkipBack
import com.adamglin.phosphoricons.fill.SkipForward
import com.adamglin.phosphoricons.regular.CaretUp
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.LocalFluidCanvasBackdrop
import dev.antigravity.fluidengine.ui.fluid.LocalGlassBackdrop
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassRole
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.glassSurface
import dev.antigravity.fluidengine.ui.fluid.rememberCombinedGlassBackdrop
import dev.antigravity.fluidengine.ui.fluid.rememberEmptyGlassBackdrop
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.lelonio.square.R
import dev.lelonio.square.data.CanvasClip
import dev.lelonio.square.ui.components.Artwork
import dev.lelonio.square.ui.components.RoundGlassButton
import dev.lelonio.square.ui.glass.pressable
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import dev.lelonio.square.ui.theme.SelfPaintedPage
import androidx.compose.ui.res.stringResource
import kotlin.math.roundToInt

/**
 * What is playing, kept on screen beside the page.
 *
 * The pill's job, given the room a wide window has for it. Below nine hundred
 * points the pill is right — it is the most a phone can spare — and above it the
 * same information as a strip forty-eight points tall, on a screen with a
 * thousand to spare, is an app that has not noticed where it is running.
 *
 * It is the *same* surface the window grows out of, not a second one: the panel
 * reports its own rectangle exactly as the pill does, so the morph that turns it
 * into the full player is the one that was already there. Nothing about
 * [NowPlayingSheet] knows which of the two it started from.
 *
 * With a Canvas it becomes the full player in miniature: the clip fills the pane
 * edge to edge and the title, the bar and the three discs of glass stand *on*
 * it, over a scrim, exactly as they do in the window. That is the arrangement
 * the discs were designed for — a control is a lens, and a lens needs something
 * behind it with structure. It also settles what the panel is for: a glance, at
 * the thing a glance is actually drawn to.
 *
 * Without one it keeps the quieter shape it has always had — the cover inset in
 * the pane with the controls under it — and the two are one spring apart.
 *
 * Still deliberately not a second player. The queue, the lyrics, the effects and
 * the credits all live in the full window.
 */
@UnstableApi
@Composable
fun NowPlayingPanel(
    state: PlaybackState,
    positionMs: State<Long>,
    /** The device the music is coming out of, when it is not this one. */
    playingOn: String?,
    modifier: Modifier = Modifier,
    /**
     * False for the copy that rides inside the travelling surface.
     *
     * Three more gestures on the axis the journey is being dragged along is not
     * a detail: the copy is a picture, and the real panel is one frame away.
     */
    interactive: Boolean = true,
    /** The track's Canvas, or null when it has none. Always null on the copy. */
    canvas: CanvasClip? = null,
    /**
     * False from the first pixel of the journey.
     *
     * A `State` and not a `Boolean` on purpose: read plainly at the call site
     * this would recompose the whole app twice a journey, at the first frame of
     * a drag. Handed over unread, only this panel recomposes.
     *
     * What it gates is a second decoder. The panel stays composed while the
     * player is open — it is cut by an alpha, not by composition — so without
     * this, opening the player on a track with a Canvas would run two ExoPlayers
     * and two downloads of the same clip, which is the trap CanvasSurface's own
     * note describes for audio.
     */
    canvasLive: State<Boolean> = remember { mutableStateOf(false) },
    /**
     * The page behind the panel, which is what the glass in it refracts.
     *
     * The same record the panel's own pane samples. Null on the travelling copy,
     * which is given an empty one instead; see [PanelGlass].
     */
    backdrop: GlassBackdropState? = null,
    onOpen: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
) {
    // Only ever the real panel, standing still.
    val clip = canvas?.takeIf { interactive && canvasLive.value }

    var canvasReady by remember(clip?.url) { mutableStateOf(false) }
    var clipRatio by remember(clip?.url) { mutableStateOf<Float?>(null) }

    // Held at zero until there is a picture, then faded up. Between prepare()
    // and the first decoded frame a TextureView is empty, and the cover has to
    // stand there until it is not.
    val clipAlpha by animateFloatAsState(
        targetValue = if (canvasReady) 1f else 0f,
        animationSpec = tween(420),
        label = "panel-clip",
    )

    // How far along the panel is to being a picture with controls on it.
    //
    // It starts moving the moment a Canvas is *known about* rather than when one
    // decodes: the growth and the picture are two events, and holding the first
    // for the second makes the panel jump with the video already playing.
    val picture = animateFloatAsState(
        targetValue = if (clip != null) 1f else 0f,
        // Critically damped, and the family's own sheet spring: the whole pane
        // changes height with this, and a panel that bounces reads as loose
        // layout. Same choice and same reason as PlayerMorphSpec.
        animationSpec = spring(
            dampingRatio = FluidMotion.DampingChrome,
            stiffness = FluidMotion.ResponseSmooth,
        ),
        label = "panel-picture",
    )

    // Width over height. Nine by sixteen is what a Canvas is, until the decoder
    // says otherwise — which it usually does before the first frame arrives, so
    // the pane is already the right shape by the time there is anything in it.
    val shape = rememberUpdatedState(clipRatio ?: CanvasPortrait)

    // The picture behind the controls, recorded before them.
    //
    // This is the whole answer to "the buttons are not glass". A control is a
    // lens: over a flat wash it has nothing to bend and comes out as a pale
    // disc, which is exactly what the app's root ground — the blurred sleeve —
    // gave them. Combined, the page is what they read when there is no clip and
    // the clip is what they read when there is, and both have structure.
    val empty = rememberEmptyGlassBackdrop()
    val page = backdrop ?: empty
    val stage = rememberGlassBackdrop()
    val controlGlass = rememberCombinedGlassBackdrop(page, stage)

    // The side the panel is read on. A boolean, like the full player's own
    // `canvasVisible`, and turned at the same moment: when there is a picture,
    // not when one is expected.
    val onPicture = clip != null && canvasReady

    PanelGlass(page = page, controls = controlGlass, inert = backdrop == null) {
        PanelSide(onPicture) {
            PanelFrame(
                modifier = modifier,
                // What the picture asks the pane to be. Zero asks for nothing,
                // and the frame then measures to its contents as it always did.
                pictureHeight = { width ->
                    picture.value * (width / shape.value.coerceIn(MinPaneRatio, MaxPaneRatio))
                },
                picture = {
                    if (picture.value > 0.001f || clip != null) {
                        PanelPicture(
                            state = state,
                            clip = clip,
                            clipAlpha = clipAlpha,
                            amount = picture,
                            stage = stage,
                            onFirstFrame = { canvasReady = true },
                            onAspectRatio = { clipRatio = it },
                            onStillReady = {
                                clipRatio = it
                                canvasReady = true
                            },
                        )
                    }
                },
                header = {
                    // The way into the full player, said with a word rather than
                    // left to be discovered: the panel is a surface you can
                    // press, and nothing about a cover suggests that.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .then(
                                if (interactive) {
                                    Modifier.pressable(onOpen, pressedScale = 0.98f)
                                } else {
                                    Modifier
                                },
                            )
                            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = playingOn ?: stringResource(R.string.now_playing),
                            style = MaterialTheme.typography.labelLarge,
                            color = InkDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            PhosphorIcons.Regular.CaretUp,
                            contentDescription = null,
                            tint = InkDim,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                },
                content = {
                    Column(
                        Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // The quiet shape, folded away as the picture takes over.
                        // Collapsed rather than removed so the two arrangements
                        // are one spring apart instead of a cut.
                        InsetCover(
                            state = state,
                            interactive = interactive,
                            onOpen = onOpen,
                            amount = picture,
                        )

                        // The name, on its own pane once there is a clip.
                        //
                        // A canvas is graded for itself and some are near-white
                        // in the very place a title sits; ink alone cannot be
                        // made to survive all of them, and a scrim heavy enough
                        // to try turns the video into a mood board. A small
                        // pane of the same glass as the discs is the answer the
                        // full player already gives, at the same size.
                        Box(Modifier.fillMaxWidth()) {
                            if (clip != null) {
                                Box(
                                    Modifier
                                        .matchParentSize()
                                        .graphicsLayer {
                                            alpha = picture.value.coerceIn(0f, 1f)
                                        }
                                        .glassSurface(
                                            state = controlGlass,
                                            tint = GlassDefaults.floatingTintOnPhoto(),
                                            shape = ContinuousCornerShape(FluidRadius.Card),
                                            role = GlassRole.Floating,
                                        ),
                                )
                            }
                            Column(
                                // Arrives with the pane rather than a frame
                                // before it: read in the measure pass, so the
                                // inset is part of the same spring instead of a
                                // step the eye catches.
                                Modifier.insetBy(14.dp, 10.dp) { picture.value },
                            ) {
                                Text(
                                    text = state.title,
                                    style = MaterialTheme.typography.titleLarge,
                                    color = Ink,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    text = state.artist,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = InkDim,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                                )
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        LinearProgressIndicator(
                            progress = { progressOf(positionMs.value, state.durationMs) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = Ink.copy(alpha = 0.18f),
                            drawStopIndicator = {},
                        )

                        Spacer(Modifier.height(12.dp))

                        // The player's transport, at the panel's scale.
                        //
                        // The same discs of engine glass, three quarters the
                        // size: the panel has three hundred points between its
                        // gutters against a phone's four hundred, and the ratio
                        // between the two sizes is the ratio between the two
                        // widths.
                        //
                        // Spaced rather than spread: 48 + 16 + 58 + 16 + 48 reads
                        // as one group in the middle of the panel, where evenly
                        // spread pushes the skips against the gutter and their
                        // rims into the panel's own.
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(
                                16.dp,
                                Alignment.CenterHorizontally,
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RoundGlassButton(
                                size = 48.dp,
                                enabled = state.hasPrevious,
                                isInteractive = interactive,
                                onClick = onPrevious,
                            ) {
                                Icon(
                                    PhosphorIcons.Fill.SkipBack,
                                    contentDescription = stringResource(R.string.previous),
                                    // The dimming belongs to the button, not to
                                    // the glyph: asking for it twice is how a
                                    // disabled control goes from quiet to gone.
                                    tint = Ink,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                            RoundGlassButton(
                                size = 58.dp,
                                isInteractive = interactive,
                                onClick = onTogglePlay,
                            ) {
                                Icon(
                                    if (state.isPlaying) {
                                        PhosphorIcons.Fill.Pause
                                    } else {
                                        PhosphorIcons.Fill.Play
                                    },
                                    contentDescription = stringResource(
                                        if (state.isPlaying) R.string.pause else R.string.play,
                                    ),
                                    tint = Ink,
                                    modifier = Modifier.size(26.dp),
                                )
                            }
                            RoundGlassButton(
                                size = 48.dp,
                                enabled = state.hasNext,
                                isInteractive = interactive,
                                onClick = onNext,
                            ) {
                                Icon(
                                    PhosphorIcons.Fill.SkipForward,
                                    contentDescription = stringResource(R.string.next),
                                    tint = Ink,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                    }
                },
            )
        }
    }
}

/**
 * The clip, the cover under it, and the scrim that makes letters possible on top.
 *
 * Recorded into [stage] so the discs below have it to bend. The recording is of
 * this node only — the controls are somewhere else entirely, so nothing that
 * samples it can be inside it.
 */
@UnstableApi
@Composable
private fun PanelPicture(
    state: PlaybackState,
    clip: CanvasClip?,
    clipAlpha: Float,
    amount: State<Float>,
    stage: dev.antigravity.fluidengine.ui.fluid.GlassBackdropState,
    onFirstFrame: () -> Unit,
    onAspectRatio: (Float) -> Unit,
    onStillReady: (Float) -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = amount.value.coerceIn(0f, 1f) }
            .glassBackdropSource(stage),
    ) {
        // Underneath, always. It is the floor until the clip has a picture, and
        // it is what shows while the pane is still changing shape.
        Artwork(
            url = state.artworkUrl,
            title = state.title,
            modifier = Modifier.fillMaxSize(),
            // The pane carries the corner; two clips of one radius are an
            // outline a frame for nothing.
            corner = 0.dp,
        )

        when {
            clip == null -> Unit

            clip.isVideo -> CanvasSurface(
                url = clip.url,
                isPlaying = state.isPlaying,
                onFirstFrame = onFirstFrame,
                onAspectRatio = onAspectRatio,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = clipAlpha },
            )

            // A handful of canvases are stills rather than clips. They shape the
            // pane the same way, from their own figure rather than a decoder's.
            else -> AsyncImage(
                model = clip.url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onSuccess = { success ->
                    val drawable = success.result.drawable
                    val width = drawable.intrinsicWidth
                    val height = drawable.intrinsicHeight
                    onStillReady(if (width > 0 && height > 0) width.toFloat() / height else 1f)
                },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = clipAlpha },
            )
        }

        // Deliberately light, and only where the letters are. Clips are graded
        // for their own sake and some are near-white; this buys the title and
        // the transport their contrast without turning the video into a mood
        // board. The band at the top is for the one line above it.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.30f),
                        0.22f to Color.Transparent,
                        0.52f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.64f),
                    ),
                ),
        )
    }
}

/**
 * The cover in its own square, for the tracks with no Canvas.
 *
 * Folded rather than dropped: the height is scaled by how far the picture behind
 * has arrived, so the quiet arrangement and the loud one are one spring apart.
 */
@Composable
private fun InsetCover(
    state: PlaybackState,
    interactive: Boolean,
    onOpen: () -> Unit,
    amount: State<Float>,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .then(
                if (interactive) {
                    Modifier.pressable(onOpen, pressedScale = 0.97f)
                } else {
                    Modifier
                },
            )
            .graphicsLayer { alpha = (1f - amount.value * 1.6f).coerceIn(0f, 1f) }
            .clip(RoundedCornerShape(18.dp))
            .folding { 1f - amount.value },
    ) {
        Crossfade(
            targetState = state.artworkUrl to state.title,
            animationSpec = tween(320),
            label = "panel-art",
        ) { (url, title) ->
            Artwork(
                url = url,
                title = title,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                corner = 0.dp,
            )
        }
    }
}

/**
 * Reports a fraction of the height it measured, and keeps the rest.
 *
 * Read in the measure pass rather than in composition, so a pane folding away
 * costs a re-layout of eight nodes and not sixty recompositions of the app.
 */
private fun Modifier.folding(fraction: () -> Float) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val shown = (placeable.height * fraction().coerceIn(0f, 1f)).roundToInt()
    // The spacer that used to sit under the cover folds with it.
    val gap = (PanelCoverGap.roundToPx() * fraction().coerceIn(0f, 1f)).roundToInt()
    layout(placeable.width, shown + gap) { placeable.place(0, 0) }
}

/**
 * Grows an element's margin from nothing to [horizontal] by [vertical].
 *
 * Read in the measure pass rather than in composition, so the inset travels on
 * the same spring as everything else the picture is moving, and costs a
 * re-layout of a handful of nodes instead of a recomposition per frame.
 */
private fun Modifier.insetBy(
    horizontal: androidx.compose.ui.unit.Dp,
    vertical: androidx.compose.ui.unit.Dp,
    fraction: () -> Float,
) = layout { measurable, constraints ->
    val amount = fraction().coerceIn(0f, 1f)
    val side = (horizontal.roundToPx() * amount).roundToInt()
    val ends = (vertical.roundToPx() * amount).roundToInt()
    val placeable = measurable.measure(constraints.offset(-2 * side, -2 * ends))
    layout(placeable.width + 2 * side, placeable.height + 2 * ends) {
        placeable.place(side, ends)
    }
}

/**
 * The pane: a picture behind, a line at the top, and the controls at the bottom.
 *
 * A layout rather than a Box because the picture has to be as tall as the *pane*
 * and the pane as tall as the picture wants — which a Box cannot say, since
 * `matchParentSize` is resolved from a size the other children already decided.
 * One pass, no subcomposition: the height is a number read in measure.
 */
@Composable
private fun PanelFrame(
    modifier: Modifier,
    /** What the picture asks for, in pixels, given the pane's width. Zero asks for nothing. */
    pictureHeight: (Int) -> Float,
    picture: @Composable () -> Unit,
    header: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Layout(
        contents = listOf(picture, header, content),
        modifier = modifier,
    ) { (pictures, headers, contents), constraints ->
        val loose = constraints.copy(minHeight = 0)
        val head = headers.first().measure(loose)
        val body = contents.first().measure(loose)
        val width = constraints.constrainWidth(maxOf(head.width, body.width))
        val asked = pictureHeight(width).roundToInt()
        val height = constraints.constrainHeight(maxOf(head.height + body.height, asked))
        val behind = pictures.firstOrNull()?.measure(Constraints.fixed(width, height))
        layout(width, height) {
            behind?.place(0, 0)
            head.place(0, 0)
            // Pinned to the floor, so the picture grows into the space above it
            // rather than pushing the transport off the bottom of the window.
            body.place(0, height - body.height)
        }
    }
}

/**
 * Whatever the glass in the panel refracts, and nothing at all on the copy.
 *
 * The copy rides inside `fluidPhysicsContent`, which is a layer being scaled and
 * faded — and a glass surface inside a scaled layer scales the backdrop it
 * samples with it, so the page *behind* the panel would slide while the panel
 * travelled. An empty backdrop keeps the film, the rim and the shadow, which is
 * the whole of the silhouette, and drops only the refraction: invisible over the
 * third of a second the copy exists, and exact where it matters.
 *
 * Not `flat`, which is the other thing it looks like it should be: that sets the
 * blur radius to zero and the surface still photographs what is underneath.
 */
@Composable
private fun PanelGlass(
    page: GlassBackdropState,
    controls: GlassBackdropState,
    inert: Boolean,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        // Two locals and not one: the canvas one is what a control standing
        // *inside* a page reads — every engine control prefers it — and the
        // other is what chrome floating *over* one reads. Providing half of the
        // pair is how a panel ends up with its letters on one side and its
        // buttons on the other. See the note in PlayerScreen.
        LocalFluidCanvasBackdrop provides if (inert) page else controls,
        LocalGlassBackdrop provides page,
        content = content,
    )
}

/**
 * The side the panel is read on, once there is a picture under it.
 *
 * The one door for a page that paints its own ground; see `SelfPaintedPage`. It
 * carries the ink, the palette and `LocalOnPicture` together — and that last one
 * is what gives the discs the denser film a photograph needs, instead of the
 * third of an alpha that is right on a bar and invisible on a video.
 */
@Composable
private fun PanelSide(onPicture: Boolean, content: @Composable () -> Unit) {
    if (!onPicture) {
        content()
        return
    }
    SelfPaintedPage(ground = CanvasGround, content = content)
}

/**
 * How wide the panel is.
 *
 * Three hundred and forty, which is a phone's width less its gutters: the cover
 * in it is then about the size of the one on a phone's player, and the title
 * under it breaks where a title is used to breaking. Wider and it starts taking
 * room from the page for a picture nobody asked to be bigger.
 */
val NowPlayingPanelWidth = 340.dp

/** Below this the panel does not fit beside a page worth reading. See [NowPlayingPanel]. */
val NowPlayingPanelMinWindow = 900.dp

/** Nine by sixteen: what a Canvas is, until a decoder says otherwise. */
private const val CanvasPortrait = 9f / 16f

/**
 * How far a clip is allowed to talk the pane out of its shape.
 *
 * Never wider than tall, which is the floor and the one that matters: a
 * landscape clip asked for a pane two hundred points high, which is *shorter*
 * than the quiet arrangement it replaces — so a track with a wide Canvas made
 * the panel shrink, next to a page that had not moved. Cropped into a square it
 * reads as the same panel with a video in it, which is what it is.
 *
 * And not taller than two and a half times its width, for the clip that reports
 * something absurd. The window's own height caps it long before that on a
 * tablet; this is for the one that does not.
 */
private const val MinPaneRatio = 0.4f
private const val MaxPaneRatio = 1f

/** The room under the inset cover, folded away with it. */
private val PanelCoverGap = 18.dp

/**
 * What a clip under the scrim amounts to, for the purpose of picking a side.
 *
 * Not true black — the pane still has a film on it and a video under that — but
 * far enough down that the ink derived from it is the light one, which is the
 * whole question being asked.
 */
private val CanvasGround = Color(0xFF121212)
