package dev.pampa.fluidify.wear.ui.more

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.Text
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowCircleUp
import com.adamglin.phosphoricons.regular.DownloadSimple
import com.adamglin.phosphoricons.regular.Info
import com.adamglin.phosphoricons.regular.Repeat
import com.adamglin.phosphoricons.regular.RepeatOnce
import com.adamglin.phosphoricons.regular.Shuffle
import com.adamglin.phosphoricons.regular.SpeakerSimpleHigh
import com.adamglin.phosphoricons.regular.Timer
import com.adamglin.phosphoricons.regular.UserCircle
import dev.antigravity.fluidengine.wear.components.FluidWearListRow
import dev.pampa.fluidify.wear.BuildConfig
import dev.pampa.fluidify.wear.R
import dev.pampa.fluidify.wear.playback.PlaybackControls
import dev.pampa.fluidify.wear.protocol.RepeatMode
import dev.pampa.fluidify.wear.protocol.UpdatePhase
import dev.pampa.fluidify.wear.protocol.UpdateStatus
import dev.pampa.fluidify.wear.system.Bridging
import dev.pampa.fluidify.wear.system.SurfacePrefs
import dev.pampa.fluidify.wear.ui.common.WatchList
import dev.pampa.fluidify.wear.ui.debug.GlassMeterPrefs
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
 * and versions, how loud Fluidify is on the watch face. Swiped to from the
 * right edge, as the user asked, so the left edge stays the system's back.
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
    surfaces: SurfacePrefs? = null,
    onSurfacesChanged: () -> Unit = {},
    glassMeter: GlassMeterPrefs? = null,
    standalone: dev.pampa.fluidify.wear.standalone.Standalone? = null,
    onDownloads: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val now by controls.nowPlaying.collectAsStateWithLifecycle()
    val snapshot = now.snapshot
    val update by (updater?.status ?: remember { MutableStateFlow<UpdateStatus?>(null) }).collectAsStateWithLifecycle()
    val phone by phoneVersion.collectAsStateWithLifecycle()
    var auto by remember { mutableStateOf(updater?.autoUpdate ?: true) }
    var icon by remember { mutableStateOf(surfaces?.ongoingIcon ?: true) }
    var bridged by remember { mutableStateOf(surfaces?.phoneNotifications ?: true) }
    var notificationsAllowed by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsAllowed = granted
        onSurfacesChanged()
    }
    val meterOn by (glassMeter?.enabled ?: remember { MutableStateFlow(false) }).collectAsStateWithLifecycle()
    val developer by (glassMeter?.developer ?: remember { MutableStateFlow(false) }).collectAsStateWithLifecycle()
    var speaker by remember { mutableStateOf(standalone?.prefs?.speakerAllowed ?: true) }
    var headphonePrompt by remember {
        mutableStateOf(
            (standalone?.prefs?.headphonePrompt ?: true) &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED,
        )
    }
    // Hearing headphones connect needs the Bluetooth permission, asked for the moment the
    // switch is turned on rather than at first launch.
    val askBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        headphonePrompt = granted
        standalone?.prefs?.headphonePrompt = granted
    }
    var cellular by remember { mutableStateOf(standalone?.prefs?.allowCellular ?: false) }
    val watchAuth by (standalone?.auth?.state ?: remember { MutableStateFlow(dev.pampa.fluidify.wear.standalone.AuthState.NOT_YET) })
        .collectAsStateWithLifecycle()

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
        if (surfaces != null) {
            item {
                SwitchButton(
                    checked = icon && notificationsAllowed,
                    onCheckedChange = { on ->
                        icon = on
                        surfaces.ongoingIcon = on
                        if (on && !notificationsAllowed) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        onSurfacesChanged()
                    },
                    label = { Text(stringResource(R.string.watch_face_icon)) },
                    secondaryLabel = {
                        Text(
                            stringResource(
                                if (icon && !notificationsAllowed) R.string.notifications_blocked else R.string.watch_face_icon_summary,
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                SwitchButton(
                    checked = bridged,
                    onCheckedChange = { on ->
                        bridged = on
                        surfaces.phoneNotifications = on
                        Bridging.apply(context, on)
                    },
                    label = { Text(stringResource(R.string.phone_notifications)) },
                    secondaryLabel = { Text(stringResource(R.string.phone_notifications_summary)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (onDownloads != null) {
            item {
                FluidWearListRow(
                    title = stringResource(R.string.downloads),
                    subtitle = stringResource(R.string.on_this_watch),
                    onClick = onDownloads,
                    leading = { Icon(PhosphorIcons.Regular.DownloadSimple, contentDescription = null, modifier = Modifier.size(22.dp)) },
                )
            }
        }
        if (standalone != null) {
            item {
                SwitchButton(
                    checked = speaker,
                    onCheckedChange = { on ->
                        speaker = on
                        standalone.prefs.speakerAllowed = on
                    },
                    label = { Text(stringResource(R.string.speaker_allowed)) },
                    secondaryLabel = { Text(stringResource(R.string.speaker_allowed_summary)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                SwitchButton(
                    checked = headphonePrompt,
                    onCheckedChange = { on ->
                        if (on && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                            askBluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT)
                        } else {
                            headphonePrompt = on
                            standalone.prefs.headphonePrompt = on
                        }
                    },
                    label = { Text(stringResource(R.string.headphone_prompt)) },
                    secondaryLabel = { Text(stringResource(R.string.headphone_prompt_summary)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // Only on a watch with a mobile radio; off until the listener says otherwise.
            if (standalone.network.hasCellular) {
                item {
                    SwitchButton(
                        checked = cellular,
                        onCheckedChange = { on ->
                            cellular = on
                            standalone.prefs.allowCellular = on
                            standalone.prefs.cellularOffered = true
                        },
                        label = { Text(stringResource(R.string.mobile_data)) },
                        secondaryLabel = { Text(stringResource(R.string.mobile_data_summary)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item {
                FluidWearListRow(
                    title = stringResource(R.string.watch_account),
                    subtitle = when (watchAuth) {
                        dev.pampa.fluidify.wear.standalone.AuthState.SIGNED_IN -> standalone.prefs.username ?: stringResource(R.string.on)
                        dev.pampa.fluidify.wear.standalone.AuthState.NOT_YET -> stringResource(R.string.watch_account_none)
                        dev.pampa.fluidify.wear.standalone.AuthState.SIGNED_OUT -> stringResource(R.string.engine_signed_out)
                    },
                    leading = { Icon(PhosphorIcons.Regular.UserCircle, contentDescription = null, modifier = Modifier.size(22.dp)) },
                )
            }
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
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            FluidWearListRow(
                title = stringResource(R.string.about),
                subtitle = stringResource(R.string.about_versions, BuildConfig.VERSION_NAME, phone ?: "–"),
                onClick = glassMeter?.takeIf { GlassMeterPrefs.available }?.let { prefs ->
                    {
                        if (prefs.tapVersion()) {
                            android.widget.Toast.makeText(context, R.string.developer_unlocked, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                leading = { Icon(PhosphorIcons.Regular.Info, contentDescription = null, modifier = Modifier.size(22.dp)) },
            )
        }
        if (glassMeter != null && developer) {
            item { ListHeader { Text(stringResource(R.string.developer)) } }
            item {
                SwitchButton(
                    checked = meterOn,
                    onCheckedChange = glassMeter::set,
                    label = { Text(stringResource(R.string.glass_diagnostics)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                FluidWearListRow(
                    title = stringResource(R.string.developer_leave),
                    onClick = glassMeter::leaveDeveloper,
                )
            }
        }
    }
}
