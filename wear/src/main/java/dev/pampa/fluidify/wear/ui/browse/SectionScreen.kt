package dev.pampa.fluidify.wear.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
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
    val data = rememberPhoneData(section, { app.library.cachedSection(section) }, { app.library.peekSection(section) }) { app.library.section(section) }
    val texts = rememberPhoneDataTexts()
    val nothingHere = stringResource(R.string.nothing_here)
    WatchList(title = title) {
        val page = phoneDataNotices(data, texts)
        when {
            page == null -> Unit
            page.unavailableReason != null -> noticeItem(page.unavailableReason!!)
            page.shelves.all { it.items.isEmpty() } -> noticeItem(nothingHere)
            // All the shelves in one list: the same uri can be on two of them.
            else -> page.shelves.flatMap { it.items }.forEachIndexed { index, entry ->
                item(key = "$index:${entry.uri}") {
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
