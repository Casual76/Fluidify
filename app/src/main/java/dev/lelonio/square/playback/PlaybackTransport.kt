package dev.lelonio.square.playback

import androidx.media3.common.Player
import dev.lelonio.square.backend.spotify.SpotifyVideoMode
import dev.lelonio.square.data.RemoteConnect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Skipping the way the phone's own buttons skip, for callers that are not the phone's screens
 * (the watch).
 *
 * The player's generic `seekToNext()`/`seekToPrevious()` are not that: "previous" past three
 * seconds restarts the song, and a session mirroring another Connect device with a one-item window
 * ignores both. The phone's buttons go to the next or previous *item*, tell the other device
 * directly when the music is playing there, and step through the video's own list in video mode
 * (SquareApp's player callbacks). This is the same three roads.
 */
object PlaybackTransport {

    /** Skips forward or back. False when there is nothing loaded to skip in. */
    suspend fun skip(player: Player, forward: Boolean): Boolean {
        if (SpotifyVideoMode.enabled.value) {
            SpotifyVideoMode.skip(forward)
            return true
        }
        val remote = RemoteConnect.playback.value
        if (RemoteConnect.elsewhereActive.value && remote != null) {
            withContext(Dispatchers.IO) {
                if (forward) RemoteConnect.next(remote.deviceId) else RemoteConnect.previous(remote.deviceId)
            }
            return true
        }
        if (player.mediaItemCount == 0) return false
        if (forward) {
            player.seekToNextMediaItem()
        } else if (player.hasPreviousMediaItem()) {
            player.seekToPreviousMediaItem()
        } else {
            // The first song of the list: back to its start, which is what the button does too.
            player.seekTo(0)
        }
        return true
    }
}
