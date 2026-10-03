package dev.pampa.fluidify.wear.ui.more

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowCircleUp
import com.adamglin.phosphoricons.regular.Info
import com.adamglin.phosphoricons.regular.Repeat
import com.adamglin.phosphoricons.regular.RepeatOnce
import com.adamglin.phosphoricons.regular.Shuffle
import com.adamglin.phosphoricons.regular.SpeakerSimpleHigh
import com.adamglin.phosphoricons.regular.Timer
import dev.antigravity.fluidengine.wear.components.FluidWearListRow
import dev.pampa.fluidify.wear.BuildConfig
import dev.pampa.fluidify.wear.R
import dev.pampa.fluidify.wear.playback.PlaybackControls
import dev.pampa.fluidify.wear.protocol.RepeatMode
import dev.pampa.fluidify.wear.protocol.UpdatePhase
import dev.pampa.fluidify.wear.protocol.UpdateStatus
import dev.pampa.fluidify.wear.ui.common.WatchList
import dev.pampa.fluidify.wear.ui.player.deviceIcon
import dev.pampa.fluidify.wear.update.WatchUpdater
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/**
 * "Altro": the page to the right of the player.
 *
 * Everything a player has that does not deserve a place on a 200 dp circle:
 * where the sound comes out, the volume, the timer, shuffle and repeat, updates
 * and versions. Swiped to from the right edge, as the user asked, so the left
 * edge stays the system's back.
 */
@Composable
fun MoreScreen(
    controls: PlaybackControls,
    onOutput: () -> Unit,
    modifier: Modifier = Modifier,
    onVolume: () -> Unit = {},
    onSleep: () -> Unit = {},
    updater: WatchUpdater? = null,
    phoneVersion: StateFlow<String?> = remember { MutableStateFlow(null) },
) {
    val now by controls.nowPlaying.collectAsStateWithLifecycle()
    val snapshot = now.snapshot
    val update by (updater?.status ?: remember { MutableStateFlow<UpdateStatus?>(null) }).collectAsStateWithLifecycle()
    val phone by phoneVersion.collectAsStateWithLifecycle()
    var auto by remember { mutableStateOf(updater?.autoUpdate ?: true) }

    WatchList(title = stringResource(R.string.more), modifier = modifier) {
        item {
            FluidWearListRow(
                title = stringResource(R.string.audio_output),
                subtitle = snapshot?.device?.name,
                onClick = onOutput,
                leading = { Icon(deviceIcon(snapshot?.device?.kind), contentDescription = null, modifier = Modifier.size(22.dp)) },
            )
        }
        item {
            FluidWearListRow(
                title = stringResource(R.string.volume),
                subtitle = snapshot?.device?.volume?.let { "${(it * 100).roundToInt()}%" },
                onClick = onVolume,
                leading = { Icon(PhosphorIcons.Regular.SpeakerSimpleHigh, contentDescription = null, modifier = Modifier.size(22.dp)) },
            )
        }
        item {
            FluidWearListRow(
                title = stringResource(R.string.sleep_timer),
                subtitle = if (snapshot?.sleep != null) stringResource(R.string.on) else stringResource(R.string.off),
                onClick = onSleep,
                leading = { Icon(PhosphorIcons.Regular.Timer, contentDescription = null, modifier = Modifier.size(22.dp)) },
            )
        }
        item {
            val shuffle = snapshot?.shuffle == true
            FluidWearListRow(
                title = stringResource(R.string.shuffle),
                subtitle = stringResource(if (shuffle) R.string.on else R.string.off),
                onClick = { controls.setShuffle(!shuffle) },
                leading = { Icon(PhosphorIcons.Regular.Shuffle, contentDescription = null, modifier = Modifier.size(22.dp)) },
            )
        }
        item {
            val repeat = snapshot?.repeat ?: RepeatMode.OFF
            FluidWearListRow(
                title = stringResource(R.string.repeat),
                subtitle = stringResource(
                    when (repeat) {
                        RepeatMode.OFF -> R.string.off
                        RepeatMode.ALL -> R.string.on
                        RepeatMode.ONE -> R.string.repeat_one
                    },
                ),
                onClick = {
                    controls.setRepeat(
                        when (repeat) {
                            RepeatMode.OFF -> RepeatMode.ALL
                            RepeatMode.ALL -> RepeatMode.ONE
                            RepeatMode.ONE -> RepeatMode.OFF
                        },
                    )
                },
                leading = {
                    Icon(
                        if (repeat == RepeatMode.ONE) PhosphorIcons.Regular.RepeatOnce else PhosphorIcons.Regular.Repeat,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                },
            )
        }
        update?.let { status ->
            item {
                FluidWearListRow(
                    title = stringResource(R.string.update),
                    subtitle = stringResource(
                        when (status.phase) {
                            UpdatePhase.ACCEPT, UpdatePhase.RECEIVING -> R.string.update_receiving
                            UpdatePhase.INSTALLING -> R.string.update_installing
                            UpdatePhase.AWAITING_CONFIRMATION -> R.string.update_confirm
                            UpdatePhase.FAILED -> R.string.update_failed
                            else -> R.string.update_receiving
                        },
                    ),
                    leading = { Icon(PhosphorIcons.Regular.ArrowCircleUp, contentDescription = null, modifier = Modifier.size(22.dp)) },
                )
            }
        }
        if (updater != null) {
            item {
                SwitchButton(
                    checked = auto,
                    onCheckedChange = {
                        auto = it
                        updater.autoUpdate = it
                    },
                    label = { Text(stringResource(R.string.auto_update)) },
                    modifier = Modifier,
                )
            }
        }
        item {
            FluidWearListRow(
                title = stringResource(R.string.about),
                subtitle = stringResource(R.string.about_versions, BuildConfig.VERSION_NAME, phone ?: "–"),
                leading = { Icon(PhosphorIcons.Regular.Info, contentDescription = null, modifier = Modifier.size(22.dp)) },
            )
        }
    }
}
