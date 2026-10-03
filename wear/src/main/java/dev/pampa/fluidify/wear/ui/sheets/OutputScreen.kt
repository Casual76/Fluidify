package dev.pampa.fluidify.wear.ui.sheets

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.Text
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Headphones
import com.adamglin.phosphoricons.regular.Plus
import com.adamglin.phosphoricons.regular.SpeakerHigh
import dev.antigravity.fluidengine.wear.components.FluidWearListRow
import dev.pampa.fluidify.wear.R
import androidx.compose.ui.platform.LocalContext
import dev.pampa.fluidify.wear.playback.PlaybackControls
import dev.pampa.fluidify.wear.protocol.DeviceList
import dev.pampa.fluidify.wear.standalone.LocalOutput
import dev.pampa.fluidify.wear.ui.common.WatchList
import dev.pampa.fluidify.wear.ui.common.noticeItem
import dev.pampa.fluidify.wear.ui.player.deviceIcon

/**
 * Where the sound comes out: the watch itself (its speaker, or headphones
 * connected to it), the phone, or any Spotify Connect device the phone can see
 * (the computer, a speaker, the TV). Moving between devices is Spotify
 * Connect's job; the watch only says where.
 *
 * @param watchOutputs the watch's own outputs; empty hides the section.
 * @param watchActive the watch is the one playing.
 * @param watchConnectId the watch's own Connect id, left out of the phone's list
 *   because it is already the section above.
 */
@Composable
fun OutputScreen(
    controls: PlaybackControls,
    load: suspend () -> Result<DeviceList>,
    onChosen: () -> Unit,
    watchOutputs: List<LocalOutput> = emptyList(),
    watchActive: Boolean = false,
    watchConnectId: String? = null,
    onWatchOutput: (LocalOutput) -> Unit = {},
    onConnectHeadphones: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var list by remember { mutableStateOf<DeviceList?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        load().fold(onSuccess = { list = it }, onFailure = { failed = true })
    }
    WatchList(title = stringResource(R.string.audio_output)) {
        if (watchOutputs.isNotEmpty() || onConnectHeadphones != null) {
            item { ListSubHeader { Text(stringResource(R.string.on_this_watch)) } }
        }
        watchOutputs.forEach { output ->
            item(key = "watch-${output.id}") {
                FluidWearListRow(
                    title = if (output.kind == LocalOutput.Kind.SPEAKER) stringResource(R.string.watch_speaker) else output.name,
                    selected = watchActive && output == watchOutputs.firstOrNull(),
                    onClick = {
                        // Closed first: choosing the watch may open a question of its own.
                        onChosen()
                        onWatchOutput(output)
                    },
                    leading = {
                        Icon(
                            if (output.kind == LocalOutput.Kind.SPEAKER) PhosphorIcons.Regular.SpeakerHigh else PhosphorIcons.Regular.Headphones,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                        )
                    },
                )
            }
        }
        if (onConnectHeadphones != null) {
            item(key = "connect-headphones") {
                FluidWearListRow(
                    title = stringResource(R.string.connect_headphones),
                    onClick = onConnectHeadphones,
                    leading = { Icon(PhosphorIcons.Regular.Plus, contentDescription = null, modifier = Modifier.size(22.dp)) },
                )
            }
        }
        val current = list
        item { ListSubHeader { Text(stringResource(R.string.other_devices)) } }
        when {
            current == null && failed -> noticeItem(context.getString(R.string.couldnt_load))
            current == null -> noticeItem(context.getString(R.string.loading))
            else -> current.devices.filter { it.id != watchConnectId }.forEach { device ->
                item(key = device.id) {
                    val active = device.id == current.activeId && !watchActive
                    FluidWearListRow(
                        title = device.name,
                        selected = active,
                        subtitle = when {
                            device.isThisPhone -> stringResource(R.string.this_phone)
                            active && device.canSetVolume -> stringResource(R.string.volume_percent, (device.volume * 100).toInt())
                            else -> null
                        },
                        onClick = {
                            if (!active) controls.transfer(device.id)
                            onChosen()
                        },
                        leading = { Icon(deviceIcon(device.kind), contentDescription = null, modifier = Modifier.size(22.dp)) },
                    )
                }
            }
        }
    }
}
