package dev.pampa.fluidify.wear.downloads

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.lelonio.square.nativecore.NativeBridge
import dev.pampa.fluidify.wear.WearApp
import dev.pampa.fluidify.wear.protocol.ContextPage
import dev.pampa.fluidify.wear.protocol.PhoneDownloads
import dev.pampa.fluidify.wear.protocol.RpcMethod
import dev.pampa.fluidify.wear.protocol.logic.Transport
import dev.pampa.fluidify.wear.protocol.logic.TransportFacts
import dev.pampa.fluidify.wear.protocol.logic.TransportPlanner
import dev.pampa.fluidify.wear.standalone.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Works through what the watch should have and does not, one track at a time.
 *
 * For each track it asks the phone what it has (its sidecar: format and key)
 * and lets [TransportPlanner] order the ways to fetch it, then tries them in
 * that order: the watch's own Wi-Fi (with the phone's key when the phone has
 * the same file), the phone's file over Bluetooth, or the phone fetching it at
 * the watch's quality and relaying it. A way that cannot run now (no Wi-Fi came
 * up, the phone is away) falls through to the next.
 *
 * A pass is bounded ([PASS_MS]); what is left is the next pass's, which
 * WorkManager runs after a back-off. Track lists are refreshed from the phone
 * first, so a playlist edited there gains and loses tracks here too.
 */
class WatchDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val app get() = applicationContext as WearApp

    override suspend fun doWork(): Result {
        val downloads = app.downloads
        val store = downloads.store
        val started = System.currentTimeMillis()
        val phone = app.link.reachablePhone()

        if (phone != null) refreshTrackLists(store)
        var pending = store.pending()
        if (pending.isEmpty()) {
            downloads.changed(schedule = false)
            downloads.publish()
            return Result.success()
        }
        if (store.bytesFree() < WatchDownloads.RESERVE_BYTES) {
            downloads.changed(schedule = false, paused = WatchDownloads.PAUSED_STORAGE)
            downloads.publish()
            return Result.success()
        }

        val standalone = app.standalone
        val transfer = PhoneFileTransfer(applicationContext, store)
        var engineLeased = false
        var route: Route? = null
        var keysRefused = false
        val phoneFiles = if (phone != null) phoneSidecars(pending) else emptyMap()

        try {
            for (track in pending) {
                if (System.currentTimeMillis() - started > PASS_MS || isStopped) break
                downloads.changed(schedule = false, active = track)
                downloads.publish()

                val phoneSidecar = phoneFiles[track]
                val facts = TransportFacts(
                    phoneReachable = phone != null,
                    phoneHasFile = phoneSidecar != null,
                    sameQuality = WatchDownloadStore.formatKbps(formatOf(phoneSidecar)) == downloads.qualityKbps,
                    preference = downloads.preference,
                )
                var done = false
                for (attempt in TransportPlanner.plan(facts)) {
                    if (TransportPlanner.needsWatchNetwork(attempt)) {
                        if (attempt == Transport.WATCH_ALONE && keysRefused) continue
                        // Asked once per pass: a Galaxy Watch turns Wi-Fi on for this and takes a moment.
                        val got = route ?: standalone.network.acquire().also { route = it }
                        if (got != Route.WIFI && got != Route.CELLULAR) continue
                        if (!engineLeased) engineLeased = standalone.engine.acquire()
                        if (!engineLeased) continue
                    }
                    val outcome = runCatching {
                        when (attempt) {
                            Transport.WATCH_WITH_PHONE_KEY -> withContext(Dispatchers.IO) { NativeBridge.downloadWithSidecar(phoneSidecar!!) }
                            Transport.WATCH_ALONE -> withContext(Dispatchers.IO) { NativeBridge.downloadTrack(track, downloads.qualityKbps) }
                            Transport.PHONE_FILE -> transfer.fetch(phone!!, track).getOrThrow()
                            Transport.PHONE_STAGED -> transfer.fetch(phone!!, track, stageKbps = downloads.qualityKbps).getOrThrow()
                        }
                    }
                    if (outcome.isSuccess && store.has(track)) {
                        done = true
                        break
                    }
                    val message = outcome.exceptionOrNull()?.message.orEmpty()
                    Log.i(TAG, "$attempt failed for $track: $message")
                    // Spotify refusing keys is about the pace, not this track: stop asking for the rest of the pass.
                    if (message.contains(KEY_THROTTLED)) keysRefused = true
                }
                if (!done) Log.i(TAG, "$track not fetched this pass")
            }
        } finally {
            if (engineLeased) standalone.engine.release()
            if (route != null) standalone.network.release()
        }

        pending = store.pending()
        downloads.changed(schedule = false, paused = if (pending.isNotEmpty() && phone == null && route != Route.WIFI) WatchDownloads.PAUSED_OFFLINE else null)
        downloads.publish()
        return if (pending.isEmpty()) Result.success() else Result.retry()
    }

    /** Each kept owner's tracks as the phone reads them now. */
    private suspend fun refreshTrackLists(store: WatchDownloadStore) {
        for (owner in store.owners.value) {
            val tracks = mutableListOf<String>()
            var offset = 0
            var title: String? = null
            while (true) {
                val page = app.link.request(RpcMethod.Context(owner.uri, offset, PAGE), ContextPage.serializer()).getOrNull() ?: break
                title = title ?: page.title.takeIf { it.isNotEmpty() }
                tracks += page.tracks.map { it.uri }.filter { it.startsWith("spotify:track:") }
                offset += page.tracks.size
                if (page.tracks.isEmpty() || offset >= page.total || offset >= MAX_TRACKS) break
            }
            if (tracks.isNotEmpty()) store.setTracks(owner.uri, tracks, title)
        }
    }

    /** The phone's sidecars for these tracks, where it has them. */
    private suspend fun phoneSidecars(uris: List<String>): Map<String, String> =
        uris.chunked(QUERY).flatMap { chunk ->
            app.link.request(RpcMethod.Downloads(chunk), PhoneDownloads.serializer()).getOrNull()?.tracks.orEmpty()
        }.mapNotNull { download -> download.sidecar?.let { download.uri to it } }.toMap()

    private fun formatOf(sidecar: String?): String? = sidecar?.let {
        runCatching {
            dev.pampa.fluidify.wear.protocol.WearCodec.json.parseToJsonElement(it)
                .let { element -> (element as kotlinx.serialization.json.JsonObject)["format"] }
                ?.let { value -> (value as kotlinx.serialization.json.JsonPrimitive).content }
        }.getOrNull()
    }

    private companion object {
        const val TAG = "WatchDownloads"

        /** Under WorkManager's ten minutes, with room to publish and let go. */
        const val PASS_MS = 8 * 60_000L
        const val PAGE = 200
        const val QUERY = 100
        const val MAX_TRACKS = 2_000

        /** native/src/downloads.rs KEY_THROTTLED. */
        const val KEY_THROTTLED = "audio keys are being refused"
    }
}
