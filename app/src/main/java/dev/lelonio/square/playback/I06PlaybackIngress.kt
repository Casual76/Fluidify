@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.lelonio.square.playback

import androidx.media3.common.MediaItem
import dev.lelonio.square.data.LocalLibrary
import dev.lelonio.square.ui.EXTRA_QUEUE_CYCLE_ANCHOR

/** Correlation only: service admission must still check its current account authority. */
internal const val I06_REQUEST_SESSION = "wf_i06_request_session"
internal const val I06_WIDGET_TRANSPORT = "dev.lelonio.square.WIDGET_TRANSPORT"
internal const val I06_WIDGET_ACTION = "action"
internal const val I06_WIDGET_ACCOUNT_EPOCH = "accountEpoch"

internal fun MediaItem.i06ForRequest(session: String): MediaItem {
    val extras = android.os.Bundle(mediaMetadata.extras ?: android.os.Bundle.EMPTY)
    extras.putString(I06_REQUEST_SESSION, session)
    return buildUpon().setMediaMetadata(mediaMetadata.buildUpon().setExtras(extras).build()).build()
}

internal enum class I06PlaybackSource { Spotify, Local }

internal fun i06Source(uri: String): I06PlaybackSource = when {
    LocalLibrary.isLocal(uri) -> I06PlaybackSource.Local
    uri.startsWith("spotify:") -> I06PlaybackSource.Spotify
    else -> throw IllegalArgumentException("Unresolved or unsupported playback identity")
}

internal class I06QueueSourceMismatch : IllegalArgumentException("Queue source does not accept these tracks")

/** Enqueue never changes source or drops a subset of a block. */
internal fun i06RequireEnqueueSource(currentUri: String?, items: List<MediaItem>) {
    if (items.isEmpty()) return
    val wanted = i06Source(items.first().mediaId)
    if (items.any { i06Source(it.mediaId) != wanted } ||
        (currentUri != null && i06Source(currentUri) != wanted)) throw I06QueueSourceMismatch()
}

internal data class I06PlaybackSelection(val items: List<MediaItem>, val index: Int) {
    val uri: String get() = items[index].mediaId
}

/**
 * A backend can play only its own source. Project the list around the selected occurrence,
 * remapping its index rather than letting a backend filter the list behind that index.
 */
internal fun i06PlaybackSelection(items: List<MediaItem>, requestedIndex: Int,
    markCycleAnchor: Boolean = true): I06PlaybackSelection {
    require(items.isNotEmpty()) { "No tracks selected" }
    val selected = if (requestedIndex == androidx.media3.common.C.INDEX_UNSET) 0 else requestedIndex
    require(selected in items.indices) { "Selected occurrence no longer exists" }
    val source = i06Source(items[selected].mediaId)
    val indices = items.indices.filter { i06Source(items[it].mediaId) == source }
    val index = indices.indexOf(selected)
    val projected = indices.mapIndexed { target, original ->
        val item = items[original]
        if (!markCycleAnchor) item else {
            val extras = android.os.Bundle(item.mediaMetadata.extras ?: android.os.Bundle.EMPTY)
            extras.putBoolean(EXTRA_QUEUE_CYCLE_ANCHOR, target == index)
            item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build()).build()
        }
    }
    return I06PlaybackSelection(projected, index)
}

/** Preserve an intent across local/online promotion, but never across revoked credential lineage. */
internal fun i06CurrentPlaybackAccess(
    accounts: dev.lelonio.square.data.AccountLocalDataCoordinator,
    original: dev.lelonio.square.data.AccountLocalDataCoordinator.Access,
    capturedEpoch: Long,
    currentEpoch: Long,
): dev.lelonio.square.data.AccountLocalDataCoordinator.Access? =
    accounts.capture()?.takeIf { capturedEpoch == currentEpoch && it.binding == original.binding }
