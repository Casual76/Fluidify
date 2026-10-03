package dev.pampa.fluidify.wear.ui.player

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.adamglin.phosphoricons.regular.ListBullets
import com.adamglin.phosphoricons.regular.SpeakerHigh
import com.adamglin.phosphoricons.regular.Television
import com.adamglin.phosphoricons.regular.Watch
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.antigravity.fluidengine.ui.haptics.FluidHapticEvent
import dev.antigravity.fluidengine.wear.ambient.LocalFluidWearAmbient
import dev.antigravity.fluidengine.wear.ambient.fluidBurnInShift
import dev.antigravity.fluidengine.wear.components.FluidArcRow
import dev.antigravity.fluidengine.wear.components.FluidEdgeProgressRing
import dev.antigravity.fluidengine.wear.glass.FluidGlassCapsule
import dev.antigravity.fluidengine.wear.glass.FluidGlassDisc
import dev.antigravity.fluidengine.wear.theme.FluidWearDimens
import dev.pampa.fluidify.wear.R
import dev.pampa.fluidify.wear.link.ArtStore
import dev.pampa.fluidify.wear.link.LinkStatus
import dev.pampa.fluidify.wear.playback.NowPlaying
import dev.pampa.fluidify.wear.playback.PlaybackControls
import dev.pampa.fluidify.wear.playback.VolumeControl
import dev.antigravity.fluidengine.wear.components.fluidRotarySteps
import kotlinx.coroutines.flow.MutableStateFlow
import dev.pampa.fluidify.wear.protocol.DeviceKind
import dev.pampa.fluidify.wear.ui.common.CoverLayer

/**
 * The player: the screen the app opens on.
 *
 * Laid out like Spotify's watch player — title on top, previous / play / next
 * across the middle, three small actions along the bottom of the bezel — and
 * made of Fluid's material: the sharp cover fills the screen and every control
 * is a pane of glass that bends it.
 *
 * What changes often stays outside the glass's source on purpose: the progress
 * ring and the clock redraw on their own layers, so their ticking never makes a
 * pane re-capture the cover. The panes re-capture when the cover changes or
 * when one of them is pressed, and otherwise hold still.
 */
