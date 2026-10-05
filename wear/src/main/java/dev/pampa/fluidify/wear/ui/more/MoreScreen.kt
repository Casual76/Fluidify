package dev.pampa.fluidify.wear.ui.more

import com.adamglin.phosphoricons.regular.Watch
import dev.pampa.fluidify.wear.protocol.logic.NowBarMode
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowCircleUp
import com.adamglin.phosphoricons.regular.ArrowClockwise
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
import dev.pampa.fluidify.wear.protocol.UpdateChannels
import dev.pampa.fluidify.wear.protocol.UpdatePhase
import dev.pampa.fluidify.wear.protocol.UpdateStatus
import dev.pampa.fluidify.wear.system.Bridging
import dev.pampa.fluidify.wear.system.FirstRunPrefs
import dev.pampa.fluidify.wear.system.SurfacePrefs
import dev.pampa.fluidify.wear.ui.common.LocalNotice
import dev.pampa.fluidify.wear.ui.theme.WearDimens
import dev.pampa.fluidify.wear.ui.common.WatchList
import dev.pampa.fluidify.wear.ui.debug.GlassMeterPrefs
import dev.pampa.fluidify.wear.ui.player.deviceIcon
import dev.pampa.fluidify.wear.update.UpdateChannelPrefs
import dev.pampa.fluidify.wear.update.WatchUpdater
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/** The phases in which an update is being fetched or installed: asking again then would only collide. */
private val WORKING_PHASES = setOf(UpdatePhase.RECEIVING, UpdatePhase.INSTALLING, UpdatePhase.AWAITING_CONFIRMATION)

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
    /** The mirror experiment was switched: tell the phone and redraw the watch face. */
    onMirrorChanged: () -> Unit = onSurfacesChanged,
    glassMeter: GlassMeterPrefs? = null,
    standalone: dev.pampa.fluidify.wear.standalone.Standalone? = null,
    onDownloads: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val notice = LocalNotice.current
    val developerUnlocked = stringResource(R.string.developer_unlocked)
    val now by controls.nowPlaying.collectAsStateWithLifecycle()
    val snapshot = now.snapshot
    val update by (updater?.status ?: remember { MutableStateFlow<UpdateStatus?>(null) }).collectAsStateWithLifecycle()
    val phone by phoneVersion.collectAsStateWithLifecycle()
    val app = context.applicationContext as? dev.pampa.fluidify.wear.WearApp
    // The line the phone follows, from its last hello, or from the one before that if there has not
    // been one in this run: read-only here, the setting is on the phone.
    val phoneHello by (app?.link?.phone ?: remember { MutableStateFlow<dev.pampa.fluidify.wear.protocol.Hello?>(null) }).collectAsStateWithLifecycle()
    val channelWord = remember(phoneHello) {
        phoneHello?.let { UpdateChannels.parse(it.updateChannel) } ?: UpdateChannelPrefs.word(context)
    }
    val check by (updater?.check ?: remember { MutableStateFlow<WatchUpdater.Check>(WatchUpdater.Check.Idle) }).collectAsStateWithLifecycle()
    val news by (updater?.news ?: remember { MutableStateFlow<WatchUpdater.News?>(null) }).collectAsStateWithLifecycle()
    // What the update just installed brought is folded away until asked for, and counts as read
    // once it has been opened and the page is left (or it is closed again).
    var newsOpen by remember { mutableStateOf(false) }
    val newsOpenNow by rememberUpdatedState(newsOpen)
    DisposableEffect(updater) {
        onDispose { if (newsOpenNow) updater?.dismissNews() }
    }
    var auto by remember { mutableStateOf(updater?.autoUpdate ?: true) }
    var nowBar by remember { mutableStateOf(surfaces?.nowBar ?: NowBarMode.AUTO) }
    var bridged by remember { mutableStateOf(surfaces?.phoneNotifications ?: true) }
    var mirror by remember { mutableStateOf(surfaces?.mirrorNowBar ?: false) }
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
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsAllowed = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                onSurfacesChanged()
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
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
        item { ListSubHeader { Text(stringResource(R.string.group_playback)) } }
        item {
            FluidWearListRow(
                title = stringResource(R.string.audio_output),
                subtitle = snapshot?.device?.name,
                onClick = onOutput,
                leading = { Icon(deviceIcon(snapshot?.device?.kind), contentDescription = null, modifier = Modifier.size(WearDimens.ListIcon)) },
            )
        }
        item {
            FluidWearListRow(
                title = stringResource(R.string.volume),
                subtitle = snapshot?.device?.volume?.let { stringResource(R.string.volume_percent, (it * 100).roundToInt()) },
                onClick = onVolume,
                leading = { Icon(PhosphorIcons.Regular.SpeakerSimpleHigh, contentDescription = null, modifier = Modifier.size(WearDimens.ListIcon)) },
            )
        }
        item {
            FluidWearListRow(
                title = stringResource(R.string.sleep_timer),
                subtitle = if (snapshot?.sleep != null) stringResource(R.string.on) else stringResource(R.string.off),
                onClick = onSleep,
                leading = { Icon(PhosphorIcons.Regular.Timer, contentDescription = null, modifier = Modifier.size(WearDimens.ListIcon)) },
            )
        }
        item {
            val shuffle = snapshot?.shuffle == true
            FluidWearListRow(
                title = stringResource(R.string.shuffle),
                subtitle = stringResource(if (shuffle) R.string.on else R.string.off),
                onClick = { controls.setShuffle(!shuffle) },
                leading = { Icon(PhosphorIcons.Regular.Shuffle, contentDescription = null, modifier = Modifier.size(WearDimens.ListIcon)) },
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
                        modifier = Modifier.size(WearDimens.ListIcon),
                    )
                },
            )
        }
        // The light is the player's own: it belongs with what the player does, not with the watch.
        item {
            val prefs = (context.applicationContext as? dev.pampa.fluidify.wear.WearApp)?.audioLightPreferences
            val enabled by (prefs?.enabled ?: remember { MutableStateFlow(true) }).collectAsStateWithLifecycle()
            SwitchButton(
                checked = enabled,
                onCheckedChange = { prefs?.setEnabled(it) },
                label = { Text(stringResource(R.string.audio_light_title)) },
                secondaryLabel = { Text(stringResource(R.string.audio_light_note)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item { ListSubHeader { Text(stringResource(R.string.group_watch)) } }
        if (surfaces != null) {
            item {
                // One row that steps through the three choices: they are a scale, from "only when
                // nothing else shows it" to "never", and a tap is all a wrist wants to spend on it.
                FluidWearListRow(
                    title = stringResource(R.string.now_bar),
                    subtitle = stringResource(
                        when {
                            nowBar != NowBarMode.NEVER && !notificationsAllowed -> R.string.notifications_blocked
                            nowBar == NowBarMode.AUTO -> R.string.now_bar_auto
                            nowBar == NowBarMode.ALWAYS -> R.string.now_bar_always
                            else -> R.string.now_bar_never
                        },
                    ),
                    onClick = {
                        val next = NowBarMode.entries[(nowBar.ordinal + 1) % NowBarMode.entries.size]
                        nowBar = next
                        surfaces.nowBar = next
                        if (next != NowBarMode.NEVER && !notificationsAllowed) {
                            val activity = context as? Activity
                            val asked = context.getSharedPreferences(FirstRunPrefs.NAME, android.content.Context.MODE_PRIVATE)
                                .getBoolean(FirstRunPrefs.KEY_ASKED_NOTIFICATIONS, false)
                            if (asked && activity != null && !activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                            } else askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        onSurfacesChanged()
                    },
                    leading = { Icon(PhosphorIcons.Regular.Watch, contentDescription = null, modifier = Modifier.size(WearDimens.ListIcon)) },
                )
            }
        }
        if (onDownloads != null) {
            item {
                FluidWearListRow(
                    title = stringResource(R.string.downloads),
                    subtitle = stringResource(R.string.on_this_watch),
                    onClick = onDownloads,
                    leading = { Icon(PhosphorIcons.Regular.DownloadSimple, contentDescription = null, modifier = Modifier.size(WearDimens.ListIcon)) },
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
                    leading = { Icon(PhosphorIcons.Regular.UserCircle, contentDescription = null, modifier = Modifier.size(WearDimens.ListIcon)) },
                )
            }
        }
        if (update != null || updater != null) item { ListSubHeader { Text(stringResource(R.string.group_updates)) } }
        // What the version that was just installed brought: the first thing, while there is news.
        news?.let { fresh ->
            item {
                FluidWearListRow(
                    title = stringResource(R.string.whats_new_in, fresh.version),
                    onClick = {
                        newsOpen = !newsOpen
                        if (!newsOpen) updater?.dismissNews()
                    },
                    leading = { Icon(PhosphorIcons.Regular.Info, contentDescription = null, modifier = Modifier.size(WearDimens.ListIcon)) },
                )
            }
            if (newsOpen) {
                item {
                    Text(
                        text = fresh.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
        }
        if (updater != null) {
            val status = update
            // Held back for the watch's own music: waiting, which is not receiving.
            val waiting = status?.phase == UpdatePhase.ACCEPT && status.reason == UpdateStatus.REASON_WAITING_PLAYBACK
            val working = waiting || status?.phase in WORKING_PHASES
            item {
                // "Version 1.7.0", with the line when it is not the usual one: the status of an
                // update comes over this when nothing is happening.
                val version = stringResource(R.string.update_version, BuildConfig.VERSION_NAME).let { text ->
                    if (channelWord == UpdateChannels.BETA) {
                        stringResource(R.string.two_parts, text, stringResource(R.string.update_channel_beta))
                    } else {
                        text
                    }
                }
                FluidWearListRow(
                    title = stringResource(R.string.update),
                    subtitle = when {
                        status == null -> version
                        waiting -> stringResource(R.string.update_waiting_playback)
                        else -> when (status.phase) {
                            UpdatePhase.ACCEPT -> stringResource(
                                if (status.reason == UpdateStatus.REASON_WIFI) R.string.update_downloading_wifi else R.string.update_receiving,
                            )
                            UpdatePhase.RECEIVING -> stringResource(
                                R.string.two_parts,
                                stringResource(
                                    if (status.reason == UpdateStatus.REASON_WIFI) R.string.update_downloading_wifi else R.string.update_receiving,
                                ),
                                stringResource(R.string.percent_value, (status.progress * 100).roundToInt()),
                            )
                            UpdatePhase.INSTALLING -> stringResource(R.string.update_installing)
                            UpdatePhase.AWAITING_CONFIRMATION -> stringResource(R.string.update_confirm)
                            UpdatePhase.FAILED -> stringResource(
                                if (updater.canRetry) R.string.update_retry_cached else R.string.update_failed,
                            )
                            // Done: the version it is now, not "receiving", which it never was again.
                            UpdatePhase.INSTALLED -> stringResource(R.string.update_current, BuildConfig.VERSION_NAME)
                            UpdatePhase.DECLINE -> when (status.reason) {
                                "already-current", "older" -> stringResource(R.string.update_current, BuildConfig.VERSION_NAME)
                                "busy" -> stringResource(R.string.update_busy)
                                // The person's own choice, and the switch below says so.
                                "auto-update-off" -> version
                                else -> stringResource(R.string.update_failed)
                            }
                        }
                    },
                    onClick = if (status?.phase == UpdatePhase.AWAITING_CONFIRMATION || status?.phase == UpdatePhase.FAILED && updater.canRetry) ({ updater.confirmOrRetry() }) else null,
                    leading = { Icon(PhosphorIcons.Regular.ArrowCircleUp, contentDescription = null, modifier = Modifier.size(WearDimens.ListIcon)) },
                )
            }
            // Asks the phone, and shows what it said: up to date, on its way, or that it is not there.
            item {
                FluidWearListRow(
                    title = stringResource(R.string.check_updates),
                    subtitle = when (val result = check) {
                        WatchUpdater.Check.Idle -> null
                        WatchUpdater.Check.Asking -> stringResource(R.string.update_checking)
                        is WatchUpdater.Check.UpToDate -> stringResource(R.string.update_current, result.version)
                        is WatchUpdater.Check.Coming -> stringResource(R.string.update_coming, result.version)
                        WatchUpdater.Check.Failed -> stringResource(R.string.update_check_failed)
                        WatchUpdater.Check.Unreachable -> stringResource(R.string.phone_unreachable)
                    },
                    // Not while one is being fetched or installed, nor while the last question is out.
                    onClick = if (working || check == WatchUpdater.Check.Asking) null else ({
                        updater.requestCheck { app?.link?.requestUpdateCheck() == true }
                    }),
                    leading = { Icon(PhosphorIcons.Regular.ArrowClockwise, contentDescription = null, modifier = Modifier.size(WearDimens.ListIcon)) },
                )
            }
            // Read-only: the line is the phone's setting, which this watch follows.
            item {
                FluidWearListRow(
                    title = stringResource(R.string.update_channel),
                    subtitle = stringResource(
                        if (channelWord == UpdateChannels.BETA) R.string.update_channel_beta else R.string.update_channel_stable,
                    ),
                    leading = { Icon(PhosphorIcons.Regular.Info, contentDescription = null, modifier = Modifier.size(WearDimens.ListIcon)) },
                )
            }
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
                            notice.show(developerUnlocked, failure = false)
                        }
                    }
                },
                leading = { Icon(PhosphorIcons.Regular.Info, contentDescription = null, modifier = Modifier.size(WearDimens.ListIcon)) },
            )
        }
        if (glassMeter != null && developer) {
            item { ListSubHeader { Text(stringResource(R.string.developer)) } }
            item {
                SwitchButton(
                    checked = meterOn,
                    onCheckedChange = glassMeter::set,
                    label = { Text(stringResource(R.string.glass_diagnostics)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (surfaces != null) {
                item {
                    SwitchButton(
                        checked = mirror,
                        onCheckedChange = { on ->
                            mirror = on
                            surfaces.mirrorNowBar = on
                            onMirrorChanged()
                        },
                        label = { Text(stringResource(R.string.mirror_now_bar)) },
                        secondaryLabel = { Text(stringResource(R.string.mirror_now_bar_summary)) },
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
            item {
                FluidWearListRow(
                    title = stringResource(R.string.developer_leave),
                    onClick = glassMeter::leaveDeveloper,
                )
            }
        }
    }
}
