package dev.pampa.fluidify.wear.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import coil.compose.AsyncImage
import dev.pampa.fluidify.wear.library.LocalThumbnails
import dev.pampa.fluidify.wear.link.ArtStore
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A small cover for a list row.
 *
 * The full cover if the watch has it (the art store: what is or was playing),
 * then the thumbnail the phone sends for lists ([Thumbnails], asked for here
 * and kept on the watch), and only when the phone could not give one, the
 * Spotify CDN at the smallest size it serves. Until then the row keeps a quiet
 * tile with [fallback] on it, so a list never jumps when covers arrive.
 */
@Composable
fun Thumb(
    artKey: String?,
    artUrl: String?,
    art: ArtStore,
    fallback: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    large: Boolean = false,
) {
    val revision by art.revision.collectAsState()
    val thumbnails = LocalThumbnails.current
    val thumbRevision by (thumbnails?.revision ?: remember { MutableStateFlow(0L) }).collectAsState()
    val model: Any? = remember(artKey, artUrl, revision, thumbRevision) {
        art.fileFor(artKey)
            ?: thumbnails?.fileFor(artKey)
            ?: smallVariant(artUrl, large).takeIf { thumbnails == null || thumbnails.unavailable(artKey) }
    }
    if (model == null && artKey != null && thumbnails != null) {
        LaunchedEffect(artKey) { thumbnails.want(artKey, artUrl) }
    }
    Box(
        modifier = modifier
            .size(size)
            .background(MaterialTheme.colorScheme.surfaceContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(fallback, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(size * 0.45f))
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * The same Spotify cover at a size a watch row needs.
 *
 * Album covers are served under an id whose first sixteen characters name the
 * size: 640 px, 300 px and 64 px share the rest. Anything else is left as it is.
 */
fun smallVariant(url: String?, large: Boolean): String? {
    if (url.isNullOrBlank()) return null
    val target = if (large) ALBUM_300 else ALBUM_64
    val other = if (large) ALBUM_64 else ALBUM_300
    return url.replace(ALBUM_640, target).replace(other, target)
}

private const val ALBUM_640 = "ab67616d0000b273"
private const val ALBUM_300 = "ab67616d00001e02"
private const val ALBUM_64 = "ab67616d00004851"
