package dev.pampa.fluidify.wear.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.Heart
import com.adamglin.phosphoricons.regular.Disc
import com.adamglin.phosphoricons.regular.MusicNote
import com.adamglin.phosphoricons.regular.Playlist
import com.adamglin.phosphoricons.regular.User
import dev.pampa.fluidify.wear.protocol.LibraryKind

/**
 * Loads something the phone has, showing the copy kept on the watch first.
 *
 * [cached] answers at once (or not at all); [fetch] goes over Bluetooth and
 * replaces it when it returns. A failed fetch keeps whatever was shown.
 */
@Composable
fun <T> rememberPhoneData(key: Any, cached: () -> T?, fetch: suspend () -> Result<T>): PhoneData<T> {
    var state by remember(key) { mutableStateOf(PhoneData(cached(), loading = true, failed = false)) }
    LaunchedEffect(key) {
        val result = fetch()
        state = PhoneData(result.getOrNull() ?: state.value, loading = false, failed = result.isFailure)
    }
    return state
}

data class PhoneData<T>(val value: T?, val loading: Boolean, val failed: Boolean)

/** The tile icon for a row whose cover has not arrived. */
fun kindIcon(kind: LibraryKind): ImageVector = when (kind) {
    LibraryKind.ALBUM -> PhosphorIcons.Regular.Disc
    LibraryKind.ARTIST -> PhosphorIcons.Regular.User
    LibraryKind.TRACK -> PhosphorIcons.Regular.MusicNote
    LibraryKind.LIKED -> PhosphorIcons.Fill.Heart
    else -> PhosphorIcons.Regular.Playlist
}
