package dev.pampa.fluidify.wear.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.regular.MusicNote
import com.adamglin.phosphoricons.regular.Playlist
import com.adamglin.phosphoricons.regular.Shuffle
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.wear.components.FluidWearListRow
import dev.antigravity.fluidengine.wear.components.FluidWearPill
import dev.pampa.fluidify.wear.R
import dev.pampa.fluidify.wear.WearApp
import dev.pampa.fluidify.wear.ui.common.Thumb
import dev.pampa.fluidify.wear.ui.common.WatchList
import dev.pampa.fluidify.wear.ui.common.noticeItem

/** A playlist, album, artist or Liked Songs: the cover, Play and Shuffle, then the tracks. */
@Composable
fun ContextScreen(
    app: WearApp,
    uri: String,
    title: String,
    onPlaying: () -> Unit,
) {
    val data = rememberPhoneData(uri, { app.library.cachedContext(uri) }) { app.library.context(uri) }
    val page = data.value
    WatchList(title = page?.title?.ifEmpty { null } ?: title) {
        item {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Thumb(
                    artKey = page?.artKey,
                    artUrl = page?.artUrl,
                    art = app.art,
                    fallback = PhosphorIcons.Regular.Playlist,
                    size = 72.dp,
                    large = true,
                    modifier = Modifier.clip(ContinuousCornerShape(FluidRadius.Card)),
                )
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FluidWearPill(
                    onClick = {
                        app.controls.playContext(uri, label = page?.title ?: title)
                        onPlaying()
                    },
                    modifier = Modifier.weight(1f),
                ) { Icon(PhosphorIcons.Fill.Play, contentDescription = stringResource(R.string.play), modifier = Modifier.size(20.dp)) }
                FluidWearPill(
                    onClick = {
                        app.controls.playContext(uri, shuffle = true, label = page?.title ?: title)
                        onPlaying()
                    },
                    modifier = Modifier.weight(1f),
                ) { Icon(PhosphorIcons.Regular.Shuffle, contentDescription = stringResource(R.string.shuffle), modifier = Modifier.size(20.dp)) }
            }
        }
        when {
            page == null && data.failed -> noticeItem(app.getString(R.string.couldnt_load))
            page == null -> noticeItem(app.getString(R.string.loading))
            page.tracks.isEmpty() -> noticeItem(app.getString(R.string.nothing_here))
            else -> page.tracks.forEach { track ->
                item(key = track.uri) {
                    FluidWearListRow(
                        title = track.title,
                        subtitle = track.subtitle.ifEmpty { null },
                        onClick = {
                            app.controls.playContext(uri, startTrackUri = track.uri, label = page.title)
                            onPlaying()
                        },
                        leading = { Thumb(track.artKey, track.artUrl, app.art, PhosphorIcons.Regular.MusicNote) },
                    )
                }
            }
        }
    }
}
