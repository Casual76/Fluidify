package dev.pampa.fluidify.wear.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.Heart
import com.adamglin.phosphoricons.fill.Pause
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.fill.SkipBack
import com.adamglin.phosphoricons.fill.SkipForward
import com.adamglin.phosphoricons.regular.Car
import com.adamglin.phosphoricons.regular.DeviceMobile
import com.adamglin.phosphoricons.regular.Headphones
import com.adamglin.phosphoricons.regular.Heart
import com.adamglin.phosphoricons.regular.Laptop
import com.adamglin.phosphoricons.regular.Queue
import com.adamglin.phosphoricons.regular.SpeakerHigh
import com.adamglin.phosphoricons.regular.Television
import com.adamglin.phosphoricons.regular.Watch
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.antigravity.fluidengine.ui.haptics.FluidHapticEvent
import dev.antigravity.fluidengine.wear.ambient.FluidAmbientState
import dev.antigravity.fluidengine.wear.ambient.LocalFluidWearAmbient
import dev.antigravity.fluidengine.wear.ambient.fluidBurnInShift
import dev.antigravity.fluidengine.wear.components.FluidArcRow
import dev.antigravity.fluidengine.wear.components.fluidRotarySteps
import dev.antigravity.fluidengine.wear.glass.FluidGlassCapsule
import dev.antigravity.fluidengine.wear.glass.FluidGlassDisc
import dev.antigravity.fluidengine.wear.glass.FluidGlassTimePill
import dev.antigravity.fluidengine.wear.theme.FluidWearAccent
import dev.antigravity.fluidengine.wear.theme.FluidWearDimens
import dev.pampa.fluidify.wear.R
import dev.pampa.fluidify.wear.link.ArtStore
import dev.pampa.fluidify.wear.link.LinkStatus
import dev.pampa.fluidify.wear.playback.NowPlaying
import dev.pampa.fluidify.wear.playback.PlaybackControls
import dev.pampa.fluidify.wear.playback.VolumeControl
import dev.pampa.fluidify.wear.protocol.DeviceKind
import dev.pampa.fluidify.wear.ui.common.CoverLayer
import dev.pampa.fluidify.wear.ui.common.rememberArtworkAccent
import dev.pampa.fluidify.wear.ui.common.screenStarted
import dev.pampa.fluidify.wear.ui.theme.WearDimens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.cos

/**
 * The player: the screen the app opens on.
 *
 * Laid out like Spotify's watch player — the time and the title on top, previous / play / next
 * across the middle, three actions along the bottom of the bezel — and made of Fluid's material:
 * the sharp cover fills the screen, every control is a pane of the same glass bending it, and the
 * song's progress is a line of light on the very edge of the screen, tinted with the cover's own
 * colour, that leaves a gap for the clock instead of running behind it.
 *
 * What changes often stays outside the glass's source on purpose: the ring redraws on its own
 * layer, so its ticking never makes a pane re-capture the cover. The panes re-capture when the
 * cover changes or when one of them is pressed, and otherwise hold still.
 *
 * What is playing is read in pieces, each by the part of the screen that draws it: a new snapshot
 * every second of a song would otherwise recompose the whole player to change nothing but a
 * position that only the ring shows.
 *
 * @param active false while the page is off screen (the pager keeps it composed next door): the
 *   ring stops ticking.
 */
