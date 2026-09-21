package dev.lelonio.square.ui.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowsInSimple
import com.adamglin.phosphoricons.regular.ArrowsOutSimple
import com.adamglin.phosphoricons.regular.CaretUp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassRole
import dev.antigravity.fluidengine.ui.fluid.LocalFluidCanvasBackdrop
import dev.antigravity.fluidengine.ui.fluid.LocalGlassBackdrop
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
 * And it has three sizes, one number apart — [reach], see [PanelGeometry].
 * Beside the page it is the panel described above. Pulled up, or a little to
 * the left, it grows to the height of the page and the transport grows with it
 * to the player's own: the seek bar gets its times, the discs their size, the
 * toggles their place. Pulled further, or asked with the button at its top
 * left, the words and the queue open out to its left. Past that the window
 * takes over, as it always did. Every one of those is the same panel measured
 * at a different number, read in layout; nothing here is swapped for anything.
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
     * The words, for the one line of them the panel shows.
     *
     * Handed to the travelling copy as well, unlike the clip and the backdrop:
     * those two are pictures that break while a layer is being scaled, and this
     * is a string. A line that vanished for the length of the journey and came
     * back at the other end is the one thing the morph is there to avoid.
     */
    lyrics: dev.lelonio.square.data.Lyrics? = null,
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
    /**
     * How big the panel is, 0 to 2. A `State`, read in measure and draw only:
     * it moves with a finger, and read in composition it would recompose the
     * panel — and the copy of it inside the morph — on every frame of a pull.
     */
    reach: State<Float> = remember { mutableStateOf(0f) },
    /** The sizes that number moves between, for this window. */
    geometry: PanelGeometry = PanelGeometry.rest(LocalDensity.current),
    /**
     * The clip's width over its height, once the decoder has said.
     *
     * Told to the caller rather than kept: the width of the tall pane follows
     * the clip's shape, and that width is a fact the page and the bar have to
     * agree with the panel about. One number, held where all three can read it.
     */
    onClipRatio: (Float) -> Unit = {},
    /**
     * The words and the queue, opened out to the left at the third size.
     *
     * Composed by the caller, so the real panel can hold the live lists and the
     * travelling copy a still of them. Null where the window has no room.
     */
    extension: (@Composable () -> Unit)? = null,
    onOpen: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit = {},
    onToggleShuffle: () -> Unit = {},
    onCycleRepeat: () -> Unit = {},
    /** The button at the top left: opens the extension, or closes it. */
    onToggleWide: () -> Unit = {},
) {
    // Only ever the real panel, standing still.
    val clip = canvas?.takeIf { interactive && canvasLive.value }

    var canvasReady by remember(clip?.url) { mutableStateOf(false) }
    var clipRatio by remember(clip?.url) { mutableStateOf<Float?>(null) }
    val reportRatio = rememberUpdatedState(onClipRatio)

    // Held at zero until there is a picture, then faded up. Between prepare()
    // and the first decoded frame a TextureView is empty, and the cover has to
    // stand there until it is not.
    val clipAlpha by animateFloatAsState(
        targetValue = if (canvasReady) 1f else 0f,
        animationSpec = tween(420),
        label = "panel-clip",
    )

    // How far along the panel is to having a clip at all.
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

    // The geometry, through a state, so the lambdas below survive it changing
    // without being rebuilt.
    val geo = rememberUpdatedState(geometry)

    // The three numbers everything below is laid out by, as lambdas read in
    // measure and draw.
    //
    // `pictureShare` is how much of the clip is a *picture* — the pane filled
    // edge to edge with the controls standing on it. `inset` is how much of it
    // is a *cover* instead, sitting in the cover's place with the controls
    // under it. A clip that fits the tall pane is a picture at every size; one
    // too wide for it goes from the first to the second as the panel grows,
    // and the two sum to how much of a clip there is at all.
    val pictureShare = remember { { picture.value * geo.value.pictureShare(reach.value) } }
    val inset = remember { { 1f - geo.value.pictureShare(reach.value) } }
    val pictureAmount = remember { { picture.value } }
    val reach01 = remember { { geo.value.reach01(reach.value) } }
    val ext01 = remember { { geo.value.ext01(reach.value) } }

    // For the one lambda below that has to turn points into pixels.
    val density = LocalDensity.current
    val headerRoomPx = with(density) { (WideButton + WideButtonGap).toPx() }

    // The two facts composition needs, derived so they flip twice a journey
    // instead of sixty times a second: whether the controls the tall panel adds
    // can be touched yet, and which way the button at the top left points.
    val tall by remember { derivedStateOf { reach.value > 0.5f } }
    val wide by remember { derivedStateOf { reach.value > 1.5f } }

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

    // The side is the column's, not the panel's.
    //
    // A clip turns the column into a dark page, and the header, the cover, the
    // names and the transport standing on it are read on that side. The words
    // and the queue beside it are not on the clip: they stand on the panel's
    // own glass, over the page, and are read on the page's side like every
    // other pane over it. So each column slot is wrapped on its own, and the
    // extension and the button at the corner are left on the app's side.
    val side: @Composable (@Composable () -> Unit) -> Unit = { content ->
        PanelSide(onPicture, content)
    }

    PanelGlass(page = page, controls = controlGlass, inert = backdrop == null) {
            PanelFrame(
                modifier = modifier,
                geometry = geo,
                reach = reach,
                pictureShare = pictureShare,
                inset = inset,
                pictureAmount = pictureAmount,
                // What the picture asks the pane to be at rest. Zero asks for
                // nothing, and the frame then measures to its contents as it
                // always did.
                pictureRatio = { shape.value.coerceIn(MinPaneRatio, MaxPaneRatio) },
                // The shape of the cover's place: a square for a cover, the
                // clip's own for a clip inset in it.
                coverRatio = { shape.value.coerceIn(MinInsetRatio, MaxInsetRatio) },
                hasPicture = picture.value > 0.001f || clip != null,
                wash = {
                    // A wash under the words and the queue, on the page's side.
                    //
                    // The panel's own film is tuned for a cover and a title
                    // over a page; a column of lyrics over a shelf of bright
                    // covers wants more of the page taken away than that, and
                    // this is the same wash a record's header lays over its
                    // picture for the same reason.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(dev.lelonio.square.ui.theme.pageWash(ExtensionWash)),
                    )
                },
                extension = {
                    if (extension != null && geometry.hasWide) {
                        Box(Modifier.extensionReveal(geometry.extension, ext01)) {
                            extension()
                        }
                    }
                },
                cover = {
                    // The quiet shape, folded away as the picture takes over.
                    // Collapsed rather than removed so the two arrangements
                    // are one spring apart instead of a cut.
                    side {
                        InsetCover(
                            state = state,
                            interactive = interactive,
                            onOpen = onOpen,
                            amount = picture,
                        )
                    }
                },
                picture = {
                    if (picture.value > 0.001f || clip != null) {
                        side {
                        PanelPicture(
                            state = state,
                            clip = clip,
                            clipAlpha = clipAlpha,
                            amount = picture,
                            scrim = pictureShare,
                            stage = stage,
                            onFirstFrame = { canvasReady = true },
                            onAspectRatio = {
                                clipRatio = it
                                reportRatio.value(it)
                            },
                            onStillReady = {
                                clipRatio = it
                                reportRatio.value(it)
                                canvasReady = true
                            },
                        )
                        }
                    }
                },
                header = {
                    // The way into the full player, said with a word rather than
                    // left to be discovered: the panel is a surface you can
                    // press, and nothing about a cover suggests that.
                    side {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            // Room for the button at the top left while it is
                            // in the column, and none once it has gone out to
                            // the extension's corner; see PanelFrame.
                            .startInset {
                                val ext = geo.value.extPx(reach.value)
                                (headerRoomPx - ext).coerceAtLeast(0f) * reach01()
                            }
                            .then(
                                if (interactive) {
                                    Modifier.pressable(onOpen, pressedScale = 0.98f)
                                } else {
                                    Modifier
                                },
                            )
                            .padding(start = PanelGutter, end = PanelGutter, top = HeaderTop, bottom = HeaderBottom),
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
                    }
                },
                wideButton = {
                    if (geometry.hasWide) {
                        // Only an icon. It says what it does by pointing, and
                        // a word beside it would be the one label in a panel
                        // that has none.
                        RoundGlassButton(
                            size = WideButton,
                            isInteractive = interactive && tall,
                            onClick = onToggleWide,
                        ) {
                            Crossfade(wide, animationSpec = tween(160), label = "panel-wide") { open ->
                                Icon(
                                    if (open) PhosphorIcons.Regular.ArrowsInSimple
                                    else PhosphorIcons.Regular.ArrowsOutSimple,
                                    contentDescription = stringResource(
                                        if (open) R.string.player_panel_narrow
                                        else R.string.player_panel_widen,
                                    ),
                                    tint = Ink,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                },
                body = {
                    side {
                    Column(
                        Modifier.padding(start = PanelGutter, end = PanelGutter, bottom = PanelGutter),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
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
                                            alpha = pictureShare().coerceIn(0f, 1f)
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
                                Modifier.insetBy(14.dp, 10.dp, pictureShare),
                            ) {
                                // Above the title, as in the window: the panel
                                // is the player in miniature, and the order
                                // things are read in is part of being the same
                                // screen. Inside this pane rather than on one
                                // of its own — the glass that was put here for
                                // a title on a clip is the glass a lyric on a
                                // clip needs.
                                // Folded away as the words open out beside it:
                                // the line being sung is lit in the middle of
                                // them, and saying it twice is noise.
                                SungLineLabel(
                                    lyrics = lyrics,
                                    positionMs = positionMs,
                                    isPlaying = state.isPlaying,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .foldHeight { 1f - ext01() },
                                )
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

                        // The bar: a hairline beside the page, the player's own
                        // seek bar once there is room, and its times folded out
                        // under it. One bar, one number.
                        GlassProgressBar(
                            positionMs = positionMs,
                            durationMs = state.durationMs,
                            onSeek = onSeek,
                            accentColor = MaterialTheme.colorScheme.primary,
                            trackColor = Ink.copy(alpha = 0.18f),
                            amount = reach01,
                            interactive = interactive && tall,
                        )
                        Box(Modifier.fillMaxWidth().foldHeight(reach01)) {
                            TimeRow(positionMs, state.durationMs)
                        }

                        Spacer(Modifier.height(12.dp))

                        // The player's transport, at the panel's scale — and
                        // at the player's, as the panel grows. Same discs of
                        // engine glass, one row, measured at whatever size the
                        // number says; see Controls.
                        Controls(
                            state = state,
                            backdrop = null,
                            onTogglePlay = onTogglePlay,
                            onNext = onNext,
                            onPrevious = onPrevious,
                            onToggleShuffle = onToggleShuffle,
                            onCycleRepeat = onCycleRepeat,
                            amount = reach01,
                            isInteractive = interactive,
                            togglesEnabled = tall,
                        )
                    }
                    }
                },
            )
    }
}

/**
 * The clip, the cover under it, and the scrim that makes letters possible on top.
 *
 * Recorded into [stage] so the discs below have it to bend. The recording is of
 * this node only — the controls are somewhere else entirely, so nothing that
 * samples it can be inside it.
 *
 * The scrim is only for the picture arrangement, where the controls stand on
 * the clip: inset in the cover's place the clip is a cover, and a cover has no
 * letters on it. [scrim] is how much of the first this is.
 */
@UnstableApi
@Composable
private fun PanelPicture(
    state: PlaybackState,
    clip: CanvasClip?,
    clipAlpha: Float,
    amount: State<Float>,
    scrim: () -> Float,
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
                .graphicsLayer { alpha = scrim().coerceIn(0f, 1f) }
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
 * The cover in its own place, for the tracks with no Canvas.
 *
 * Faded rather than dropped as a clip arrives, and its place in the frame is
 * folded with it: the quiet arrangement and the loud one are one spring apart.
 * The frame decides its size — see [PanelFrame] — so this is only the picture
 * and the press.
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
            .fillMaxSize()
            .then(
                if (interactive) {
                    Modifier.pressable(onOpen, pressedScale = 0.97f)
                } else {
                    Modifier
                },
            )
            .graphicsLayer { alpha = (1f - amount.value * 1.6f).coerceIn(0f, 1f) }
            .clip(ContinuousCornerShape(PanelPictureRadius)),
    ) {
        Crossfade(
            targetState = state.artworkUrl to state.title,
            animationSpec = tween(320),
            label = "panel-art",
        ) { (url, title) ->
            Artwork(
                url = url,
                title = title,
                modifier = Modifier.fillMaxSize(),
                corner = 0.dp,
            )
        }
    }
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
 * The extension's content, measured at its full width and uncovered by the edge.
 *
 * Laid out whole from the first pixel, and placed so that it travels a third
 * as fast as the edge that is revealing it: it arrives with the edge rather
 * than either being pinned under it or standing still while the glass slides
 * off. Clipped to the room it has been given, because the panel's glass is
 * transparent and a column of words at full width would show through the
 * column beside it.
 */
private fun Modifier.extensionReveal(fullWidthPx: Float, amount: () -> Float): Modifier = this
    .clipToBounds()
    .layout { measurable, constraints ->
        val full = fullWidthPx.roundToInt().coerceAtLeast(constraints.maxWidth)
        val placeable = measurable.measure(Constraints.fixed(full, constraints.maxHeight))
        layout(constraints.maxWidth, constraints.maxHeight) {
            val x = ((constraints.maxWidth - full) * ExtensionParallax).roundToInt()
            placeable.placeWithLayer(x, 0) {
                alpha = (amount() / ExtensionFadeIn).coerceIn(0f, 1f)
            }
        }
    }

/**
 * The pane: a picture behind, a line at the top, a cover in the middle, and
 * the controls at the bottom — and beside it, once there is room, the words.
 *
 * A layout rather than a Box because the picture has to be as tall as the *pane*
 * and the pane as tall as the picture wants — which a Box cannot say, since
 * `matchParentSize` is resolved from a size the other children already decided.
 * One pass, no subcomposition: every number is read in measure.
 *
 * Three things it decides, all from [reach]:
 *
 * - **Its own size.** The column is as wide as the geometry says for this
 *   reach; the extension takes the rest. The height is its contents' at rest
 *   and the whole box it was given at the second size, and every height
 *   between is the same number.
 * - **The cover's place.** Between the header and the body there is a
 *   rectangle: at rest it is the square the cover has always had, grown, it is
 *   whatever room is left, and the cover takes it. With a clip that is a
 *   picture the place folds to nothing; with a clip inset instead of a cover
 *   the place takes the clip's own shape.
 * - **Where the picture is.** Edge to edge with the controls on it, or exactly
 *   in the cover's place with the controls under it, or on its way between the
 *   two — it is the same node, moved and clipped in place, so the one decoder
 *   there is never has to be handed from one slot to another.
 */
@Composable
private fun PanelFrame(
    modifier: Modifier,
    geometry: State<PanelGeometry>,
    reach: State<Float>,
    /** How much of the clip is a picture behind the controls, 0..1. */
    pictureShare: () -> Float,
    /** How far the picture has gone from the pane to the cover's place, 0..1. */
    inset: () -> Float,
    /** How much of a clip there is at all, 0..1: the spring that follows one arriving or leaving. */
    pictureAmount: () -> Float,
    /** The clip's width over its height, for what the picture asks the pane to be. */
    pictureRatio: () -> Float,
    /** The shape of the cover's place, width over height. */
    coverRatio: () -> Float,
    /** Whether there is a picture node at all. */
    hasPicture: Boolean,
    wash: @Composable () -> Unit,
    extension: @Composable () -> Unit,
    cover: @Composable () -> Unit,
    picture: @Composable () -> Unit,
    header: @Composable () -> Unit,
    body: @Composable () -> Unit,
    wideButton: @Composable () -> Unit,
) {
    Layout(
        contents = listOf(wash, extension, cover, picture, header, body, wideButton),
        modifier = modifier,
    ) { slots, constraints ->
        val washes = slots[0]
        val extensions = slots[1]
        val covers = slots[2]
        val pictures = slots[3]
        val headers = slots[4]
        val bodies = slots[5]
        val buttons = slots[6]
        val g = geometry.value
        val t = reach.value
        val r01 = g.reach01(t)
        val e01 = g.ext01(t)
        val ext = g.extPx(t).roundToInt().coerceAtMost(constraints.maxWidth)
        val colW = g.columnWidthPx(t).roundToInt()
            .coerceIn(0, (constraints.maxWidth - ext).coerceAtLeast(0))
        val width = constraints.constrainWidth(colW + ext)
        val gutter = PanelGutter.roundToPx()

        val column = Constraints(minWidth = colW, maxWidth = colW, minHeight = 0, maxHeight = constraints.maxHeight)
        val head = headers.first().measure(column)
        val bodyP = bodies.first().measure(column)

        // How much of the clip is a picture, how much a cover.
        val pic = pictureShare().coerceIn(0f, 1f)
        val k = inset().coerceIn(0f, 1f)

        // The cover's place at rest: the width between the gutters, in the
        // shape it has — square, or the clip's, and anything between.
        val coverMaxW = (colW - 2 * gutter).coerceAtLeast(0)
        // The clip's shape only as far as there is a clip, and only as far as
        // it is a cover: a clip leaving hands the place back to the square.
        val aspect = lerpPx(1f, coverRatio(), if (hasPicture) k * pictureAmount().coerceIn(0f, 1f) else 0f)
            .coerceAtLeast(0.01f)
        val coverNaturalH = (coverMaxW / aspect).roundToInt()
        val gap = (PanelCoverGap.roundToPx() * (1f - pic)).roundToInt()

        // What the picture asks the pane to be at rest, and what the contents
        // add up to; then the height, which is either of those at rest and the
        // whole box once grown.
        val asked = (pic * colW / pictureRatio()).roundToInt()
        val natural = maxOf(head.height + (coverNaturalH * (1f - pic)).roundToInt() + gap + bodyP.height, asked)
        val bounded = constraints.maxHeight != Constraints.Infinity
        val height = constraints.constrainHeight(
            if (bounded) lerpPx(natural.toFloat(), constraints.maxHeight.toFloat(), r01).roundToInt() else natural,
        )

        // The cover's place: what is left between the header and the body,
        // never wider than the gutters allow, centred in any slack.
        val room = (height - head.height - bodyP.height - gap).coerceAtLeast(0)
        val coverW = minOf(coverMaxW, (room * aspect).roundToInt()).coerceAtLeast(0)
        val coverH = (coverW / aspect).roundToInt().coerceAtMost(room)
        val coverX = ext + gutter + (coverMaxW - coverW) / 2
        val coverY = head.height + (room - coverH) / 2
        val coverP = covers.firstOrNull()?.measure(Constraints.fixed(coverW, coverH))

        // The picture: the whole column, or the cover's place, or between.
        val picX = lerpPx(ext.toFloat(), coverX.toFloat(), k).roundToInt()
        val picY = lerpPx(0f, coverY.toFloat(), k).roundToInt()
        val picW = lerpPx(colW.toFloat(), coverW.toFloat(), k).roundToInt().coerceAtLeast(0)
        val picH = lerpPx(height.toFloat(), coverH.toFloat(), k).roundToInt().coerceAtLeast(0)
        val pictureP = pictures.firstOrNull()?.measure(Constraints.fixed(picW, picH))
        // The picture's corner. Inset in the cover's place it is the cover's;
        // filling the column beside an open extension it is the panel's own,
        // so the clip's left edge rounds off like every other edge of the
        // pane instead of meeting the words square. The right corners land
        // exactly under the pane's, which is the same shape at the same place.
        val picRadius = maxOf(
            PanelPictureRadius.toPx() * k,
            FluidRadius.Sheet.toPx() * e01 * (1f - k),
        )
        val picSettled = (k <= 0.001f || k >= 0.999f) && (e01 <= 0.001f || e01 >= 0.999f)
        val picShape: androidx.compose.ui.graphics.Shape? = when {
            picRadius < 0.5f -> null
            // Rounded in transit, continuous at rest: a continuous corner is a
            // path, and a path re-cut every frame is the one thing the corner
            // rules forbid.
            !picSettled -> RoundedCornerShape(picRadius)
            k >= 0.999f -> ContinuousCornerShape(PanelPictureRadius)
            else -> ContinuousCornerShape(FluidRadius.Sheet)
        }

        val washP = if (ext > 0) washes.firstOrNull()?.measure(Constraints.fixed(ext, height)) else null
        val extensionP = if (ext > 0) extensions.firstOrNull()?.measure(Constraints.fixed(ext, height)) else null

        val button = buttons.firstOrNull()?.measure(constraints.copy(minWidth = 0, minHeight = 0))
        // Centred on the header's line of text, at the frame's left edge: in
        // the column at the second size, at the extension's corner at the
        // third, and it is the same place because the edge is what moved.
        val buttonY = (head.height + (HeaderTop - HeaderBottom).roundToPx()) / 2 - (button?.height ?: 0) / 2
        val buttonSlide = (WideButtonSlide.toPx() * (1f - r01)).roundToInt()
        val buttonAlpha = ((r01 - WideButtonFadeFrom) / (1f - WideButtonFadeFrom)).coerceIn(0f, 1f)

        layout(width, height) {
            washP?.placeWithLayer(0, 0) { alpha = (e01 / ExtensionFadeIn).coerceIn(0f, 1f) }
            extensionP?.place(0, 0)
            coverP?.place(coverX, coverY)
            pictureP?.placeWithLayer(picX, picY) {
                if (picShape != null) {
                    shape = picShape
                    clip = true
                }
            }
            head.place(ext, 0)
            // Pinned to the floor, so the picture grows into the space above it
            // rather than pushing the transport off the bottom of the window.
            bodyP.place(ext, height - bodyP.height)
            button?.placeWithLayer(gutter - buttonSlide, buttonY) { alpha = buttonAlpha }
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

/**
 * And how far one inset in the cover's place may: a landscape clip is a wide
 * cover there, which is fine, and a clip taller than the place is high is
 * cropped to it like the pane crops one at rest.
 */
private const val MinInsetRatio = 0.5f
private const val MaxInsetRatio = 2f

/** The room under the inset cover, folded away with it. */
private val PanelCoverGap = 18.dp

/** The panel's gutters, on every side. */
private val PanelGutter = 20.dp

/** The header's own air, above and below its line. */
private val HeaderTop = 20.dp
private val HeaderBottom = 10.dp

/**
 * The corner of the cover, and of a clip inset in its place.
 *
 * Twenty-six, continuous. The concentric answer — the pane's radius less the
 * gutter — is eighteen, and eighteen on a cover three hundred points across
 * reads as square; this is the smallest that reads as a cover in a pane and not
 * a tile in one.
 */
private val PanelPictureRadius = 26.dp

/** The button at the top left, and the air between it and the header's line. */
private val WideButton = 40.dp
private val WideButtonGap = 8.dp

/** How far it comes in from the panel's edge, and where on the first leg it starts to show. */
private val WideButtonSlide = 24.dp
private const val WideButtonFadeFrom = 0.35f

/** How much slower than the edge the extension's content travels, and how soon it is whole. */
private const val ExtensionParallax = 0.35f
private const val ExtensionFadeIn = 0.4f

/**
 * The wash under the extension, as the dark side's black; see the wash slot.
 *
 * Heavier than a header's: a header carries a title over a picture it wants
 * seen, this carries a column of small type over a shelf of covers nobody is
 * looking at. Measured at thirty percent the shelf still came through the
 * words; at forty-five it is a wash.
 */
private const val ExtensionWash = 0.45f

/**
 * What a clip under the scrim amounts to, for the purpose of picking a side.
 *
 * Not true black — the pane still has a film on it and a video under that — but
 * far enough down that the ink derived from it is the light one, which is the
 * whole question being asked.
 */
private val CanvasGround = Color(0xFF121212)
