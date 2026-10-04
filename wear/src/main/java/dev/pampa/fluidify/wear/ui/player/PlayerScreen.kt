package dev.pampa.fluidify.wear.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
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
import dev.antigravity.fluidengine.wear.ambient.LocalFluidWearAmbient
import dev.antigravity.fluidengine.wear.ambient.fluidBurnInShift
import dev.antigravity.fluidengine.wear.components.FluidArcRow
import dev.antigravity.fluidengine.wear.components.FluidEdgeGlowRing
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.roundToInt

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
) {
    val now by controls.nowPlaying.collectAsStateWithLifecycle()
    val remoteVolume = now.snapshot?.device?.volume
    LaunchedEffect(remoteVolume) { if (remoteVolume != null) volume?.sync(remoteVolume) }
    val volumeVisible by (volume?.visible ?: remember { MutableStateFlow(false) }).collectAsStateWithLifecycle()
    val volumeLevel by (volume?.level ?: remember { MutableStateFlow(0f) }).collectAsStateWithLifecycle()
    val ambient = LocalFluidWearAmbient.current
    val snapshot = now.snapshot
    val track = snapshot?.track
    val accent = rememberArtworkAccent(track?.artKey, art)

    FluidWearAccent(seed = accent.takeUnless { ambient.isAmbient }) {
        val backdrop = rememberGlassBackdrop()
        Box(
            modifier = modifier
                .fillMaxSize()
                .then(if (volume != null && !ambient.isAmbient) Modifier.fluidRotarySteps(onSteps = volume::turn) else Modifier),
        ) {
            CoverLayer(
                artKey = track?.artKey,
                art = art,
                dimmed = ambient.isAmbient,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (ambient.isAmbient) Modifier.fluidBurnInShift(ambient) else Modifier.glassBackdropSource(backdrop)),
            )

            if (ambient.isAmbient) {
                FluidGlassTimePill(
                    backdrop = null,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = FluidWearDimens.TimePillTop)
                        .fluidBurnInShift(ambient),
                )
                AmbientCaption(now, Modifier.align(Alignment.BottomCenter).fluidBurnInShift(ambient))
            } else {
                // Controls step back while the bezel turns: the volume is what is being looked at.
                val controlsAlpha by animateFloatAsState(
                    targetValue = if (volumeVisible) DimmedControls else 1f,
                    animationSpec = FluidMotion.fadeIn(),
                    label = "controls",
                )
                var clockWidth by remember { mutableStateOf(0.dp) }
                val density = LocalDensity.current

                FluidEdgeGlowRing(
                    positionMs = { now.positionAt(System.currentTimeMillis()) },
                    durationMs = track?.durationMs ?: 0L,
                    running = active && snapshot?.isPlaying == true && !snapshot.buffering,
                    clearTop = if (clockWidth > 0.dp) clockWidth + FluidWearDimens.EdgeRingClockMargin * 2 else 0.dp,
                    // Out of the way of the volume's own line of light on the same edge.
                    modifier = Modifier.graphicsLayer { alpha = controlsAlpha },
                )

                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val compact = maxWidth < CompactScreen
                    val sizes = when {
                        compact -> DiscSizes.Compact
                        maxWidth < MediumScreen -> DiscSizes.Medium
                        else -> DiscSizes.Regular
                    }
                    PlayerLayout(
                        compact = compact,
                        arcTop = maxHeight - FluidWearDimens.ArcEdgeClearance - sizes.queue,
                        modifier = Modifier.graphicsLayer { alpha = controlsAlpha },
                        clock = {
                            FluidGlassTimePill(
                                backdrop = backdrop,
                                modifier = Modifier.onSizeChanged { clockWidth = with(density) { it.width.toDp() } },
                            )
                        },
                        title = {
                            TitleCapsule(
                                now = now,
                                status = status,
                                backdrop = backdrop,
                                compact = compact,
                                onClick = onEssentials,
                                modifier = Modifier.widthIn(max = maxWidth * CapsuleWidthFraction),
                            )
                        },
                        transport = { Transport(controls, now, backdrop, sizes) },
                    )
                    ArcActions(
                        controls = controls,
                        now = now,
                        backdrop = backdrop,
                        size = sizes.arc,
                        queueSize = sizes.queue,
                        spacing = sizes.arcSpacing,
                        onQueue = onQueue,
                        onOutput = onOutput,
                        likes = LikeActions(controls, confirmUnlike = onUnlike ?: { controls.setLiked(false) }),
                        onAddToPlaylist = onAddToPlaylist,
                        modifier = Modifier.graphicsLayer { alpha = controlsAlpha },
                    )
                }

                VolumeOverlay(
                    visible = volumeVisible,
                    level = volumeLevel,
                    device = snapshot?.device?.name,
                    backdrop = backdrop,
                )
            }
        }
    }
}

/**
 * Places the clock at the top, the title right under it and the transport across the middle —
 * pushed down only as far as the title needs, and never into the arc of actions at the bottom.
 *
 * Measured rather than placed by fractions: a title on two lines, a large font setting and a
 * 192 dp watch all change how much room the top takes, and a fixed fraction either wastes the
 * middle of a big screen or puts the play button under the title on a small one.
 */
