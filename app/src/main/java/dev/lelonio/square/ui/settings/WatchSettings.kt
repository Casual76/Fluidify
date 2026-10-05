package dev.lelonio.square.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidSpinner
import dev.lelonio.square.ui.theme.InkDim
import dev.lelonio.square.wear.watchUpdateFailureRes
import dev.pampa.fluidify.wear.protocol.DownloadRequest
import dev.pampa.fluidify.wear.protocol.logic.TransferPreference
import kotlinx.coroutines.launch

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lelonio.square.BuildConfig
import dev.lelonio.square.R
import dev.lelonio.square.SquareApplication
import dev.lelonio.square.wear.WatchUpdateCoordinator.State
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A request, from outside the settings (the watch-update notification), to open them on the Watch
 * page.
 *
 * A flag the settings screen takes and clears, not an argument threaded through the activity and
 * the navigation: the screen may not exist yet when the notification is tapped (it is composed once
 * the route is reached), and a flag set before then is still there when it is.
 */
internal object WatchPageRequest {
    /**
     * The intent action that asks the main activity to open the settings on the Watch page.
     *
     * Named once, here, for the two notifications that send it (the update's and the transfer's)
     * and the activity that reads it, so that the spelling cannot drift between them.
     */
    const val ACTION = "dev.pampa.fluidify.WATCH_UPDATES"

    private val _pending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    fun request() {
        _pending.value = true
    }

    fun consume() {
        _pending.value = false
    }
}

/**
 * The watch, from the phone: which one, which version, and keeping it current.
 *
 * Everything the watch app needs from a human that a 200 dp screen is the wrong
 * place for: the version it runs, sending it an update, and whether updates go
 * to it on their own. Grows with the companion (downloads, storage, the
 * installer) in later releases.
 */
@Composable
internal fun WatchSection() {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as SquareApplication }
    val updates = app.wearBridge.updates
    // The look for the watch takes a few seconds (it waits for a hello, up to six). Until it has
    // finished, "no watch" is a claim the page cannot yet make: it said so for the whole wait and
    // then changed its mind when the hello arrived. [attempt] is the Retry row.
    var looking by remember { mutableStateOf(true) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(app, attempt) {
        looking = true
        app.wearBridge.refreshWatchLink()
        looking = false
    }
    val watch by updates.watch.collectAsStateWithLifecycle()
    val state by updates.state.collectAsStateWithLifecycle()
    var auto by remember { mutableStateOf(updates.autoUpdate) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) updates.pushFile(uri)
    }

    Section(stringResource(R.string.page_watch)) {
        val current = watch
        if (current != null) {
            InfoRow(
                stringResource(R.string.watch_status),
                current.name.ifBlank { stringResource(R.string.page_watch) },
            )
            RowDivider()
            InfoRow(stringResource(R.string.watch_version), current.hello.versionName)
        } else if (looking) {
            ProgressRow(stringResource(R.string.watch_looking))
        } else {
            // Nothing answered. Said, with the way to ask again, rather than left as a status
            // that looks final: the watch may simply have been out of Bluetooth range.
            InfoRow(stringResource(R.string.watch_status), stringResource(R.string.watch_none))
            Text(
                stringResource(R.string.watch_none_hint),
                style = MaterialTheme.typography.bodySmall,
                color = InkDim,
                modifier = Modifier.padding(horizontal = 18.dp).padding(bottom = 12.dp),
            )
            ActionRow(stringResource(R.string.retry), destructive = false) { attempt++ }
        }

        RowDivider()
        InfoRow(stringResource(R.string.watch_update), describe(state))
        if (current != null && state !is State.Checking && state !is State.Downloading && state !is State.Sending) {
            ActionRow(stringResource(R.string.watch_update_now), destructive = false) {
                updates.checkAndPush()
            }
        }

        RowDivider()
        SwitchRow(
            label = stringResource(R.string.watch_auto_update),
            note = stringResource(R.string.watch_auto_update_note),
            checked = auto,
            onCheckedChange = {
                auto = it
                updates.autoUpdate = it
            },
        )

        WatchDownloadsRows(app, watchKnown = current != null)

        // Only while no watch has the app yet (or for reinstalling one in development).
        if (current == null || BuildConfig.BUILD_TYPE != "release") WatchInstallRows(app)

        // While developing: any build, straight from the phone, without a computer.
        if (BuildConfig.BUILD_TYPE != "release" && current != null) {
            RowDivider()
            ActionRow(stringResource(R.string.watch_push_apk), destructive = false) {
                picker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
            }
        }
    }
}