@Composable
fun PlayerScreen(
    controls: PlaybackControls,
    art: ArtStore,
    onQueue: () -> Unit,
    onOutput: () -> Unit,
    onEssentials: () -> Unit,
    modifier: Modifier = Modifier,
    volume: VolumeControl? = null,
    /** A word on the watch's own playback (starting, needs the phone), in place of the artist. */
    status: String? = null,
    active: Boolean = true,
    /** Taking a like back: asks first (see [LikeActions]). Without one, the heart unlikes at once. */
    onUnlike: (() -> Unit)? = null,
    /** The long press on the heart: the song into a playlist. */
    onAddToPlaylist: (() -> Unit)? = null,
    /** Whether the controls are on screen; see [PlayerChrome]. Without one they always are. */
    chrome: PlayerChrome? = null,
) {
    val nowState = controls.nowPlaying.collectAsStateWithLifecycle()
    // The volume follows the phone's by itself (see VolumeControl): nothing to sync from here.
    val volumeVisible by (volume?.visible ?: remember { MutableStateFlow(false) }).collectAsStateWithLifecycle()
    val volumeLevel by (volume?.level ?: remember { MutableStateFlow(0f) }).collectAsStateWithLifecycle()
    val ambient = LocalFluidWearAmbient.current
    val dimmed = rememberAmbientDim(ambient)
    val started = screenStarted()
    val artKey by remember { derivedStateOf { nowState.value.snapshot?.track?.artKey } }
    val durationMs by remember { derivedStateOf { nowState.value.snapshot?.track?.durationMs ?: 0L } }
    val deviceName by remember { derivedStateOf { nowState.value.snapshot?.device?.name } }
    val ticking by remember(active, started, ambient) {
        derivedStateOf {
            val snapshot = nowState.value.snapshot
            active && started && !ambient.isAmbient && snapshot?.isPlaying == true && !snapshot.buffering
        }
    }
    val controlsHidden by remember(chrome) { derivedStateOf { chrome?.isHidden == true } }
    val accent = rememberArtworkAccent(artKey, art)

    FluidWearAccent(seed = accent) {
        val backdrop = rememberGlassBackdrop()
        Box(
            modifier = modifier
                .fillMaxSize()
                .then(if (volume != null) Modifier.fluidRotarySteps(onSteps = volume::turn) else Modifier),
        ) {
            CoverLayer(
                artKey = artKey,
                art = art,
                modifier = Modifier
                    .fillMaxSize()
                    .glassBackdropSource(backdrop),
            )
            // Always-on display: the same screen, the lights turned down. The cover stays a cover
            // at a fraction of its brightness (none at all on a panel that can only show a few
            // colours), so the screen is still recognisably the player at a glance.
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind { drawRect(Color.Black, alpha = dimmed.value * coverDim(ambient)) },
            )

            // Controls step back while the bezel turns: the volume is what is being looked at.
            val volumeAlpha by animateFloatAsState(
                targetValue = if (volumeVisible) DimmedControls else 1f,
                animationSpec = FluidMotion.fadeIn(),
                label = "controls",
            )
            // How much of the controls is there: the volume, the hidden controls and the always-on
            // display all take some away, and each fades at its own pace. Read in the draw pass
            // only, so a finger moving them recomposes nothing.
            val controlsAlpha = { volumeAlpha * (1f - (chrome?.hidden ?: 0f)) * (1f - dimmed.value) }
            // The clock and the title stay in always-on, as plain type in place of their glass.
            val glassAlpha = { (1f - (chrome?.hidden ?: 0f)) * (1f - dimmed.value) }
            val plainAlpha = { dimmed.value }
            // The clock's width, for the ring to leave a gap round it. A state of its own that only
            // the ring reads (see AudioReactiveRing): measuring the clock must not recompose the player.
            val clockWidth = remember { mutableStateOf(0.dp) }
            val density = LocalDensity.current

            AudioReactiveRing(
                positionMs = { nowState.value.positionAt(System.currentTimeMillis()) },
                durationMs = durationMs,
                running = ticking,
                // The gap closes as the clock goes with the rest of the controls.
                clearTop = {
                    if (clockWidth.value > 0.dp) {
                        (clockWidth.value + FluidWearDimens.EdgeRingClockMargin * 2) * (1f - (chrome?.hidden ?: 0f))
                    } else {
                        0.dp
                    }
                },
                // Out of the way of the volume's own line of light on the same edge, and gone in
                // always-on: a lit arc round the edge is exactly what burns in.
                modifier = Modifier.graphicsLayer { alpha = volumeAlpha * (1f - dimmed.value) },
            )

            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    // Hidden controls are not there for TalkBack either; the cover's own actions are.
                    .then(if (controlsHidden || ambient.isAmbient) Modifier.clearAndSetSemantics { } else Modifier),
            ) {
                val compact = maxWidth < WearDimens.CompactScreen
                val sizes = when {
                    compact -> DiscSizes.Compact
                    maxWidth < MediumScreen -> DiscSizes.Medium
                    else -> DiscSizes.Regular
                }
                val capsuleMax = maxWidth * CapsuleWidthFraction
                PlayerLayout(
                    compact = compact,
                    arcTop = maxHeight - FluidWearDimens.ArcEdgeClearance - sizes.queue,
                    arcSideTop = maxHeight / 2 +
                        (minOf(maxWidth, maxHeight) / 2 - FluidWearDimens.ArcEdgeClearance - sizes.arc / 2) *
                        cos(Math.toRadians(sizes.arcSpacing.toDouble())).toFloat() - sizes.arc / 2,
                    // Lit pixels walk a few pixels every minute in always-on, where the panel asks.
                    modifier = Modifier.fluidBurnInShift(ambient),
                    clock = {
                        Box(contentAlignment = Alignment.Center) {
                            FluidGlassTimePill(
                                backdrop = backdrop,
                                modifier = Modifier
                                    .graphicsLayer { alpha = glassAlpha() }
                                    .onSizeChanged { clockWidth.value = with(density) { it.width.toDp() } },
                            )
                            // The same time again, for always-on: decoration as far as TalkBack is
                            // concerned, which already has the one above.
                            FluidGlassTimePill(
                                backdrop = null,
                                modifier = Modifier
                                    .clearAndSetSemantics { }
                                    .graphicsLayer { alpha = plainAlpha() },
                            )
                        }
                    },
                    title = {
                        Box(contentAlignment = Alignment.Center) {
                            TitleCapsule(
                                nowState = nowState,
                                status = status,
                                backdrop = backdrop,
                                compact = compact,
                                onOpen = onEssentials,
                                modifier = Modifier
                                    .widthIn(max = capsuleMax)
                                    .graphicsLayer { alpha = glassAlpha() },
                            )
                            PlainTitle(
                                nowState = nowState,
                                compact = compact,
                                lowBit = ambient.lowBitAmbient,
                                modifier = Modifier
                                    .widthIn(max = capsuleMax)
                                    .clearAndSetSemantics { }
                                    .graphicsLayer { alpha = plainAlpha() },
                            )
                        }
                    },
                    transport = { maxDisc ->
                        Box(Modifier.graphicsLayer { alpha = controlsAlpha() }) {
                            Transport(controls, nowState, backdrop, sizes, maxDisc)
                        }
                    },
                )
                ArcActions(
                    controls = controls,
                    nowState = nowState,
                    backdrop = backdrop,
                    size = sizes.arc,
                    queueSize = sizes.queue,
                    spacing = sizes.arcSpacing,
                    onQueue = onQueue,
                    onOutput = onOutput,
                    likes = LikeActions(controls, confirmUnlike = onUnlike ?: { controls.setLiked(false) }),
                    onAddToPlaylist = onAddToPlaylist,
                    modifier = Modifier.graphicsLayer { alpha = controlsAlpha() },
                )
            }

            // Above everything, so the invisible controls under it cannot be pressed: with the
            // controls away, the cover itself is the control.
            if (chrome != null && controlsHidden && !ambient.isAmbient) {
                ImmersiveGestures(
                    controls = controls,
                    nowState = nowState,
                    backdrop = backdrop,
                    chrome = chrome,
                    onUnlike = onUnlike,
                )
            }

            if (!ambient.isAmbient) {
                VolumeOverlay(
                    visible = volumeVisible,
                    level = volumeLevel,
                    device = deviceName,
                    backdrop = backdrop,
                )
            }
        }
    }
}

