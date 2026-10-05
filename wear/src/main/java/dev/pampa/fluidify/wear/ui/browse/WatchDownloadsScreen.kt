package dev.pampa.fluidify.wear.ui.browse

import android.text.format.Formatter
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Gauge
import com.adamglin.phosphoricons.regular.HardDrives
import com.adamglin.phosphoricons.regular.Playlist
import dev.antigravity.fluidengine.wear.components.FluidWearListRow
import dev.pampa.fluidify.wear.R
import dev.pampa.fluidify.wear.downloads.WatchDownloads
import dev.pampa.fluidify.wear.protocol.logic.TransferPreference
import dev.pampa.fluidify.wear.ui.common.WatchList
import dev.pampa.fluidify.wear.ui.common.noticeItem
import dev.pampa.fluidify.wear.ui.theme.WearDimens

/**
 * What the watch keeps, and how it keeps it: the playlists and albums with
 * their progress, the space they take, the quality and the transport rule.
 * Reached from the library ("Download · su questo orologio") and from Altro.
 */
@Composable
fun WatchDownloadsScreen(downloads: WatchDownloads, onOpen: (String, String) -> Unit) {
    val context = LocalContext.current
    val status by downloads.status.collectAsStateWithLifecycle()
    val emptyText = stringResource(R.string.watch_downloads_empty)
    val unavailableText = context.resources.getQuantityString(R.plurals.unavailable_tracks, status.unavailable, status.unavailable)
    val pausedStorage = stringResource(R.string.paused_storage)
    val pausedOffline = stringResource(R.string.paused_offline)
    val waitingText = stringResource(R.string.waiting_tracks, status.waiting)
    val storageUsed = stringResource(
        R.string.storage_used_free,
        Formatter.formatShortFileSize(context, status.bytesUsed),
        Formatter.formatShortFileSize(context, status.bytesFree),
    )
    val storageText = when {
        status.paused == WatchDownloads.PAUSED_STORAGE -> stringResource(R.string.two_parts, storageUsed, pausedStorage)
        status.paused == WatchDownloads.PAUSED_OFFLINE -> stringResource(R.string.two_parts, storageUsed, pausedOffline)
        status.waiting > 0 -> stringResource(R.string.two_parts, storageUsed, waitingText)
        else -> storageUsed
    }
    var quality by remember { mutableIntStateOf(downloads.qualityKbps) }
    var bluetoothFirst by remember { mutableStateOf(downloads.preference == TransferPreference.BLUETOOTH_FIRST) }

    WatchList(title = stringResource(R.string.downloads)) {
        if (status.owners.isEmpty()) noticeItem(emptyText)
        if (status.unavailable > 0) noticeItem(unavailableText)
        status.owners.forEach { owner ->
            item(key = owner.uri) {
                FluidWearListRow(
                    title = owner.title.ifBlank { owner.uri },
                    subtitle = stringResource(R.string.kept_progress, owner.done, owner.tracks),
                    onClick = { onOpen(owner.uri, owner.title) },
                    leading = { Icon(PhosphorIcons.Regular.Playlist, contentDescription = null, modifier = Modifier.size(WearDimens.ListIcon)) },
                )
            }
        }
        item {
            FluidWearListRow(
                title = stringResource(R.string.storage),
                subtitle = storageText,
                leading = { Icon(PhosphorIcons.Regular.HardDrives, contentDescription = null, modifier = Modifier.size(WearDimens.ListIcon)) },
            )
        }
        item {
            FluidWearListRow(
                title = stringResource(R.string.download_quality),
                subtitle = stringResource(R.string.quality_kbps, quality),
                onClick = {
                    // Round the three steps, the phone's own choices. Only what is fetched from now on
                    // has the new quality: what is on the watch already is kept as it is, so there is
                    // nothing to confirm.
                    val steps = WatchDownloads.QUALITY_STEPS
                    quality = steps[(steps.indexOf(quality) + 1) % steps.size]
                    downloads.qualityKbps = quality
                    downloads.changed(schedule = true)
                },
                leading = { Icon(PhosphorIcons.Regular.Gauge, contentDescription = null, modifier = Modifier.size(WearDimens.ListIcon)) },
            )
        }
        item {
            SwitchButton(
                checked = bluetoothFirst,
                onCheckedChange = { on ->
                    bluetoothFirst = on
                    downloads.preference = if (on) TransferPreference.BLUETOOTH_FIRST else TransferPreference.WIFI_FIRST
                    downloads.changed(schedule = false)
                },
                label = { Text(stringResource(R.string.bluetooth_first)) },
                secondaryLabel = { Text(stringResource(R.string.bluetooth_first_summary)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
