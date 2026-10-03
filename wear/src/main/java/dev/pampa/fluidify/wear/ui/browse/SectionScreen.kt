package dev.pampa.fluidify.wear.ui.browse

import androidx.compose.runtime.Composable
import dev.antigravity.fluidengine.wear.components.FluidWearListRow
import dev.pampa.fluidify.wear.R
import dev.pampa.fluidify.wear.WearApp
import dev.pampa.fluidify.wear.protocol.LibraryKind
import dev.pampa.fluidify.wear.protocol.LibrarySection
import dev.pampa.fluidify.wear.ui.common.Thumb
import dev.pampa.fluidify.wear.ui.common.WatchList
import dev.pampa.fluidify.wear.ui.common.noticeItem

/** One library section: playlists, albums, artists, downloads or recent tracks. */
@Composable
fun SectionScreen(
    app: WearApp,
    section: LibrarySection,
    title: String,
    onOpen: (uri: String, title: String) -> Unit,
    onPlayTrack: (uri: String) -> Unit,
) {
    val data = rememberPhoneData(section, { app.library.cachedSection(section) }) { app.library.section(section) }
    WatchList(title = title) {
        val page = data.value
        when {
            page == null && data.failed -> noticeItem(app.getString(R.string.couldnt_load))
            page == null -> noticeItem(app.getString(R.string.loading))
            page.unavailableReason != null -> noticeItem(page.unavailableReason!!)
            page.shelves.all { it.items.isEmpty() } -> noticeItem(app.getString(R.string.nothing_here))
            else -> page.shelves.flatMap { it.items }.forEach { entry ->
                item(key = entry.uri) {
                    FluidWearListRow(
                        title = entry.title,
                        subtitle = entry.subtitle.ifEmpty { null },
                        onClick = {
                            if (entry.kind == LibraryKind.TRACK) onPlayTrack(entry.uri) else onOpen(entry.uri, entry.title)
                        },
                        leading = { Thumb(entry.artKey, entry.artUrl, app.art, kindIcon(entry.kind)) },
                    )
                }
            }
        }
    }
}
