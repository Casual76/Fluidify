package dev.lelonio.square.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.ui.EXTRA_ARTIST_NAMES
import dev.lelonio.square.ui.EXTRA_ARTIST_URI
import dev.lelonio.square.ui.EXTRA_ARTIST_URIS
import dev.lelonio.square.ui.EXTRA_CONTEXT_LABEL
import dev.lelonio.square.ui.EXTRA_CONTEXT_ORDERED
import dev.lelonio.square.ui.EXTRA_CONTEXT_URI
import dev.lelonio.square.ui.EXTRA_PLAY_NEXT

/**
 * A track as an item of the play queue, with everything the service needs to
 * know about where it came from.
 *
 * Moved out of MainActivity unchanged, so the watch remote can start a playlist
 * on the phone exactly the way the phone's own screens do.
 */
fun CatalogTrack.toQueueItem(
    contextUri: String? = null,
    asContext: Boolean = false,
    contextLabel: String = "",
    playNext: Boolean = false,
): MediaItem =
    MediaItem.Builder()
        // The media id carries the track's URI; PlayQueue refuses anything else.
        .setMediaId(this.uri)
        // The same URI again, as the thing to play. The librespot player
        // ignores it and works off the media id, but ExoPlayer — which is
        // what the local-files player uses — plays the URI and nothing
        // else; its resolver turns a `local:` one into a content URI at load.
        .setUri(this.uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(this.name)
                .setArtist(this.artist)
                .setAlbumTitle(this.album)
                // Left unset when unknown rather than sent as zero: some of
                // YouTube's shelves carry no length, and a zero here would
                // win over the one the player works out from the stream.
                .setDurationMs(this.durationMs.takeIf { it > 0 })
                // The copy on the phone first. This is the picture the
                // notification and the quick settings panel draw, and they
                // fetch it themselves over the network: offline an https
                // URL is a blank tile next to music that is playing fine.
                .setArtworkUri(
                    dev.lelonio.square.download.DownloadExtras.artworkUri(this.artworkUrl)
                        ?: this.artworkUrl?.let(android.net.Uri::parse),
                )
                // Where the queue came from, carried with the item because
                // the engine lives in the service and this is the only
                // channel between them that survives the session boundary.
                .setExtras(
                    android.os.Bundle().apply {
                        contextUri?.let { putString(EXTRA_CONTEXT_URI, it) }
                        putBoolean(EXTRA_CONTEXT_ORDERED, asContext)
                        if (contextLabel.isNotEmpty()) {
                            putString(EXTRA_CONTEXT_LABEL, contextLabel)
                        }
                        // So the artist's name in the player is a way to
                        // reach them, rather than a caption.
                        this@toQueueItem.artistUri?.let { putString(EXTRA_ARTIST_URI, it) }
                        // And each credited artist separately, so a track
                        // by two people opens the one that was pressed.
                        val credited = this@toQueueItem.artists.filter { it.uri != null }
                        if (credited.isNotEmpty()) {
                            putStringArrayList(
                                EXTRA_ARTIST_NAMES,
                                ArrayList(credited.map { it.name }),
                            )
                            putStringArrayList(
                                EXTRA_ARTIST_URIS,
                                ArrayList(credited.map { it.uri!! }),
                            )
                        }
                        if (playNext) putBoolean(EXTRA_PLAY_NEXT, true)
                    },
                )
                .build(),
        )
        .build()
