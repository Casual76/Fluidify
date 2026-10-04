package dev.pampa.fluidify.wear.ui.browse

import android.app.Activity
import android.app.RemoteInput
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.Text
import androidx.wear.input.RemoteInputIntentHelper
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.MagnifyingGlass
import dev.antigravity.fluidengine.wear.components.FluidWearListRow
import dev.pampa.fluidify.wear.R
import dev.pampa.fluidify.wear.WearApp
import dev.pampa.fluidify.wear.protocol.LibraryKind
import dev.pampa.fluidify.wear.protocol.LibraryPage
import dev.pampa.fluidify.wear.ui.common.Thumb
import dev.pampa.fluidify.wear.ui.common.WatchList
import dev.pampa.fluidify.wear.ui.common.noticeItem
import kotlinx.coroutines.launch

/**
 * Search, by voice or keyboard, through Wear's own input screen (which offers
 * both). The phone runs the search; the watch only types.
 */
@Composable
fun SearchScreen(
    app: WearApp,
    onOpen: (uri: String, title: String) -> Unit,
    onPlaying: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<LibraryPage?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val label = stringResource(R.string.search)

    fun run(text: String) {
        query = text
        busy = true
        failed = false
        scope.launch {
            app.library.search(text).fold(
                onSuccess = { results = it },
                onFailure = { failed = true },
            )
            busy = false
        }
    }

    val input = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val text = result.data?.let { RemoteInput.getResultsFromIntent(it) }?.getCharSequence(KEY)?.toString()?.trim()
        if (!text.isNullOrEmpty()) run(text)
    }
    val ask: () -> Unit = {
        val intent: Intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(RemoteInput.Builder(KEY).setLabel(label).build()))
        input.launch(intent)
    }
    // Straight to the keyboard/voice screen the first time, like Spotify.
    LaunchedEffect(Unit) { if (query.isEmpty()) ask() else if (results == null && !busy) run(query) }

    WatchList(title = null) {
        item {
            FluidWearListRow(
                title = query.ifEmpty { label },
                onClick = ask,
                leading = { Icon(PhosphorIcons.Regular.MagnifyingGlass, contentDescription = null, modifier = Modifier.size(22.dp)) },
            )
        }
        val page = results
        when {
            busy -> noticeItem(app.getString(R.string.loading))
            failed -> noticeItem(app.getString(R.string.couldnt_load))
            page != null && page.shelves.all { it.items.isEmpty() } -> noticeItem(app.getString(R.string.no_results))
            page != null -> page.shelves.forEachIndexed { shelfIndex, shelf ->
                if (shelf.title.isNotEmpty()) item { ListHeader { Text(shelf.title) } }
                shelf.items.forEachIndexed { index, entry ->
                    item(key = "$shelfIndex/$index:${entry.uri}") {
                        FluidWearListRow(
                            title = entry.title,
                            subtitle = entry.subtitle.ifEmpty { null },
                            onClick = {
                                if (entry.kind == LibraryKind.TRACK) {
                                    app.controls.playContext(entry.uri, label = query)
                                    onPlaying()
                                } else {
                                    onOpen(entry.uri, entry.title)
                                }
                            },
                            leading = { Thumb(entry.artKey, entry.artUrl, app.art, kindIcon(entry.kind)) },
                        )
                    }
                }
            }
        }
    }
}

private const val KEY = "query"
