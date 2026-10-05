package dev.pampa.fluidify.wear.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.text.style.TextOverflow
import dev.pampa.fluidify.wear.protocol.LibraryItem
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.PushPin
import com.adamglin.phosphoricons.regular.Books
import com.adamglin.phosphoricons.regular.House
import com.adamglin.phosphoricons.regular.MagnifyingGlass
import dev.antigravity.fluidengine.ui.haptics.FluidHapticEvent
import dev.antigravity.fluidengine.ui.haptics.LocalFluidHaptics
import dev.antigravity.fluidengine.wear.components.FluidWearListRow
import dev.antigravity.fluidengine.wear.theme.FluidWearDimens
import dev.pampa.fluidify.wear.protocol.LibraryKind
import kotlinx.coroutines.launch
import dev.antigravity.fluidengine.wear.components.FluidWearPill
import dev.pampa.fluidify.wear.R
import dev.pampa.fluidify.wear.WearApp
import dev.pampa.fluidify.wear.ui.common.Thumb
import dev.pampa.fluidify.wear.ui.common.WatchList
import dev.pampa.fluidify.wear.ui.common.LocalNotice
import dev.pampa.fluidify.wear.ui.theme.WearDimens

/**
 * Home, as Spotify's watch app lays it out: the house on top, two pills (Search,
 * Library), then the listener's own lists — pinned first, then what they played
 * lately — the playlists Spotify made for them, and Spotify's shelves, each row a
 * cover and a name. A long press pins or unpins a row, on the phone too.
 */
@Composable
fun HomeScreen(
    app: WearApp,
    /** Whether to ask the phone for the lists: false until the page has been swiped towards once. */
    load: Boolean,
    onSearch: () -> Unit,
    onLibrary: () -> Unit,
    onOpen: (uri: String, title: String) -> Unit,
) {
    var refresh by remember { mutableIntStateOf(0) }
    val home = rememberPhoneData("home#$refresh", app.library::cachedHome, app.library::peekHome, enabled = load) { app.library.home() }
    val texts = rememberPhoneDataTexts()
    val scope = rememberCoroutineScope()
    val haptics = LocalFluidHaptics.current
    val notice = LocalNotice.current
    var pendingPins by remember { mutableStateOf(emptySet<String>()) }
    val pinFailed = stringResource(R.string.pin_failed)
    val pinLabel = stringResource(R.string.pin)
    val unpinLabel = stringResource(R.string.unpin)
    fun pin(entry: LibraryItem) {
        if (entry.uri in pendingPins) return
        pendingPins = pendingPins + entry.uri
        scope.launch {
            try {
                val pinned = app.library.peekHome()?.shelves?.flatMap { it.items }?.firstOrNull { it.uri == entry.uri }?.pinned ?: entry.pinned
                if (app.library.setPinned(entry.uri, !pinned)) {
                    haptics.play(FluidHapticEvent.Threshold)
                    refresh++
                } else {
                    notice.show(pinFailed, failure = true)
                }
            } finally { pendingPins = pendingPins - entry.uri }
        }
    }
    WatchList(title = null) {
        item {
            ListHeader { Icon(PhosphorIcons.Regular.House, contentDescription = stringResource(R.string.home), modifier = Modifier.size(WearDimens.ListIcon)) }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FluidWearPill(onClick = onSearch, modifier = Modifier.weight(1f)) {
                    Icon(PhosphorIcons.Regular.MagnifyingGlass, contentDescription = stringResource(R.string.search), modifier = Modifier.size(WearDimens.PillIcon))
                }
                FluidWearPill(onClick = onLibrary, modifier = Modifier.weight(1f)) {
                    Icon(PhosphorIcons.Regular.Books, contentDescription = stringResource(R.string.library), modifier = Modifier.size(WearDimens.PillIcon))
                }
            }
        }
        val page = phoneDataNotices(home, texts)
        when {
            page == null -> Unit
            else -> page.shelves.forEachIndexed { shelfIndex, shelf ->
                if (shelf.title.isNotEmpty()) item { ListSubHeader { Text(shelf.title, maxLines = 2, overflow = TextOverflow.Ellipsis) } }
                shelf.items.forEachIndexed { index, entry ->
                    item(key = "$shelfIndex/$index:${entry.uri}") {
                        val pinnable = entry.kind == LibraryKind.PLAYLIST || entry.kind == LibraryKind.ALBUM || entry.kind == LibraryKind.LIKED
                        FluidWearListRow(
                            title = entry.title,
                            modifier = if (pinnable) Modifier.semantics {
                                customActions = listOf(CustomAccessibilityAction(if (entry.pinned) unpinLabel else pinLabel) { pin(entry); true })
                            } else Modifier,
                            onClick = { onOpen(entry.uri, entry.title) },
                            // A long press pins or unpins, as on the phone's library.
                            onLongClick = if (pinnable) {
                                {
                                    pin(entry)
                                }
                            } else {
                                null
                            },
                            leading = { Thumb(entry.artKey, entry.artUrl, app.art, kindIcon(entry.kind)) },
                            trailing = if (entry.pinned) {
                                {
                                    Icon(
                                        PhosphorIcons.Fill.PushPin,
                                        contentDescription = stringResource(R.string.pinned),
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(FluidWearDimens.IconSmall),
                                    )
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
    }
}
