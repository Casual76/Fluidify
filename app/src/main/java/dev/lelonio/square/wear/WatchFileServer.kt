package dev.lelonio.square.wear

import android.util.Log
import android.content.ComponentName
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import dev.lelonio.square.SquareApplication
import dev.lelonio.square.nativecore.NativeBridge
import dev.lelonio.square.playback.PlaybackService
import dev.pampa.fluidify.wear.protocol.FileHeader
import dev.pampa.fluidify.wear.protocol.FileRequest
import dev.pampa.fluidify.wear.protocol.SpotifyIds
import dev.pampa.fluidify.wear.protocol.WearCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.guava.await as awaitController
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import dev.pampa.fluidify.wear.protocol.logic.FileResume
import dev.pampa.fluidify.wear.protocol.logic.DownloadFailure
import dev.lelonio.square.io.ReadWatchdog
import dev.lelonio.square.io.WriteWatchdog

/**
 * Sends the watch a downloaded track over Bluetooth.
 *
 * The watch opens a channel and asks for one track from an offset (what it
 * already has); this answers with the track's sidecar and the rest of the file,
 * the phone's own download byte for byte. When the watch wants a quality the
 * phone does not have, the phone first fetches the track at that quality into a
 * scratch directory of its own ([staging]), sends it, and deletes it: the
 * listener's library is never touched by what the watch wants.
 *
 * Each transfer runs on its own coroutine, never on the listener service's
 * thread: that thread also carries the watch's playback commands, and a
 * five-megabyte transfer must not make the remote freeze. [WatchTransferService]
 * keeps the process in the foreground while any is running, where Android allows
 * it to start.
 *
 * A staged track lives in a directory named after the track and the quality, not after the
 * attempt. The watch asks again when a transfer is cut off (it does so three times in one pass),
 * and every ask used to fetch the track from scratch: a new download, and a new audio key from
 * Spotify, which throttles them. Now the second ask finds the finished file and only sends it. The
 * directory goes when a transfer completes, or when what is in it turns out to be unusable; one
 * whose transfer was cut off stays, for the retry, until [cleanAbandonedStaging] finds it
 * forgotten.
 */
