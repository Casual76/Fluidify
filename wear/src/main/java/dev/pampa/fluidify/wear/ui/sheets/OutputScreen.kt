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
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Check
import dev.antigravity.fluidengine.wear.components.FluidWearListRow
import dev.pampa.fluidify.wear.R
import androidx.compose.ui.platform.LocalContext
import dev.pampa.fluidify.wear.playback.PlaybackControls
import dev.pampa.fluidify.wear.protocol.DeviceList
import dev.pampa.fluidify.wear.ui.common.WatchList
import dev.pampa.fluidify.wear.ui.common.noticeItem
import dev.pampa.fluidify.wear.ui.player.deviceIcon

/**
 * Where the sound comes out: the phone, or any Spotify Connect device the phone
 * can see (the computer, a speaker, the TV). Moving it is the phone's job; the
 * watch only says where.
 */
@Composable
fun OutputScreen(
    controls: PlaybackControls,
    load: suspend () -> Result<DeviceList>,
    onChosen: () -> Unit,
) {
    val context = LocalContext.current
    var list by remember { mutableStateOf<DeviceList?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        load().fold(onSuccess = { list = it }, onFailure = { failed = true })
    }
    WatchList(title = stringResource(R.string.audio_output)) {
        val current = list
        when {
            current == null && failed -> noticeItem(context.getString(R.string.couldnt_load))
            current == null -> noticeItem(context.getString(R.string.loading))
            else -> current.devices.forEach { device ->
                item(key = device.id) {
                    val active = device.id == current.activeId
                    FluidWearListRow(
                        title = device.name,
                        subtitle = if (device.isThisPhone) stringResource(R.string.this_phone) else null,
                        onClick = {
                            if (!active) controls.transfer(device.id)
                            onChosen()
                        },
                        leading = { Icon(deviceIcon(device.kind), contentDescription = null, modifier = Modifier.size(22.dp)) },
                        trailing = if (active) {
                            { Icon(PhosphorIcons.Regular.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}
