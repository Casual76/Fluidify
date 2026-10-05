package dev.pampa.fluidify.wear.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.ConfirmationDialogDefaults
import androidx.wear.compose.material3.SuccessConfirmationDialog
import androidx.wear.compose.material3.confirmationDialogCurvedText
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Playlist
import dev.antigravity.fluidengine.ui.haptics.FluidHapticEvent
import dev.antigravity.fluidengine.ui.haptics.LocalFluidHaptics
import dev.antigravity.fluidengine.wear.components.FluidWearListRow
import dev.pampa.fluidify.wear.R
import dev.pampa.fluidify.wear.WearApp
import dev.pampa.fluidify.wear.protocol.LibraryItem
import dev.pampa.fluidify.wear.protocol.LibraryKind
import dev.pampa.fluidify.wear.protocol.LibrarySection
import dev.pampa.fluidify.wear.ui.common.Thumb
import dev.pampa.fluidify.wear.ui.common.WatchList
import dev.pampa.fluidify.wear.ui.common.noticeItem
import dev.pampa.fluidify.wear.ui.theme.WearDimens
import kotlinx.coroutines.launch

/**
 * The song on screen into one of the listener's playlists: the long press on the heart.
 *
 * Only the playlists the account may write to — the phone knows which ([LibraryItem.editable]);
 * one it only follows would refuse the song after the tap. The list is the phone's library section,
 * kept copy first. A tap waits for the write to land before saying so, and goes back to the player.
 */
@Composable
fun AddToPlaylistScreen(
    app: WearApp,
    trackUri: String,
    trackTitle: String,
    onDone: () -> Unit,
) {
    val section = LibrarySection.PLAYLISTS
    val data = rememberPhoneData(section, { app.library.cachedSection(section) }, { app.library.peekSection(section) }) { app.library.section(section) }
    val haptics = LocalFluidHaptics.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var added by remember { mutableStateOf<String?>(null) }
    val texts = rememberPhoneDataTexts()
    val noEditable = stringResource(R.string.no_editable_playlists)
    val playlists = data.value?.shelves.orEmpty().flatMap { it.items }.filter(::canTake)

    WatchList(title = stringResource(R.string.add_to_playlist)) {
        if (trackTitle.isNotEmpty()) noticeItem(trackTitle)
        val page = phoneDataNotices(data, texts)
        when {
            page == null -> Unit
            page.unavailableReason != null -> noticeItem(page.unavailableReason!!)
            playlists.isEmpty() -> noticeItem(noEditable)
            else -> playlists.forEachIndexed { index, playlist ->
                item(key = "$index:${playlist.uri}") {
                    FluidWearListRow(
                        title = playlist.title,
                        onClick = {
                            if (busy != null) return@FluidWearListRow
                            busy = playlist.uri
                            scope.launch {
                                val ok = app.controls.addToPlaylist(playlist.uri, trackUri)
                                busy = null
                                if (ok) {
                                    haptics.play(FluidHapticEvent.Confirm)
                                    added = playlist.title
                                }
                            }
                        },
                        leading = { Thumb(playlist.artKey, playlist.artUrl, app.art, PhosphorIcons.Regular.Playlist) },
                        trailing = if (busy == playlist.uri) {
                            { CircularProgressIndicator(modifier = Modifier.size(WearDimens.PillIcon)) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }

    val name = added
    val text = if (name != null) stringResource(R.string.added_to, name) else ""
    val style = ConfirmationDialogDefaults.curvedTextStyle
    SuccessConfirmationDialog(
        visible = name != null,
        onDismissRequest = {
            added = null
            onDone()
        },
        curvedText = { confirmationDialogCurvedText(text, style) },
    )
}

/** Offer only playlists whose ownership or capabilities confirm that this account can write. */
internal fun canTake(item: LibraryItem): Boolean =
    item.kind == LibraryKind.PLAYLIST && item.uri.startsWith("spotify:playlist:") && item.editable == true
