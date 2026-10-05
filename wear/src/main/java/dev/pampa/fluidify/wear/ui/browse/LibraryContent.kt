package dev.pampa.fluidify.wear.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.Heart
import com.adamglin.phosphoricons.regular.Disc
import com.adamglin.phosphoricons.regular.MusicNote
import com.adamglin.phosphoricons.regular.Playlist
import com.adamglin.phosphoricons.regular.User
import dev.pampa.fluidify.wear.R
import dev.pampa.fluidify.wear.protocol.LibraryKind
import dev.pampa.fluidify.wear.ui.common.WatchListScope
import dev.pampa.fluidify.wear.ui.common.failedItem
import dev.pampa.fluidify.wear.ui.common.loadingItem
import dev.pampa.fluidify.wear.ui.common.staleItem

/**
 * Loads something the phone has, showing the copy kept on the watch first.
 *
 * [peek] answers from memory in the first frame (or not at all); [cached] reads the copy on the
 * watch's disk, off the main thread; [fetch] goes over Bluetooth and replaces it when it returns.
 * A failed fetch keeps whatever was shown, and says so ([PhoneData.failed] with a value): see
 * [phoneDataNotices]. Nothing is read or fetched while [enabled] is false, which is how a screen
 * composed ahead of time (the Home, one swipe away) waits until it is looked at.
 */
@Composable
fun <T> rememberPhoneData(
    key: Any,
    cached: () -> T?,
    peek: () -> T? = { null },
    enabled: Boolean = true,
    fetch: suspend () -> Result<T>,
): PhoneData<T> {
    var state by remember(key) { mutableStateOf(PhoneData(peek(), loading = true, failed = false)) }
    val attempt = remember(key) { mutableIntStateOf(0) }
    LaunchedEffect(key, enabled, attempt.intValue) {
        if (!enabled) return@LaunchedEffect
        if (state.value == null) {
            withContext(Dispatchers.IO) { cached() }?.let { kept -> if (state.value == null) state = state.copy(value = kept) }
        }
        state = state.copy(loading = true, failed = false)
        val result = fetch()
        state = state.copy(value = result.getOrNull() ?: state.value, loading = false, failed = result.isFailure)
    }
    val retry: () -> Unit = remember(key) { { attempt.intValue++ } }
    return state.copy(retry = retry)
}

/**
 * What the phone's answer is, as far as it is known: [value] is what there is to show (the fresh
 * copy, or the one kept on the watch), [loading] that a fetch is on its way, [failed] that the last
 * one did not come back, and [retry] asks again.
 */
data class PhoneData<T>(
    val value: T?,
    val loading: Boolean,
    val failed: Boolean,
    val retry: () -> Unit = {},
)

/** The words a list says about its data, read where strings can be (see [phoneDataNotices]). */
class PhoneDataTexts(val loading: String, val failed: String, val notUpdated: String, val retry: String)

@Composable
fun rememberPhoneDataTexts(): PhoneDataTexts = PhoneDataTexts(
    loading = stringResource(R.string.loading),
    failed = stringResource(R.string.couldnt_load),
    notUpdated = stringResource(R.string.not_updated),
    retry = stringResource(R.string.retry),
)

/**
 * Says what a list built from [data] has to say about it, and gives back what there is to show:
 *
 *  - nothing yet and still asking: a spinner;
 *  - nothing and the ask failed: why, and a Retry;
 *  - the copy kept on the watch, but the ask failed: a small "not updated" row over it, with Retry.
 *
 * Null when there is nothing to show, and the list has said why.
 */
fun <T : Any> WatchListScope.phoneDataNotices(data: PhoneData<T>, texts: PhoneDataTexts): T? {
    val value = data.value
    when {
        value == null && data.failed -> failedItem(texts.failed, texts.retry, data.retry)
        value == null -> loadingItem(texts.loading)
        data.failed -> staleItem(texts.notUpdated, texts.retry, data.retry)
    }
    return value
}

/** The tile icon for a row whose cover has not arrived. */
fun kindIcon(kind: LibraryKind): ImageVector = when (kind) {
    LibraryKind.ALBUM -> PhosphorIcons.Regular.Disc
    LibraryKind.ARTIST -> PhosphorIcons.Regular.User
    LibraryKind.TRACK -> PhosphorIcons.Regular.MusicNote
    LibraryKind.LIKED -> PhosphorIcons.Fill.Heart
    else -> PhosphorIcons.Regular.Playlist
}