@Composable
fun PlayerScreen(
    controls: PlaybackControls,
    art: ArtStore,
    onBrowse: () -> Unit,
    onOutput: () -> Unit,
    onEssentials: () -> Unit,
    modifier: Modifier = Modifier,
    volume: VolumeControl? = null,
) {
    val now by controls.nowPlaying.collectAsStateWithLifecycle()
    val remoteVolume = now.snapshot?.device?.volume
    LaunchedEffect(remoteVolume) { if (remoteVolume != null) volume?.sync(remoteVolume) }
    val volumeVisible by (volume?.visible ?: remember { MutableStateFlow(false) }).collectAsStateWithLifecycle()
    val volumeLevel by (volume?.level ?: remember { MutableStateFlow(0f) }).collectAsStateWithLifecycle()
    val ambient = LocalFluidWearAmbient.current
    val snapshot = now.snapshot
    val track = snapshot?.track
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
            AmbientCaption(now, Modifier.align(Alignment.BottomCenter).fluidBurnInShift(ambient))
        } else {
            FluidEdgeProgressRing(
                positionMs = { now.positionAt(System.currentTimeMillis()) },
                durationMs = track?.durationMs ?: 0L,
                running = snapshot?.isPlaying == true && !snapshot.buffering,
            )

            BoxWithConstraints(Modifier.fillMaxSize()) {
                // Positions as fractions of the screen, so the 40 mm and the 44 mm watch get
                // the same composition: title at a quarter, transport across the middle,
                // the small actions on the arc below.
                FluidGlassCapsule(
                    backdrop = backdrop,
                    onClick = onEssentials,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = maxHeight * if (track == null) CapsuleTopFractionEmpty else CapsuleTopFraction)
                        .widthIn(max = maxWidth * CapsuleWidthFraction),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = track?.title ?: stringResource(R.string.nothing_playing),
                            style = if (track == null) MaterialTheme.typography.bodySmall else MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center,
                            maxLines = if (track == null) 2 else 1,
                            modifier = if (track != null) Modifier.basicMarquee(iterations = 2) else Modifier,
                        )
                        val second = linkMessage(now.link)?.let { stringResource(it) } ?: track?.artist
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

                Row(
                    modifier = Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FluidGlassDisc(
                        onClick = controls::previous,
                        backdrop = backdrop,
                        contentDescription = stringResource(R.string.previous),
                        size = FluidWearDimens.DiscMedium,
                        enabled = track != null,
                    ) { Icon(PhosphorIcons.Fill.SkipBack, contentDescription = null, modifier = Modifier.size(20.dp)) }
                    val playing = snapshot?.isPlaying == true || snapshot?.playWhenReady == true
                    FluidGlassDisc(
                        onClick = controls::togglePlay,
                        backdrop = backdrop,
                        contentDescription = stringResource(if (playing) R.string.pause else R.string.play),
                        size = FluidWearDimens.DiscLarge,
                        haptic = FluidHapticEvent.Confirm,
                    ) {
                        Icon(
                            imageVector = if (playing) PhosphorIcons.Fill.Pause else PhosphorIcons.Fill.Play,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    FluidGlassDisc(
                        onClick = controls::next,
                        backdrop = backdrop,
                        contentDescription = stringResource(R.string.next),
                        size = FluidWearDimens.DiscMedium,
                        enabled = track != null,
                    ) { Icon(PhosphorIcons.Fill.SkipForward, contentDescription = null, modifier = Modifier.size(20.dp)) }
                }
            }

            FluidArcRow(modifier = Modifier.fillMaxSize(), radiusFraction = ArcRadiusFraction) {
                FluidGlassDisc(
                    onClick = onOutput,
                    backdrop = backdrop,
                    contentDescription = stringResource(R.string.audio_output),
                    size = FluidWearDimens.DiscSmall,
                ) { Icon(deviceIcon(snapshot?.device?.kind), contentDescription = null, modifier = Modifier.size(18.dp)) }
                FluidGlassDisc(
                    onClick = onBrowse,
                    backdrop = backdrop,
                    contentDescription = stringResource(R.string.browse),
                    size = FluidWearDimens.DiscSmall,
                ) { Icon(PhosphorIcons.Regular.ListBullets, contentDescription = null, modifier = Modifier.size(18.dp)) }
                val liked = snapshot?.liked == true
                FluidGlassDisc(
                    onClick = { controls.setLiked(!liked) },
                    backdrop = backdrop,
                    contentDescription = stringResource(if (liked) R.string.unlike else R.string.like),
                    size = FluidWearDimens.DiscSmall,
                    enabled = track?.uri?.startsWith("spotify:track:") == true,
                    selected = liked,
                    haptic = if (liked) FluidHapticEvent.ToggleOff else FluidHapticEvent.ToggleOn,
                ) {
                    Icon(
                        imageVector = if (liked) PhosphorIcons.Fill.Heart else PhosphorIcons.Regular.Heart,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            VolumeOverlay(visible = volumeVisible, level = volumeLevel)
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
        modifier = modifier.padding(bottom = 34.dp).fillMaxWidth(0.7f),
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

/** Top of the title capsule, as a fraction of the screen's height. */
private const val CapsuleTopFraction = 0.15f

/** Higher with nothing playing: the invitation to pick something wraps to two lines. */
private const val CapsuleTopFractionEmpty = 0.125f

/** Widest the title capsule may grow, as a fraction of the screen's width. */
private const val CapsuleWidthFraction = 0.72f

/** The bottom actions' distance from the centre, as a fraction of the radius. */
private const val ArcRadiusFraction = 0.72f

fun deviceIcon(kind: DeviceKind?): ImageVector = when (kind) {
    DeviceKind.COMPUTER -> PhosphorIcons.Regular.Laptop
    DeviceKind.SPEAKER -> PhosphorIcons.Regular.SpeakerHigh
    DeviceKind.TV -> PhosphorIcons.Regular.Television
    DeviceKind.CAR -> PhosphorIcons.Regular.Car
    DeviceKind.WATCH -> PhosphorIcons.Regular.Watch
    DeviceKind.HEADPHONES -> PhosphorIcons.Regular.Headphones
    else -> PhosphorIcons.Regular.DeviceMobile
}