class WatchFileServer(private val app: SquareApplication) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channels by lazy { Wearable.getChannelClient(app) }
    private val running = AtomicInteger()

    /** One lock per staged track and quality: two asks for the same file must not fetch it twice. */
    private val stagingLocks = ConcurrentHashMap<String, Mutex>()

    /** Where a track fetched at the watch's quality waits to be sent. */
    private val staging: File get() = File(app.cacheDir, "watch-staging")

    fun serve(channel: ChannelClient.Channel) {
        scope.launch {
            val wrapUp = ChannelWrapUp(channels, channel)
            var answered = false
            if (running.incrementAndGet() == 1) WatchTransferService.start(app)
            try {
                cleanAbandonedStaging()
                wrapUp.start()
                answered = transfer(channel)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.i(TAG, "transfer to the watch stopped: ${error.message}")
            } finally {
                // Not closed at once: see ChannelWrapUp. Only an answer that was completely written
                // is worth waiting for the watch's close on.
                withContext(NonCancellable) {
                    wrapUp.finish(delivered = answered)
                    if (running.decrementAndGet() == 0) WatchTransferService.stop(app)
                }
            }
        }
    }

    /** True when the watch was given an answer (the file, or the reason there is none) in full. */
    private suspend fun transfer(channel: ChannelClient.Channel): Boolean {
        val line = ReadWatchdog(channels.getInputStream(channel).await(), 30_000L) { channels.close(channel) }
            .use { WearChannelIo.readLine(it, MAX_LINE) } ?: return false
        val request = WearCodec.decodeOrNull(FileRequest.serializer(), line.encodeToByteArray()) ?: return false
        // Closing the stream when the answer is written is what tells the watch the file has ended.
        channels.getOutputStream(channel).await().use { out -> answer(out, channel, request) }
        return true
    }

    private suspend fun answer(out: OutputStream, channel: ChannelClient.Channel, request: FileRequest) {
        val stageKbps = request.stageKbps
        val stagedRoot = if (stageKbps != null) {
            val key = SpotifyIds.trackHex(request.uri)?.let { "$it-$stageKbps" }
            if (key == null) {
                header(out, FileHeader(ok = false, error = "track-unavailable"))
                return
            }
            File(staging, key)
        } else {
            null
        }
        val sidecar = if (stagedRoot != null && stageKbps != null) {
            stageOnce(request.uri, stageKbps, stagedRoot).getOrElse {
                header(out, FileHeader(ok = false, error = if (DownloadFailure.unavailable(it.message)) "track-unavailable" else "temporarily-unavailable"))
                return
            }
        } else {
            catchingNonCancel { NativeBridge.setDownloadRoot(app.downloads.root.absolutePath) }
            catchingNonCancel { NativeBridge.downloadState(request.uri) }.getOrNull()?.takeIf { it != "null" }
        }
        if (sidecar == null) {
            header(out, FileHeader(ok = false, error = "not on the phone"))
            return
        }
        val audio = audioFile(stagedRoot ?: app.downloads.root, sidecar)
        if (audio == null || !audio.isFile) {
            // A staged file that is not there is not going to be there for the retry either.
            if (stagedRoot != null) cleanUp(stagedRoot)
            header(out, FileHeader(ok = false, error = "the file is missing"))
            return
        }
        val total = audio.length()
        val actualId = catchingNonCancel { (WearCodec.json.parseToJsonElement(sidecar) as JsonObject)["fileId"]?.jsonPrimitive?.content }.getOrNull()
        val offset = FileResume.offset(request.offset, total, request.fileId, actualId)
        header(out, FileHeader(ok = true, sidecar = sidecar, totalBytes = total, offset = offset))
        // From here the watch is receiving, and a radio that stops taking bytes must not leave this
        // thread blocked in a write for ever: the watchdog closes the channel under it.
        WriteWatchdog(out, WRITE_IDLE_MS, WRITE_DEADLINE_MS) { channels.close(channel) }.use { guarded ->
            RandomAccessFile(audio, "r").use { file ->
                file.seek(offset)
                val buffer = ByteArray(BUFFER)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = file.read(buffer)
                    if (read <= 0) break
                    guarded.write(buffer, 0, read)
                }
            }
            guarded.flush()
        }
        // Only a transfer that went all the way: after a cut-off the file is kept for the retry.
        if (stagedRoot != null) cleanUp(stagedRoot)
    }

    /**
     * The staged track for [uri] at [kbps] in [root]: the one already there from an earlier ask
     * when it is complete, fetched now otherwise. A half-made earlier attempt is thrown away, and
     * so is the outcome of one that failed here: nothing incomplete is ever left to be sent.
     */
    private suspend fun stageOnce(uri: String, kbps: Int, root: File): Result<String> {
        val lock = stagingLocks.computeIfAbsent(root.name) { Mutex() }
        return lock.withLock {
            reusableSidecar(root)?.let { return@withLock Result.success(it) }
            root.deleteRecursively()
            stage(uri, kbps, root)
                .onSuccess { sidecar -> markFinished(root, sidecar) }
                .onFailure { root.deleteRecursively() }
        }
    }

    /** The sidecar of a staged track that was finished, or null: written only once the fetch ended. */
    private fun reusableSidecar(root: File): String? {
        val sidecar = catchingNonCancel { File(root, SIDECAR_FILE).takeIf { it.isFile }?.readText() }.getOrNull() ?: return null
        return sidecar.takeIf { audioFile(root, it)?.isFile == true }
    }

    /** Marks the fetch in [root] as complete, by writing its sidecar beside it (atomically). */
    private fun markFinished(root: File, sidecar: String) {
        catchingNonCancel {
            val temporary = File(root, "$SIDECAR_FILE.tmp")
            temporary.writeText(sidecar)
            if (!temporary.renameTo(File(root, SIDECAR_FILE))) temporary.delete()
        }
    }

    /** Fetches the track at the watch's quality into [root], with the phone's engine. */
    private suspend fun stage(uri: String, kbps: Int, root: File): Result<String> {
        if (!app.spotifySignedIn || !app.tokenStore.isLoggedIn || kbps !in setOf(96, 160, 320)) return Result.failure(IllegalStateException("signed-out"))
        var controller: MediaController? = null
        return try {
            // A distinct binding lasts through the blocking native download, even when the command
            // waker's short hold expires. Every overlapping staging owns its own binding. Built
            // inside the try: a service that cannot be bound is a failed staging, which the watch
            // is told of, not an exception that leaves it waiting for a header.
            controller = withContext(Dispatchers.Main) {
                MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java)))
                    .buildAsync().awaitController()
            }
            catchingNonCancel { PlaybackService.connect(app) }
            val connected = withTimeoutOrNull(ENGINE_WAIT_MS) {
                while (!NativeBridge.isConnected) delay(POLL_MS)
                true
            } ?: false
            if (!connected) {
                Result.failure(IllegalStateException("engine-unavailable"))
            } else {
                catchingNonCancel { NativeBridge.downloadTrackInto(uri, kbps, root.absolutePath) }
                    .onFailure { Log.i(TAG, "staging failed: ${it.message}") }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.i(TAG, "staging failed: ${error.javaClass.simpleName}")
            Result.failure(error)
        } finally {
            controller?.let { bound -> withContext(NonCancellable + Dispatchers.Main) { bound.release() } }
        }
    }

    /**
     * Removes what has been sitting in [staging] for hours: a transfer cut off for good, a process
     * killed mid-fetch, the per-attempt directories an older build left. Whole directories, not
     * just the files in them (the empty shells used to pile up), and never one that is being
     * fetched or sent right now.
     */
    private fun cleanAbandonedStaging() {
        val before = System.currentTimeMillis() - ABANDONED_AFTER_MS
        staging.listFiles()?.forEach { entry ->
            if (stagingLocks[entry.name]?.isLocked == true) return@forEach
            val touched = entry.walkTopDown().maxOf { it.lastModified() }
            if (touched < before) entry.deleteRecursively()
        }
    }

    private fun cleanUp(root: File) {
        root.deleteRecursively()
    }

    /** `<root>/audio/<xx>/<rest>`, the native store's layout (native/src/downloads.rs). */
    private fun audioFile(root: File, sidecar: String): File? {
        val id = trackId(sidecar) ?: return null
        return File(File(File(root, "audio"), id.take(2)), id.drop(2))
    }

    private fun trackId(sidecar: String): String? = catchingNonCancel {
        (WearCodec.json.parseToJsonElement(sidecar) as JsonObject)["trackId"]?.jsonPrimitive?.content
    }.getOrNull()?.takeIf { it.length == 32 }

    private fun header(out: OutputStream, header: FileHeader) =
        WearChannelIo.writeLine(out, FileHeader.serializer(), header)

    private companion object {
        const val TAG = "WatchFileServer"
        const val BUFFER = 64 * 1024
        const val MAX_LINE = 16 * 1024
        const val ENGINE_WAIT_MS = 30_000L
        const val POLL_MS = 250L

        /** The sidecar kept beside a finished staged track; see [stageOnce]. */
        const val SIDECAR_FILE = "sidecar.json"

        /** The watch's retries come within minutes; two hours is a transfer given up on. */
        const val ABANDONED_AFTER_MS = 2 * 60 * 60_000L

        /** The same budget the update push gives its stream (WatchUpdateCoordinator.sendPrepared). */
        const val WRITE_IDLE_MS = 30_000L
        const val WRITE_DEADLINE_MS = 15 * 60_000L
    }
}