@Composable
private fun PlayerLayout(
    compact: Boolean,
    arcTop: Dp,
    modifier: Modifier = Modifier,
    clock: @Composable () -> Unit,
    title: @Composable () -> Unit,
    transport: @Composable () -> Unit,
) {
    Layout(
        contents = listOf(clock, title, transport),
        modifier = modifier.fillMaxSize(),
    ) { (clockMeasurables, titleMeasurables, transportMeasurables), constraints ->
        val loose = Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight)
        val clockPlaceable = clockMeasurables.firstOrNull()?.measure(loose)
        val titlePlaceable = titleMeasurables.firstOrNull()?.measure(loose)
        val transportPlaceable = transportMeasurables.firstOrNull()?.measure(loose)
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val gap = (if (compact) CompactGap else RegularGap).roundToPx()
        val clockTop = FluidWearDimens.TimePillTop.roundToPx()
        val clockBottom = clockTop + (clockPlaceable?.height ?: 0)
        val titleTop = clockBottom + gap
        val titleBottom = titleTop + (titlePlaceable?.height ?: 0)
        val transportHeight = transportPlaceable?.height ?: 0
        val lowest = arcTop.roundToPx() - gap - transportHeight
        val transportTop = maxOf(height / 2 - transportHeight / 2, titleBottom + gap).coerceAtMost(maxOf(lowest, titleBottom + gap / 2))
        layout(width, height) {
            clockPlaceable?.place((width - clockPlaceable.width) / 2, clockTop)
            titlePlaceable?.place((width - titlePlaceable.width) / 2, titleTop)
            transportPlaceable?.place((width - transportPlaceable.width) / 2, transportTop)
        }
    }
}

@Composable
private fun TitleCapsule(
    now: NowPlaying,
    status: String?,
    backdrop: GlassBackdropState,
    compact: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = now.snapshot?.track
    FluidGlassCapsule(backdrop = backdrop, onClick = onClick, modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
            // On the smallest watches the artist gives its line back to the transport, unless there
            // is something to say there that matters more (the phone is away, the watch is starting).
            val notice = status ?: linkMessage(now.link)?.let { stringResource(it) }
            val second = notice ?: track?.artist?.takeUnless { compact }
            if (!second.isNullOrEmpty()) {
                Text(
                    text = second,
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Transport(controls: PlaybackControls, now: NowPlaying, backdrop: GlassBackdropState, sizes: DiscSizes) {
    val snapshot = now.snapshot
    val track = snapshot?.track
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FluidWearDimens.TransportGap),
    ) {
        FluidGlassDisc(
            onClick = controls::previous,
            backdrop = backdrop,
            contentDescription = stringResource(R.string.previous),
            size = sizes.side,
            enabled = track != null,
        ) { Icon(PhosphorIcons.Fill.SkipBack, contentDescription = null, modifier = Modifier.size(FluidWearDimens.IconMedium)) }
        val playing = snapshot?.isPlaying == true || snapshot?.playWhenReady == true
        FluidGlassDisc(
            onClick = controls::togglePlay,
            backdrop = backdrop,
            contentDescription = stringResource(if (playing) R.string.pause else R.string.play),
            size = sizes.main,
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
            size = sizes.side,
            enabled = track != null,
        ) { Icon(PhosphorIcons.Fill.SkipForward, contentDescription = null, modifier = Modifier.size(FluidWearDimens.IconMedium)) }
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
    now: NowPlaying,
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
    val snapshot = now.snapshot
    val track = snapshot?.track
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
        ) { Icon(deviceIcon(snapshot?.device?.kind), contentDescription = null, modifier = Modifier.size(FluidWearDimens.IconMedium)) }
        FluidGlassDisc(
            onClick = onQueue,
            backdrop = backdrop,
            contentDescription = stringResource(R.string.queue),
            size = queueSize,
        ) { Icon(PhosphorIcons.Regular.Queue, contentDescription = null, modifier = Modifier.size(QueueIcon)) }
        val liked = snapshot?.liked == true
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
        val likeable = track.likeable()
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

/** Ambient: just what is playing, small, under the dimmed cover. */
@Composable
private fun AmbientCaption(now: NowPlaying, modifier: Modifier = Modifier) {
    val track = now.snapshot?.track ?: return
    Column(
        modifier = modifier.padding(bottom = AmbientCaptionBottom).fillMaxWidth(AmbientCaptionWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = track.title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (track.artist.isNotEmpty()) {
            Text(
                text = track.artist,
                style = MaterialTheme.typography.bodyExtraSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
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

/** Below this the player takes its compact sizes: the 192 dp class of watch. */
private val CompactScreen = 210.dp

/** Below this, and above [CompactScreen], the 216 dp class. */
private val MediumScreen = 228.dp
private val CompactMain = 56.dp

/** Between the arc's sides and the play button: bigger than the one, smaller than the other. */
private val RegularQueue = 58.dp
private val CompactQueue = 50.dp
private val QueueIcon = 26.dp
private val RegularGap = 6.dp
private val CompactGap = 4.dp

/** Widest the title capsule may grow, as a fraction of the screen's width. */
private const val CapsuleWidthFraction = 0.72f

/** Degrees between the arc's discs: wide enough that thumb-sized discs never touch the transport. */
private const val RegularArcSpacing = 52f
private const val MediumArcSpacing = 44f
private const val CompactArcSpacing = 45f

/** How faint the controls get while the volume is being turned. */
private const val DimmedControls = 0.22f
private const val MarqueeIterations = 2
private const val HeartPop = 0.6f
private val AmbientCaptionBottom = 34.dp
private const val AmbientCaptionWidth = 0.7f

fun deviceIcon(kind: DeviceKind?): ImageVector = when (kind) {
    DeviceKind.COMPUTER -> PhosphorIcons.Regular.Laptop
    DeviceKind.SPEAKER -> PhosphorIcons.Regular.SpeakerHigh
    DeviceKind.TV -> PhosphorIcons.Regular.Television
    DeviceKind.CAR -> PhosphorIcons.Regular.Car
    DeviceKind.WATCH -> PhosphorIcons.Regular.Watch
    DeviceKind.HEADPHONES -> PhosphorIcons.Regular.Headphones
    else -> PhosphorIcons.Regular.DeviceMobile
}