/**
 * How far into always-on the player is drawn: 0 interactive, 1 fully dimmed.
 *
 * Eased both ways, so the screen settles into always-on the way the system's own brightness does
 * instead of cutting to a different picture. A panel in always-on is refreshed about once a
 * minute, so the fade may be frozen wherever it was when the refreshes stopped: the first refresh
 * after entering (or a screen composed already in always-on) puts it at its end, and nothing is
 * left half-lit for longer than that.
 */
@Composable
internal fun rememberAmbientDim(ambient: FluidAmbientState): State<Float> {
    val dimmed = remember { Animatable(if (ambient.isAmbient) 1f else 0f) }
    LaunchedEffect(ambient.isAmbient) {
        if (ambient.isAmbient) {
            val enteredAt = ambient.updateTick
            launch { dimmed.animateTo(1f, tween(AmbientEnterMs, easing = FastOutSlowInEasing)) }
            snapshotFlow { ambient.updateTick }.first { it != enteredAt }
            dimmed.snapTo(1f)
        } else {
            dimmed.animateTo(0f, tween(AmbientExitMs, easing = FastOutSlowInEasing))
        }
    }
    return dimmed.asState()
}

/** How dark the cover goes in always-on; all the way on a panel of a few colours. */
private fun coverDim(ambient: FluidAmbientState): Float = when {
    ambient.lowBitAmbient -> 1f
    ambient.burnInProtectionRequired -> AmbientCoverDimProtected
    else -> AmbientCoverDim
}

