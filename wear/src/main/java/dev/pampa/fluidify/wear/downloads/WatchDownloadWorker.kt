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
import dev.pampa.fluidify.wear.protocol.logic.DownloadFailure
import dev.pampa.fluidify.wear.standalone.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
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
        val expected = inputData.getString("account") ?: return Result.success()
        val sessions = app.link.accountSessions
        val phoneAccount = sessions.capture()?.takeIf {
            it.namespace == expected && it.session == inputData.getString("phone-session")
        }
        var access = downloads.capture()
        val lineage = inputData.getString("local-lineage")
        if (lineage != null && access?.lineage != lineage) return Result.success()
        // A phone plan can bootstrap a first sign-in, but only through the live auth-grant path.
        if (access == null && phoneAccount != null) {
            if (!downloads.store.phoneEdit(sessions, phoneAccount) { it.owners.value.isNotEmpty() }) return Result.success()
            val engine = app.standalone.engine
            val lease = engine.acquireDownload() ?: return Result.retry()
            try {
                engine.awaitConnected(20_000L)
                access = downloads.capture()
            } finally { engine.releaseDownload(lease) }
        }
        val account = access?.takeIf { it.namespace == expected }
            ?: return if (phoneAccount != null && sessions.isCurrent(phoneAccount)) Result.retry() else Result.success()
        val store = downloads.store.forAccount(account)
        fun current() = downloads.authority.isCurrent(account)
        fun phoneCurrent() = current() && phoneAccount != null && sessions.isCurrent(phoneAccount)
        val started = System.currentTimeMillis()
        val phone = if (phoneCurrent()) app.link.reachablePhone() else null

        // A fresh look at the disk: the engine writes its own downloads, and what the store
        // remembered of the last pass may be old.
        store.invalidateAll()
        if (phone != null && phoneCurrent()) refreshTrackLists(store, phoneAccount!!)
        var pending = store.pending()
        if (pending.isEmpty()) {
            downloads.changed(schedule = false, expected = account)
            downloads.publish(final = true)
            return Result.success()
        }
        if (store.bytesFree() < WatchDownloads.RESERVE_BYTES) {
            downloads.changed(schedule = false, paused = WatchDownloads.PAUSED_STORAGE, expected = account)
            downloads.publish(final = true)
            return Result.success()
        }

        val standalone = app.standalone
        val transfer = PhoneFileTransfer(applicationContext, store, downloads.authority, account, phoneAccount)
        var engineLease: String? = null
        var route: Route? = null
        var keysRefused = false
        val phoneFiles = if (phone != null && phoneCurrent()) phoneSidecars(pending, phoneAccount!!) else emptyMap()

        try {
            for (track in pending) {
                if (!current()) return Result.success()
                if (System.currentTimeMillis() - started > PASS_MS || isStopped) break
                downloads.changed(schedule = false, active = track, expected = account)
                downloads.publish()

                val phoneSidecar = phoneFiles[track]
                val facts = TransportFacts(
                    phoneReachable = phone != null && phoneCurrent(),
                    phoneHasFile = phoneSidecar != null,
                    sameQuality = WatchDownloadStore.formatKbps(formatOf(phoneSidecar)) == downloads.qualityKbps,
                    preference = downloads.preference,
                )
                var done = false
                var attempted = false
                var unavailable = false
                for (attempt in TransportPlanner.plan(facts)) {
                    if (!current()) return Result.success()
                    if (attempt != Transport.WATCH_ALONE && !phoneCurrent()) continue
                    if (TransportPlanner.needsWatchNetwork(attempt)) {
                        if (attempt == Transport.WATCH_ALONE && keysRefused) continue
                        // Only after the preferred Bluetooth attempt failed (or Wi-Fi was explicitly selected).
                        val got = route ?: standalone.network.acquire(preferWifi = true).also { route = it }
                        if (got != Route.WIFI && got != Route.CELLULAR) continue
                        if (engineLease == null) engineLease = standalone.engine.acquireDownload()
                        if (engineLease == null) continue
                        if (!standalone.engine.awaitConnected(20_000L) || !current()) continue
                    }
                    attempted = true
                    val outcome = runCatching {
                        when (attempt) {
                            Transport.WATCH_WITH_PHONE_KEY -> standalone.engine.download(account) { NativeBridge.downloadWithSidecar(phoneSidecar!!) }
                            Transport.WATCH_ALONE -> standalone.engine.download(account) { NativeBridge.downloadTrack(track, downloads.qualityKbps) }
                            Transport.PHONE_FILE -> transfer.fetch(phone!!, track).getOrThrow()
                            Transport.PHONE_STAGED -> transfer.fetch(phone!!, track, stageKbps = downloads.qualityKbps).getOrThrow()
                        }
                    }
                    (outcome.exceptionOrNull() as? CancellationException)?.let { throw it }
                    // The engine may have just written the files itself.
                    if (!current()) return Result.success()
                    store.invalidate(track)
                    if (outcome.isSuccess && store.has(track)) {
                        done = true
                        break
                    }
                    val message = outcome.exceptionOrNull()?.message.orEmpty()
                    unavailable = unavailable || DownloadFailure.unavailable(message)
                    Log.i(TAG, "$attempt failed for $track: $message")
                    // Spotify refusing keys is about the pace, not this track: stop asking for the rest of the pass.
                    if (message.contains(KEY_THROTTLED)) keysRefused = true
                }
                if (done) store.clearFailure(track)
                else if (attempted && unavailable && !keysRefused && !isStopped) store.recordFailure(track)
            }
        } finally {
            engineLease?.let { standalone.engine.releaseDownload(it) }
            if (route != null) standalone.network.release(preferWifi = true)
        }

        if (!current()) return Result.success()
        pending = store.pending()
        downloads.changed(schedule = false, paused = if (pending.isNotEmpty() && phone == null && route != Route.WIFI) WatchDownloads.PAUSED_OFFLINE else null, expected = account)
        downloads.publish(final = true)
        return if (pending.isEmpty()) Result.success() else Result.retry()
    }

    /** Each kept owner's tracks as the phone reads them now. */
    private suspend fun refreshTrackLists(store: WatchDownloadFiles, phone: dev.pampa.fluidify.wear.link.PhoneAccountSession.Scope) {
        for (owner in store.owners.value) {
            val complete = CompleteTrackList.read(MAX_TRACKS, PAGE) { offset, limit ->
                app.link.request(RpcMethod.Context(owner.uri, offset, limit), ContextPage.serializer(), expectedAccount = phone).getOrNull()
            } ?: continue
            store.setTracks(owner.uri, complete.second, complete.first)
        }
    }

    /** The phone's sidecars for these tracks, where it has them. */
    private suspend fun phoneSidecars(uris: List<String>, phone: dev.pampa.fluidify.wear.link.PhoneAccountSession.Scope): Map<String, String> =
        uris.chunked(QUERY).flatMap { chunk ->
            app.link.request(RpcMethod.Downloads(chunk), PhoneDownloads.serializer(), expectedAccount = phone).getOrNull()?.tracks.orEmpty()
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
