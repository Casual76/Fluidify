package dev.pampa.fluidify.wear.system

import android.content.Context
import dev.pampa.fluidify.wear.link.ReceivedSnapshot
import dev.pampa.fluidify.wear.protocol.PlaybackSnapshot

/**
 * Keeps the system's surfaces in step with the phone: the icon on the watch
 * face, the tile and the complication.
 *
 * Called for every snapshot the watch accepts, with the app open or not, and
 * for every cover that lands. Each surface is touched only when something it
 * shows changed: the phone sends a snapshot for a seek or a volume step too,
 * and none of those should wake the tile renderer or the watch face.
 */
class SystemSurfaces(
    private val context: Context,
    private val prefs: SurfacePrefs = SurfacePrefs(context),
    private val ongoing: OngoingPlayback = OngoingPlayback(context),
) {

    @Volatile
    private var current: PlaybackSnapshot? = null

    fun onState(received: ReceivedSnapshot?) {
        val snapshot = received?.snapshot
        current = snapshot
        ongoing.update(snapshot, prefs.ongoingIcon)
        val signature = signatureOf(snapshot)
        if (signature != prefs.lastSignature) {
            prefs.lastSignature = signature
            refreshTileAndComplication()
        }
    }

    /** A cover arrived; it matters if it is the one on show. */
    fun onCoverStored(key: String) {
        if (current?.track?.artKey == key) refreshTileAndComplication()
    }

    /** The icon switch moved in Altro. */
    fun onPrefsChanged() {
        ongoing.update(current, prefs.ongoingIcon)
    }

    private fun refreshTileAndComplication() {
        PlayerTileService.requestUpdate(context)
        NowPlayingComplicationService.requestUpdate(context)
    }

    companion object {
        /** What the tile and the complication draw; a change in anything else redraws nothing. */
        fun signatureOf(snapshot: PlaybackSnapshot?): String {
            val track = snapshot?.track ?: return "-"
            val playing = snapshot.isPlaying || snapshot.playWhenReady
            return listOf(track.uri, track.title, track.artist, track.artKey, playing, snapshot.liked).joinToString("|")
        }
    }
}