/**
 * Places the clock at the top, the title right under it and the transport across the middle —
 * pushed down only as far as the title needs, and never into the arc of actions at the bottom.
 *
 * Measured rather than placed by fractions: a title on two lines, a large font setting and a
 * 192 dp watch all change how much room the top takes, and a fixed fraction either wastes the
 * middle of a big screen or puts the play button under the title on a small one. The title is
 * composed once, given the room that is left, and drops its second line by itself when that does
 * not fit (see [TitleLines]).
 */
@Composable
private fun PlayerLayout(
    compact: Boolean,
    arcTop: Dp,
    arcSideTop: Dp,
    modifier: Modifier = Modifier,
    clock: @Composable () -> Unit,
    title: @Composable () -> Unit,
    transport: @Composable (Dp) -> Unit,
) {
    val tight = isTightClock(compact)
    SubcomposeLayout(modifier = modifier.fillMaxSize()) { constraints ->
        val loose = Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight)
        val clockPlaceable = subcompose("clock") { PlayerClock(tight, clock) }.firstOrNull()?.measure(loose)
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val gap = (if (tight) WearDimens.PlayerGapTight else if (compact) WearDimens.PlayerGapCompact else WearDimens.PlayerGap).roundToPx()
        val clockTop = (if (tight) WearDimens.ClockTopTight else FluidWearDimens.TimePillTop).roundToPx()
        val clockBottom = clockTop + (clockPlaceable?.height ?: 0)
        val titleTop = clockBottom + gap
        val maxBottom = minOf(arcTop.roundToPx(), arcSideTop.roundToPx()) - gap
        val minDisc = FluidWearDimens.MinTouchTarget.roundToPx()
        val titleRoom = (maxBottom - titleTop - gap - minDisc).coerceAtLeast(0)
        val titlePlaceable = subcompose("title", title).firstOrNull()?.measure(loose.copy(maxHeight = titleRoom))
        val titleBottom = titleTop + (titlePlaceable?.height ?: 0)
        val maxDisc = (maxBottom - titleBottom - gap).coerceAtLeast(minDisc).toDp()
        val transportPlaceable = subcompose("transport") { transport(maxDisc) }.firstOrNull()?.measure(loose)
        val transportHeight = transportPlaceable?.height ?: 0
        val lowest = maxBottom - transportHeight
        val transportTop = maxOf(height / 2 - transportHeight / 2, titleBottom + gap).coerceAtMost(lowest)
        layout(width, height) {
            clockPlaceable?.place((width - clockPlaceable.width) / 2, clockTop)
            titlePlaceable?.place((width - titlePlaceable.width) / 2, titleTop)
            transportPlaceable?.place((width - transportPlaceable.width) / 2, transportTop)
        }
    }
}

