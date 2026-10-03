package dev.lelonio.square.wear

import android.util.Log
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import dev.lelonio.square.SquareApplication
import dev.lelonio.square.nativecore.NativeBridge
import dev.lelonio.square.playback.PlaybackService
import dev.pampa.fluidify.wear.protocol.FileHeader
import dev.pampa.fluidify.wear.protocol.FileRequest
import dev.pampa.fluidify.wear.protocol.WearCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger

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
 */
class WatchFileServer(private val app: SquareApplication) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channels by lazy { Wearable.getChannelClient(app) }
    private val running = AtomicInteger()

    /** Where a track fetched at the watch's quality waits to be sent. */
    private val staging: File get() = File(app.cacheDir, "watch-staging")

    fun serve(channel: ChannelClient.Channel) {
        scope.launch {
            if (running.incrementAndGet() == 1) WatchTransferService.start(app)
            try {
                transfer(channel)
            } catch (error: Exception) {
                Log.i(TAG, "transfer to the watch stopped: ${error.message}")
            } finally {
                runCatching { channels.close(channel).await() }
                if (running.decrementAndGet() == 0) WatchTransferService.stop(app)
            }
        }
    }

    private suspend fun transfer(channel: ChannelClient.Channel) {
        val input = channels.getInputStream(channel).await()
        val line = readLine(input) ?: return
        val request = WearCodec.decodeOrNull(FileRequest.serializer(), line.encodeToByteArray()) ?: return
        channels.getOutputStream(channel).await().use { out ->
            val stagedRoot = request.stageKbps?.let { staging }
            val sidecar = if (stagedRoot != null) {
                stage(request.uri, request.stageKbps!!, stagedRoot)
            } else {
                runCatching { NativeBridge.setDownloadRoot(app.downloads.root.absolutePath) }
                runCatching { NativeBridge.downloadState(request.uri) }.getOrNull()?.takeIf { it != "null" }
            }
            if (sidecar == null) {
                header(out, FileHeader(ok = false, error = "not on the phone"))
                return
            }
            val audio = audioFile(stagedRoot ?: app.downloads.root, sidecar)
            if (audio == null || !audio.isFile) {
                header(out, FileHeader(ok = false, error = "the file is missing"))
                return
            }
            val total = audio.length()
            val offset = request.offset.takeIf { it in 1 until total } ?: 0L
            header(out, FileHeader(ok = true, sidecar = sidecar, totalBytes = total))
            RandomAccessFile(audio, "r").use { file ->
                file.seek(offset)
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val read = file.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                }
            }
            out.flush()
            if (stagedRoot != null) cleanUp(stagedRoot, sidecar)
        }
    }

    /** Fetches the track at the watch's quality into [root], with the phone's engine. */
    private suspend fun stage(uri: String, kbps: Int, root: File): String? {
        app.wearBridge.wakePlayback()
        runCatching { PlaybackService.connect(app) }
        val connected = withTimeoutOrNull(ENGINE_WAIT_MS) {
            while (!NativeBridge.isConnected) delay(POLL_MS)
            true
        } ?: false
        if (!connected) return null
        return runCatching { NativeBridge.downloadTrackInto(uri, kbps, root.absolutePath) }
            .onFailure { Log.i(TAG, "staging $uri failed: ${it.message}") }
            .getOrNull()
    }

    private fun cleanUp(root: File, sidecar: String) {
        audioFile(root, sidecar)?.delete()
        trackId(sidecar)?.let { id -> File(File(File(root, "meta"), id.take(2)), id.drop(2) + ".json").delete() }
    }

    /** `<root>/audio/<xx>/<rest>`, the native store's layout (native/src/downloads.rs). */
    private fun audioFile(root: File, sidecar: String): File? {
        val id = trackId(sidecar) ?: return null
        return File(File(File(root, "audio"), id.take(2)), id.drop(2))
    }

    private fun trackId(sidecar: String): String? = runCatching {
        (WearCodec.json.parseToJsonElement(sidecar) as JsonObject)["trackId"]?.jsonPrimitive?.content
    }.getOrNull()?.takeIf { it.length == 32 }

    private fun header(out: OutputStream, header: FileHeader) {
        out.write(WearCodec.encode(FileHeader.serializer(), header))
        out.write('\n'.code)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next < 0) return null
            if (next == '\n'.code) return bytes.toString(Charsets.UTF_8.name())
            bytes.write(next)
            if (bytes.size() > MAX_LINE) return null
        }
    }

    private companion object {
        const val TAG = "WatchFileServer"
        const val BUFFER = 64 * 1024
        const val MAX_LINE = 16 * 1024
        const val ENGINE_WAIT_MS = 30_000L
        const val POLL_MS = 250L
    }
}
