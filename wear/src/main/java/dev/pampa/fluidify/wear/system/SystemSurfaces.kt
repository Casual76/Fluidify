package dev.pampa.fluidify.wear.system

import dev.pampa.fluidify.wear.protocol.logic.NowBarEntry
import dev.pampa.fluidify.wear.protocol.logic.NowBarPolicy

import android.content.Context
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import dev.pampa.fluidify.wear.protocol.WatchSurfaces
import dev.pampa.fluidify.wear.protocol.WearCodec
import dev.pampa.fluidify.wear.protocol.WearPaths
import kotlinx.coroutines.tasks.await
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

    /** Whether the watch's own player is the one in front; its media session then has the entry. */
    @Volatile
    private var watchPlaying = false

    /** The experiment's media notification; built only if the experiment is ever on. */
    var mirror: MirrorNowPlaying? = null

    fun onState(received: ReceivedSnapshot?) {
        val snapshot = received?.snapshot
        current = snapshot
        updateEntry()
        val signature = signatureOf(snapshot)
        if (signature != prefs.lastSignature) {
            prefs.lastSignature = signature
            refreshTileAndComplication()
        }
    }

    /**
     * The watch's own player changed: the tile and the complication follow it as they follow the
     * phone (they read whichever player is in front). The watch-face entry is the session's own.
     */
    fun onWatchState(snapshot: PlaybackSnapshot?) {
        if (!watchPlaying) return
        val signature = "watch|" + signatureOf(snapshot)
        if (signature != prefs.lastSignature) {
            prefs.lastSignature = signature
            refreshTileAndComplication()
        }
    }

    /** Which of the watch's players is in front changed. */
    fun onModeChanged(watchInFront: Boolean) {
        if (watchInFront == watchPlaying) return
        watchPlaying = watchInFront
        updateEntry()
        refreshTileAndComplication()
    }

    /**
     * Tells the phone how the watch wants its notifications ([WatchSurfaces]): today only whether
     * the phone's media notification should stay on the phone, for the mirror experiment.
     */
    suspend fun publishWatchSurfaces() {
        val surfaces = WatchSurfaces(phoneMediaLocalOnly = prefs.mirrorNowBar, changedAtEpochMs = System.currentTimeMillis())
        runCatching {
            Wearable.getDataClient(context)
                .putDataItem(
                    PutDataRequest.create(WearPaths.WATCH)
                        .setData(WearCodec.encode(WatchSurfaces.serializer(), surfaces))
                        .setUrgent(),
                )
                .await()
        }
    }

    private fun updateEntry() {
        val snapshot = current
        val entry = NowBarPolicy.entry(prefs.nowBar, snapshot, watchPlaying, prefs.mirrorNowBar)
        ongoing.update(snapshot, enabled = entry == NowBarEntry.ONGOING)
        if (entry == NowBarEntry.MIRROR) mirror?.show(snapshot) else mirror?.hide()
    }

    /** A cover arrived; it matters if it is the one on show. */
    fun onCoverStored(key: String) {
        if (current?.track?.artKey == key) refreshTileAndComplication()
    }

    /** A switch about the watch face moved in Altro. */
    fun onPrefsChanged() {
        updateEntry()
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
