package dev.lelonio.square.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowClockwise
import com.adamglin.phosphoricons.regular.Desktop
import com.adamglin.phosphoricons.regular.DeviceMobile
import com.adamglin.phosphoricons.regular.SpeakerHifi
import com.adamglin.phosphoricons.regular.SpeakerHigh
import com.adamglin.phosphoricons.regular.SpeakerSimpleLow
import com.adamglin.phosphoricons.regular.Television
import dev.antigravity.fluidengine.ui.fluid.fluidRowPressable
import dev.lelonio.square.R
import dev.lelonio.square.ui.MainViewModel

/**
 * The Spotify Connect device list.
 *
 * The list comes from the engine's cluster rather than from the Web API:
 * Spotify pushes an update to every device whenever anything changes, so a list
 * left open while playback moved corrects itself, where one that was fetched
 * goes on naming the device it was fetched about. The Web API is the fallback
 * for an account whose application can answer when the cluster has not arrived.
 *
 * Switching device is a real transfer, not a local mute: the audio moves, and
 * this app stops decoding. That is the whole point of Connect, and it is why
 * the active device is marked rather than assumed to be this one.
 *
 * Drawn without any glass of its own. It is shown inside the player's panel,
 * which is already a glass surface, and a pane of glass on each row would be
 * refracting the pane behind it.
 */
@Composable
fun DeviceList(
    state: MainViewModel.DevicesState,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
    onSetVolume: (String, Int) -> Unit = { _, _ -> },
) {
    Column(Modifier.padding(vertical = 12.dp)) {
        // No title. The player's own bar is already saying "Play on" over this
        // panel, and a panel that repeats its own heading reads as two panels.
        Box(
            Modifier
                .fillMaxWidth()
                .padding(end = 14.dp, bottom = 4.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .fluidRowPressable(onClick = onRefresh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    PhosphorIcons.Regular.ArrowClockwise,
                    contentDescription = stringResource(R.string.refresh),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        when {
            state.loading && state.devices.isEmpty() -> Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 26.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    strokeWidth = 2.dp,
                )
            }

            state.error != null -> Text(
                state.error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
            )

            state.devices.isEmpty() -> Text(
                stringResource(R.string.no_devices),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
            )

            else -> Column(
                Modifier
                    .heightIn(max = 340.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // This phone at the top, always.
                //
                // Not because it is more important than a speaker, but because
                // it is the one row whose meaning depends on where it is: a
                // list that reorders itself as playback moves makes "bring it
                // back here" a different tap every time, and that tap is the
                // one made in a hurry.
                val ordered = remember(state.devices) {
                    state.devices.sortedWith(
                        compareByDescending<MainViewModel.SpotifyDevice> { it.isThisPhone }
                            .thenByDescending { it.isActive }
                            .thenBy { it.name.lowercase() },
                    )
                }
                // Whether the music is on some other device, which is what
                // makes "play here" an offer rather than a description of where
                // you already are. A phone holding a paused queue is not
                // somewhere else, and saying so sent people looking for music
                // that was under their thumb.
                val away = ordered.any { it.isActive && !it.isThisPhone }
                ordered.forEach { device ->
                    DeviceRow(
                        device = device,
                        switching = state.switchingTo == device.id,
                        playingAway = away,
                        onClick = { onSelect(device.id) },
                        onSetVolume = { onSetVolume(device.id, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: MainViewModel.SpotifyDevice,
    /** Whether this is the row the playback is moving to right now. */
    switching: Boolean,
    /** Whether the account is playing on a device that is not this phone. */
    playingAway: Boolean,
    onClick: () -> Unit,
    onSetVolume: (Int) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSurface
    val inkDim = MaterialTheme.colorScheme.onSurfaceVariant

    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .fluidRowPressable(
                    enabled = !device.isActive && !switching,
                    onClick = onClick,
                )
                .padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                iconFor(device.type),
                contentDescription = null,
                // The playing device is the accent's one job on this sheet:
                // every other row has to stay legible over whatever is behind
                // the glass.
                tint = if (device.isActive) accent else inkDim,
                modifier = Modifier.size(22.dp),
            )
            Column(
                Modifier
                    .padding(start = 16.dp)
                    .weight(1f),
            ) {
                Text(
                    device.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (device.isActive) accent else ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        switching -> stringResource(R.string.switching)
                        device.isActive && device.isThisPhone ->
                            stringResource(R.string.playing_here)
                        device.isActive -> stringResource(R.string.now_playing)
                        // The one row whose subtitle is an offer rather than a
                        // description: the music is somewhere else, and this is
                        // the phone in your hand.
                        device.isThisPhone && playingAway -> stringResource(R.string.play_here)
                        device.isThisPhone -> stringResource(R.string.this_device)
                        else -> device.type
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (playingAway && device.isThisPhone) accent else inkDim,
                    maxLines = 1,
                )
            }

            // The one piece of feedback a handover has. Moving playback takes a
            // moment, and without this the row simply sat there, so it was
            // pressed again and again.
            if (switching) {
                CircularProgressIndicator(
                    color = inkDim,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // Volume, and only for the device that is playing.
        //
        // A slider under every row would be four ways to change a number that
        // only means anything on one of them: the others are speakers standing
        // silent, and turning one of those up does nothing you can hear until
        // the music arrives, by which point you have forgotten you did it.
        if (device.isActive && device.volume >= 0 && !switching) {
            DeviceVolume(device.volume, onSetVolume)
        }
    }
}

/**
 * The active device's volume.
 *
 * Held locally while the finger is down and only then sent. The cluster
 * answers a beat after each change, and letting every one of those answers
 * write back into the slider is what makes it stutter under the thumb; the
 * device's own value is picked up again the moment the gesture ends.
 */
@Composable
private fun DeviceVolume(raw: Int, onSetVolume: (Int) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var held by remember { mutableFloatStateOf(0f) }
    val fraction = if (dragging) held else raw / MAX_VOLUME.toFloat()

    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 22.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            PhosphorIcons.Regular.SpeakerSimpleLow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Slider(
            value = fraction.coerceIn(0f, 1f),
            onValueChange = {
                dragging = true
                held = it
                onSetVolume((it * MAX_VOLUME).toInt())
            },
            onValueChangeFinished = { dragging = false },
            modifier = Modifier
                .weight(1f)
                .height(28.dp),
        )
        Spacer(Modifier.width(10.dp))
        Icon(
            PhosphorIcons.Regular.SpeakerHigh,
            contentDescription = stringResource(R.string.volume),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** librespot's volume scale, which is the one the cluster speaks. */
private const val MAX_VOLUME = 65_535

/** Spotify's device types, as far as they map onto icons worth telling apart. */
private fun iconFor(type: String): androidx.compose.ui.graphics.vector.ImageVector =
    when (type.lowercase()) {
        "computer" -> PhosphorIcons.Regular.Desktop
        "smartphone", "tablet" -> PhosphorIcons.Regular.DeviceMobile
        "tv", "castvideo" -> PhosphorIcons.Regular.Television
        else -> PhosphorIcons.Regular.SpeakerHifi
    }