@Composable
private fun describe(state: State): String = when (state) {
    State.Idle -> stringResource(R.string.watch_update_idle)
    State.Checking -> stringResource(R.string.watch_update_checking)
    is State.UpToDate -> stringResource(R.string.watch_update_current, state.version)
    is State.Available -> stringResource(R.string.watch_update_available, state.version)
    is State.Downloading -> withProgress(stringResource(R.string.watch_update_downloading, state.version), state.progress)
    is State.Offered -> stringResource(R.string.watch_update_offered, state.version)
    is State.Sending -> withProgress(stringResource(R.string.watch_update_sending, state.version), state.progress)
    is State.Installing -> stringResource(R.string.watch_update_installing, state.version)
    is State.AwaitingConfirmation -> stringResource(R.string.watch_update_confirm)
    is State.Installed -> stringResource(R.string.watch_update_installed, state.version)
    // Never the token itself: watchUpdateFailureRes has a generic line for the ones it does not
    // know, so that an exception message or an internal word is not what the person reads.
    is State.Failed -> stringResource(watchUpdateFailureRes(state.reason))
}

/** [text] with how far along it is, when that is known. */
@Composable
private fun withProgress(text: String, progress: Float?): String =
    if (progress == null) {
        text
    } else {
        stringResource(R.string.watch_update_progress, text, (progress.coerceIn(0f, 1f) * 100).toInt())
    }

/** A line of status with the spinner in front, for something that is being looked for. */
@Composable
private fun ProgressRow(label: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FluidSpinner(size = 18.dp, color = InkDim)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = InkDim)
    }
}

/**
 * What the watch keeps for offline listening, from the phone: how much, how
 * full the watch is, and the two choices that decide how tracks get there.
 * The list itself is edited from each playlist's page ("Scarica sull'orologio").
 */
@Composable
private fun WatchDownloadsRows(app: SquareApplication, watchKnown: Boolean) {
    val remote = app.wearBridge.watchDownloads
    val status by remote.status.collectAsStateWithLifecycle()
    val waiting by remote.waiting.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // Whether the read of what the watch keeps is still going, and the Retry that starts it again.
    var reading by remember { mutableStateOf(true) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        reading = true
        remote.refresh()
        reading = false
    }
    val current = status
    if (current == null) {
        // Nothing to draw for a phone with no watch at all. With one, a page that simply stops
        // after the update rows reads as a page with nothing to say, when the truth is that the
        // watch has not reported yet (or the read is still on its way).
        if (watchKnown) {
            RowDivider()
            if (reading) {
                ProgressRow(stringResource(R.string.watch_downloads_reading))
            } else {
                InfoRow(stringResource(R.string.watch_downloads), stringResource(R.string.watch_downloads_unreported))
                ActionRow(stringResource(R.string.retry), destructive = false) { attempt++ }
            }
        }
        WaitingNote(waiting)
        return
    }

    RowDivider()
    InfoRow(
        stringResource(R.string.watch_downloads),
        if (current.owners.isEmpty()) {
            stringResource(R.string.watch_downloads_none)
        } else {
            val total = current.owners.sumOf { it.tracks }
            pluralStringResource(
                R.plurals.watch_downloads_summary,
                total,
                current.owners.sumOf { it.done },
                total,
                android.text.format.Formatter.formatShortFileSize(LocalContext.current, current.bytesUsed),
                android.text.format.Formatter.formatShortFileSize(LocalContext.current, current.bytesFree),
            )
        },
    )
    current.owners.forEach { owner ->
        RowDivider()
        // A blank title is a playlist the watch has not been told the name of: not something to
        // read out as a spotify: address.
        InfoRow(owner.title.ifBlank { stringResource(R.string.unknown) }, "${owner.done}/${owner.tracks}")
    }
    current.paused?.let { reason ->
        RowDivider()
        InfoRow(
            stringResource(R.string.watch_downloads_paused),
            stringResource(if (reason == "storage") R.string.watch_downloads_paused_storage else R.string.watch_downloads_paused_offline),
        )
    }

    RowDivider()
    InfoRow(stringResource(R.string.watch_download_quality), stringResource(R.string.kbps_value, current.qualityKbps))
    listOf(96, 160, 320).forEach { kbps ->
        ChoiceRow(stringResource(R.string.kbps_value, kbps), selected = current.qualityKbps == kbps) {
            scope.launch { remote.request(DownloadRequest(qualityKbps = kbps)) }
        }
    }
    RowDivider()
    SwitchRow(
        label = stringResource(R.string.watch_transfer_bluetooth),
        note = stringResource(R.string.watch_transfer_bluetooth_note),
        checked = current.preference == TransferPreference.BLUETOOTH_FIRST,
        onCheckedChange = { bluetooth ->
            scope.launch {
                remote.request(
                    DownloadRequest(preference = if (bluetooth) TransferPreference.BLUETOOTH_FIRST else TransferPreference.WIFI_FIRST),
                )
            }
        },
    )
    WaitingNote(waiting)
}

/**
 * What the watch has not heard yet.
 *
 * A change made while the watch is out of reach is kept and goes with its next hello (see
 * WatchDownloadsRemote), and nothing on screen said so: the list showed the change at once, as if
 * it had been made, and the person had no way to know it was only waiting.
 */
@Composable
private fun WaitingNote(count: Int) {
    if (count <= 0) return
    Text(
        pluralStringResource(R.plurals.watch_downloads_waiting, count, count),
        style = MaterialTheme.typography.bodySmall,
        color = InkDim,
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
    )
}

