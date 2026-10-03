package dev.lelonio.square.ui.settings

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lelonio.square.BuildConfig
import dev.lelonio.square.R
import dev.lelonio.square.SquareApplication
import dev.lelonio.square.wear.WatchUpdateCoordinator.State

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
    val watch by updates.watch.collectAsStateWithLifecycle()
    val state by updates.state.collectAsStateWithLifecycle()
    var auto by remember { mutableStateOf(updates.autoUpdate) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) updates.pushFile(uri)
    }

    Section(stringResource(R.string.page_watch)) {
        val current = watch
        if (current == null) {
            InfoRow(stringResource(R.string.watch_status), stringResource(R.string.watch_none))
        } else {
            InfoRow(
                stringResource(R.string.watch_status),
                current.name.ifBlank { stringResource(R.string.page_watch) },
            )
            RowDivider()
            InfoRow(stringResource(R.string.watch_version), current.hello.versionName)
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

        WatchDownloadsRows(app)

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
    is State.Downloading -> stringResource(R.string.watch_update_downloading, state.version)
    is State.Offered -> stringResource(R.string.watch_update_offered, state.version)
    is State.Sending -> stringResource(R.string.watch_update_sending, state.version)
    is State.Installing -> stringResource(R.string.watch_update_installing, state.version)
    is State.AwaitingConfirmation -> stringResource(R.string.watch_update_confirm)
    is State.Installed -> stringResource(R.string.watch_update_installed, state.version)
    is State.Failed -> stringResource(R.string.watch_update_failed, state.reason)
}

/**
 * What the watch keeps for offline listening, from the phone: how much, how
 * full the watch is, and the two choices that decide how tracks get there.
 * The list itself is edited from each playlist's page ("Scarica sull'orologio").
 */
@Composable
private fun WatchDownloadsRows(app: SquareApplication) {
    val remote = app.wearBridge.watchDownloads
    val status by remote.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { remote.refresh() }
    val current = status ?: return

    RowDivider()
    InfoRow(
        stringResource(R.string.watch_downloads),
        if (current.owners.isEmpty()) {
            stringResource(R.string.watch_downloads_none)
        } else {
            stringResource(
                R.string.watch_downloads_summary,
                current.owners.sumOf { it.done },
                current.owners.sumOf { it.tracks },
                android.text.format.Formatter.formatShortFileSize(LocalContext.current, current.bytesUsed),
                android.text.format.Formatter.formatShortFileSize(LocalContext.current, current.bytesFree),
            )
        },
    )
    current.owners.forEach { owner ->
        RowDivider()
        InfoRow(owner.title.ifBlank { owner.uri }, "${owner.done}/${owner.tracks}")
    }
    current.paused?.let { reason ->
        RowDivider()
        InfoRow(
            stringResource(R.string.watch_downloads_paused),
            stringResource(if (reason == "storage") R.string.watch_downloads_paused_storage else R.string.watch_downloads_paused_offline),
        )
    }

    RowDivider()
    InfoRow(stringResource(R.string.watch_download_quality), "${current.qualityKbps} kbps")
    listOf(96, 160, 320).forEach { kbps ->
        ChoiceRow("$kbps kbps", selected = current.qualityKbps == kbps) {
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
}

