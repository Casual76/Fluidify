package dev.pampa.fluidify.wear.ui.browse

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ClockCounterClockwise
import com.adamglin.phosphoricons.regular.Disc
import com.adamglin.phosphoricons.regular.DeviceMobile
import com.adamglin.phosphoricons.regular.Download
import com.adamglin.phosphoricons.regular.Playlist
import com.adamglin.phosphoricons.regular.User
import dev.antigravity.fluidengine.wear.components.FluidWearListRow
import dev.pampa.fluidify.wear.R
import dev.pampa.fluidify.wear.protocol.LibrarySection
import dev.pampa.fluidify.wear.ui.common.WatchList

/** "La tua libreria": the sections, one row each, like Spotify's. */
@Composable
fun LibraryScreen(onSection: (LibrarySection, String) -> Unit, onWatchDownloads: (() -> Unit)? = null) {
    WatchList(title = stringResource(R.string.your_library)) {
        // What the watch keeps itself first: it plays with no phone at all.
        if (onWatchDownloads != null) {
            item(key = "watch-downloads") {
                FluidWearListRow(
                    title = stringResource(R.string.downloads),
                    subtitle = stringResource(R.string.on_this_watch),
                    onClick = onWatchDownloads,
                    leading = { Icon(PhosphorIcons.Regular.Download, contentDescription = null, modifier = Modifier.size(24.dp)) },
                )
            }
        }
        val rows = listOf(
            Triple(LibrarySection.DOWNLOADS, R.string.on_the_phone, PhosphorIcons.Regular.DeviceMobile),
            Triple(LibrarySection.PLAYLISTS, R.string.playlists, PhosphorIcons.Regular.Playlist),
            Triple(LibrarySection.ALBUMS, R.string.albums, PhosphorIcons.Regular.Disc),
            Triple(LibrarySection.ARTISTS, R.string.artists, PhosphorIcons.Regular.User),
            Triple(LibrarySection.RECENT, R.string.recent, PhosphorIcons.Regular.ClockCounterClockwise),
        )
        rows.forEach { (section, label, icon) ->
            item(key = section.name) {
                val title = stringResource(label)
                FluidWearListRow(
                    title = title,
                    onClick = { onSection(section, title) },
                    leading = { Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp)) },
                )
            }
        }
    }
}