@Composable
private fun TitleCapsule(
    nowState: State<NowPlaying>,
    status: String?,
    backdrop: GlassBackdropState,
    compact: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val track by remember { derivedStateOf { nowState.value.snapshot?.track } }
    val link by remember { derivedStateOf { nowState.value.link } }
    val openLabel = stringResource(R.string.song_options)
    FluidGlassCapsule(
        backdrop = backdrop,
        onClick = onOpen,
        // Said to TalkBack as what the press does: the capsule is a title, and "double tap to
        // activate" does not tell where it leads.
        modifier = modifier.semantics { onClick(label = openLabel) { onOpen(); true } },
    ) {
        // On the smallest watches the artist gives its line back to the transport, unless there
        // is something to say there that matters more (the phone is away, the watch is starting).
        val notice = status ?: linkMessage(link)?.let { stringResource(it) }
        val second = notice ?: track?.artist?.takeUnless { compact }
        TitleLines(
            first = {
                Text(
                    text = track?.title ?: stringResource(R.string.nothing_playing),
                    style = when {
                        track == null -> MaterialTheme.typography.bodySmall
                        compact -> MaterialTheme.typography.labelSmall
                        else -> MaterialTheme.typography.labelMedium
                    },
                    textAlign = TextAlign.Center,
                    maxLines = if (track == null) 2 else 1,
                    modifier = if (track != null) Modifier.basicMarquee(iterations = MarqueeIterations) else Modifier,
                )
            },
            second = second?.takeIf { it.isNotEmpty() }?.let { line ->
                {
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodyExtraSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
        )
    }
}

/**
 * The title as it stays in always-on: the same lines in the same place, without the glass round
 * them, which would be the brightest thing on the screen. Padded like the capsule, so the type
 * does not move as one fades into the other.
 */
@Composable
private fun PlainTitle(
    nowState: State<NowPlaying>,
    compact: Boolean,
    lowBit: Boolean,
    modifier: Modifier = Modifier,
) {
    val track by remember { derivedStateOf { nowState.value.snapshot?.track } }
    val color = if (lowBit) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier.padding(
            horizontal = FluidWearDimens.CapsulePaddingHorizontal,
            vertical = FluidWearDimens.CapsulePaddingVertical,
        ),
        contentAlignment = Alignment.Center,
    ) {
        TitleLines(
            first = {
                Text(
                    text = track?.title ?: stringResource(R.string.nothing_playing),
                    style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                    color = color,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            second = track?.artist?.takeIf { it.isNotBlank() && !compact }?.let { artist ->
                {
                    Text(
                        text = artist,
                        style = MaterialTheme.typography.bodyExtraSmall,
                        color = color,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
        )
    }
}

/**
 * Two lines of text, one over the other and centred, the second left out when both do not fit the
 * height it is given. Deciding that here, while measuring, is what lets the title be composed
 * once: the layout above used to compose it with the second line, find it too tall, and compose
 * it again without.
 */
@Composable
private fun TitleLines(first: @Composable () -> Unit, second: (@Composable () -> Unit)?) {
    Layout(
        content = {
            Box(Modifier.layoutId(FirstLine)) { first() }
            if (second != null) Box(Modifier.layoutId(SecondLine)) { second() }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val top = measurables.first { it.layoutId == FirstLine }.measure(loose)
        val bottom = measurables.firstOrNull { it.layoutId == SecondLine }?.measure(loose)
        val fits = bottom != null && (constraints.maxHeight == Constraints.Infinity || top.height + bottom.height <= constraints.maxHeight)
        val shown = bottom.takeIf { fits }
        val width = maxOf(top.width, shown?.width ?: 0)
        val height = top.height + (shown?.height ?: 0)
        layout(width, height) {
            top.placeRelative((width - top.width) / 2, 0)
            shown?.placeRelative((width - shown.width) / 2, top.height)
        }
    }
}

@Composable
private fun Transport(
    controls: PlaybackControls,
    nowState: State<NowPlaying>,
    backdrop: GlassBackdropState,
    sizes: DiscSizes,
    maxDisc: Dp,
) {
    val hasTrack by remember { derivedStateOf { nowState.value.snapshot?.track != null } }
    val playing by remember { derivedStateOf { nowState.value.snapshot?.let { it.isPlaying || it.playWhenReady } == true } }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FluidWearDimens.TransportGap),
        ) {
            FluidGlassDisc(
                onClick = controls::previous,
                backdrop = backdrop,
                contentDescription = stringResource(R.string.previous),
                size = minOf(sizes.side, maxDisc),
                enabled = hasTrack,
            ) { Icon(PhosphorIcons.Fill.SkipBack, contentDescription = null, modifier = Modifier.size(FluidWearDimens.IconMedium)) }
            FluidGlassDisc(
                onClick = controls::togglePlay,
                backdrop = backdrop,
                contentDescription = stringResource(if (playing) R.string.pause else R.string.play),
                size = minOf(sizes.main, maxDisc),
                haptic = FluidHapticEvent.Confirm,
            ) {
                Icon(
                    imageVector = if (playing) PhosphorIcons.Fill.Pause else PhosphorIcons.Fill.Play,
                    contentDescription = null,
                    modifier = Modifier.size(FluidWearDimens.IconLarge),
                )
            }
            FluidGlassDisc(
                onClick = controls::next,
                backdrop = backdrop,
                contentDescription = stringResource(R.string.next),
                size = minOf(sizes.side, maxDisc),
                enabled = hasTrack,
            ) { Icon(PhosphorIcons.Fill.SkipForward, contentDescription = null, modifier = Modifier.size(FluidWearDimens.IconMedium)) }
        }
    }
}

/**
 * Output, queue and like, hugging the bottom of the bezel.
 *
 * The queue, in the middle, is a size up from the two beside it: it sits where the bezel is
 * lowest, and at the sides' size it left a hole between itself and the play button.
 */
@Composable
private fun ArcActions(
    controls: PlaybackControls,
    nowState: State<NowPlaying>,
    backdrop: GlassBackdropState,
    size: Dp,
    queueSize: Dp,
    spacing: Float,
    onQueue: () -> Unit,
    onOutput: () -> Unit,
    likes: LikeActions,
    onAddToPlaylist: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val deviceKind by remember { derivedStateOf { nowState.value.snapshot?.device?.kind } }
    val liked by remember { derivedStateOf { nowState.value.snapshot?.liked == true } }
    val likeable by remember { derivedStateOf { nowState.value.snapshot?.track.likeable() } }
    FluidArcRow(
        modifier = modifier.fillMaxSize(),
        spacing = spacing,
        edgeClearance = FluidWearDimens.ArcEdgeClearance,
    ) {
        FluidGlassDisc(
            onClick = onOutput,
            backdrop = backdrop,
            contentDescription = stringResource(R.string.audio_output),
            size = size,
        ) { Icon(deviceIcon(deviceKind), contentDescription = null, modifier = Modifier.size(FluidWearDimens.IconMedium)) }
        FluidGlassDisc(
            onClick = onQueue,
            backdrop = backdrop,
            contentDescription = stringResource(R.string.queue),
            size = queueSize,
        ) { Icon(PhosphorIcons.Regular.Queue, contentDescription = null, modifier = Modifier.size(QueueIcon)) }
        // The heart fills with a little spring when it is lit; the one action here that is a feeling.
        val heart = remember { Animatable(1f) }
        var lastLiked by remember { mutableStateOf(liked) }
        LaunchedEffect(liked) {
            if (liked && !lastLiked) {
                heart.snapTo(HeartPop)
                heart.animateTo(1f, FluidMotion.fluid())
            }
            lastLiked = liked
        }
        FluidGlassDisc(
            onClick = { likes.toggle(liked) },
            onLongClick = onAddToPlaylist?.takeIf { likeable },
            backdrop = backdrop,
            contentDescription = stringResource(if (liked) R.string.unlike else R.string.like),
            size = size,
            enabled = likeable,
            selected = liked,
            // Taking it back only opens the question; the answer is what toggles it off.
            haptic = if (liked) FluidHapticEvent.Tap else FluidHapticEvent.ToggleOn,
        ) {
            Icon(
                imageVector = if (liked) PhosphorIcons.Fill.Heart else PhosphorIcons.Regular.Heart,
                contentDescription = null,
                modifier = Modifier
                    .size(FluidWearDimens.IconMedium)
                    .graphicsLayer {
                        scaleX = heart.value
                        scaleY = heart.value
                    },
            )
        }
    }
}

/** Why the phone is not answering, said in the capsule's second line. Null when it is. */
private fun linkMessage(link: LinkStatus): Int? = when (link) {
    LinkStatus.CONNECTED, LinkStatus.UNKNOWN -> null
    LinkStatus.UNREACHABLE -> R.string.phone_unreachable
    LinkStatus.NOT_FOUND -> R.string.phone_not_found
    LinkStatus.NO_ANSWER -> R.string.phone_no_answer
    LinkStatus.SIGNATURE_MISMATCH -> R.string.signature_mismatch
    LinkStatus.PHONE_OUTDATED -> R.string.phone_outdated
    LinkStatus.WATCH_OUTDATED -> R.string.watch_outdated
}

/** The disc sizes of a screen class: the transport's play and sides, the arc's sides and its queue. */
private enum class DiscSizes(val main: Dp, val side: Dp, val arc: Dp, val queue: Dp, val arcSpacing: Float) {
    Regular(FluidWearDimens.DiscLarge, FluidWearDimens.DiscMedium, FluidWearDimens.DiscArc, RegularQueue, RegularArcSpacing),

    /**
     * A 216 dp watch: the regular discs, with the arc's sides brought down towards the queue. At
     * the regular angle they rose into previous and next, the transport sitting lower here under a
     * title that takes the same room as on a big screen.
     */
    Medium(FluidWearDimens.DiscLarge, FluidWearDimens.DiscMedium, FluidWearDimens.DiscArc, RegularQueue, MediumArcSpacing),

    /**
     * A 192 dp watch: everything a step down, still never under Wear's 48 dp touch target, and the
     * arc opened wider so its outer discs clear previous and next.
     */
    Compact(CompactMain, FluidWearDimens.MinTouchTarget, FluidWearDimens.MinTouchTarget, CompactQueue, CompactArcSpacing),
}

/** Below [WearDimens.CompactScreen] the player takes its compact sizes; below this, and above it, the 216 dp class. */
private val MediumScreen = 228.dp
private val CompactMain = 56.dp

/** Between the arc's sides and the play button: bigger than the one, smaller than the other. */
private val RegularQueue = 58.dp
private val CompactQueue = 52.dp
private val QueueIcon = 26.dp

/** Widest the title capsule may grow, as a fraction of the screen's width. */
private const val CapsuleWidthFraction = 0.72f

/** Degrees between the arc's discs: wide enough that thumb-sized discs never touch the transport. */
private const val RegularArcSpacing = 52f
private const val MediumArcSpacing = 44f
private const val CompactArcSpacing = 45f

/** How dark the cover goes in always-on: a fraction of its light, more where the panel asks. */
private const val AmbientCoverDim = 0.72f
private const val AmbientCoverDimProtected = 0.82f

/** Into always-on and back: long enough to read as the lights going down, not as a cut. */
private const val AmbientEnterMs = 420
private const val AmbientExitMs = 260

/** How faint the controls get while the volume is being turned. */
private const val DimmedControls = 0.22f
private const val MarqueeIterations = 2
private const val HeartPop = 0.6f

/** The ids of the title's two lines, for [TitleLines] to tell its children apart. */
private const val FirstLine = "first"
private const val SecondLine = "second"

fun deviceIcon(kind: DeviceKind?): ImageVector = when (kind) {
    DeviceKind.COMPUTER -> PhosphorIcons.Regular.Laptop
    DeviceKind.SPEAKER -> PhosphorIcons.Regular.SpeakerHigh
    DeviceKind.TV -> PhosphorIcons.Regular.Television
    DeviceKind.CAR -> PhosphorIcons.Regular.Car
    DeviceKind.WATCH -> PhosphorIcons.Regular.Watch
    DeviceKind.HEADPHONES -> PhosphorIcons.Regular.Headphones
    else -> PhosphorIcons.Regular.DeviceMobile
}
