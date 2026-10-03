package dev.pampa.fluidify.wear.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.Text
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Books
import com.adamglin.phosphoricons.regular.House
import com.adamglin.phosphoricons.regular.MagnifyingGlass
import dev.antigravity.fluidengine.wear.components.FluidWearListRow
import dev.antigravity.fluidengine.wear.components.FluidWearPill
import dev.pampa.fluidify.wear.R
import dev.pampa.fluidify.wear.WearApp
import dev.pampa.fluidify.wear.ui.common.Thumb
import dev.pampa.fluidify.wear.ui.common.WatchList
import dev.pampa.fluidify.wear.ui.common.noticeItem

/**
 * Home, as Spotify's watch app lays it out: the house on top, two pills (Search,
 * Library), then the listener's own lists and Spotify's shelves, each row a
 * cover and a name.
 */
@Composable
fun HomeScreen(
    app: WearApp,
    onSearch: () -> Unit,
    onLibrary: () -> Unit,
    onOpen: (uri: String, title: String) -> Unit,
) {
    val home = rememberPhoneData("home", app.library::cachedHome) { app.library.home() }
    WatchList(title = null) {
        item {
            ListHeader { Icon(PhosphorIcons.Regular.House, contentDescription = stringResource(R.string.home), modifier = Modifier.size(22.dp)) }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FluidWearPill(onClick = onSearch, modifier = Modifier.weight(1f)) {
                    Icon(PhosphorIcons.Regular.MagnifyingGlass, contentDescription = stringResource(R.string.search), modifier = Modifier.size(20.dp))
                }
                FluidWearPill(onClick = onLibrary, modifier = Modifier.weight(1f)) {
                    Icon(PhosphorIcons.Regular.Books, contentDescription = stringResource(R.string.library), modifier = Modifier.size(20.dp))
                }
            }
        }
        val page = home.value
        when {
            page == null && home.failed -> noticeItem(app.getString(R.string.couldnt_load))
            page == null -> noticeItem(app.getString(R.string.loading))
            else -> page.shelves.forEach { shelf ->
                if (shelf.title.isNotEmpty()) item { ListHeader { Text(shelf.title, maxLines = 2) } }
                shelf.items.forEach { entry ->
                    item(key = "${shelf.title}/${entry.uri}") {
                        FluidWearListRow(
                            title = entry.title,
                            onClick = { onOpen(entry.uri, entry.title) },
                            leading = { Thumb(entry.artKey, entry.artUrl, app.art, kindIcon(entry.kind)) },
                        )
                    }
                }
            }
        }
    }
}
