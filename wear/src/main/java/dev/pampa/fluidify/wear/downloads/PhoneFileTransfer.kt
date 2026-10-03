package dev.pampa.fluidify.wear.downloads

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import dev.pampa.fluidify.wear.protocol.FileHeader
import dev.pampa.fluidify.wear.protocol.FileRequest
import dev.pampa.fluidify.wear.protocol.WearCodec
import dev.pampa.fluidify.wear.protocol.WearPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.DataInputStream
import java.io.FileOutputStream
import java.io.InputStream

/**
 * A track from the phone over Bluetooth, resumable.
 *
 * The watch opens a channel to the phone, says which track and how much of it
 * it already has (its `.part`), and the phone answers with the sidecar and the
 * rest of the file — the very bytes of its own download, still encrypted. A
 * transfer cut off by the watch walking out of range keeps what arrived, and
 * the next attempt starts from there.
 *
 * With [stageKbps] the phone first fetches the track at that quality into a
 * scratch area of its own, for a track it does not have as the watch wants it.
 */
class PhoneFileTransfer(private val context: Context, private val store: WatchDownloadStore) {

    private val channels by lazy { Wearable.getChannelClient(context) }

    suspend fun fetch(nodeId: String, trackUri: String, stageKbps: Int? = null): Result<Unit> = withContext(Dispatchers.IO) {
        val part = store.partFile(trackUri) ?: return@withContext Result.failure(IllegalArgumentException("not a track"))
        part.parentFile?.mkdirs()
        val offset = if (part.isFile) part.length() else 0L
        val channel = runCatching { channels.openChannel(nodeId, WearPaths.DOWNLOAD_FILE).await() }
            .getOrElse { return@withContext Result.failure(it) }
        try {
            val request = WearCodec.encode(FileRequest.serializer(), FileRequest(trackUri, offset, stageKbps))
            channels.getOutputStream(channel).await().use { out ->
                out.write(request)
                out.write('\n'.code)
                out.flush()
            }
            val input = DataInputStream(channels.getInputStream(channel).await())
            input.use {
                // Staging may take the phone a while: it is fetching the track first.
                val headerLine = withTimeoutOrNull(if (stageKbps != null) STAGE_WAIT_MS else HEADER_WAIT_MS) { readLine(input) }
                    ?: return@withContext Result.failure(IllegalStateException("the phone did not answer"))
                val header = WearCodec.decodeOrNull(FileHeader.serializer(), headerLine.encodeToByteArray())
                    ?: return@withContext Result.failure(IllegalStateException("unreadable answer"))
                val sidecar = header.sidecar
                if (!header.ok || sidecar == null) {
                    return@withContext Result.failure(IllegalStateException(header.error ?: "refused"))
                }
                // A staged copy may be a different file from the one half-received before.
                val resumeFrom = if (offset > 0 && offset < header.totalBytes) offset else 0L
                if (resumeFrom == 0L && part.isFile) part.delete()
                FileOutputStream(part, true).use { out -> input.copyTo(out, BUFFER) }
                if (part.length() != header.totalBytes) {
                    return@withContext Result.failure(IllegalStateException("cut off at ${part.length()} of ${header.totalBytes}"))
                }
                if (!store.adopt(trackUri, sidecar)) {
                    return@withContext Result.failure(IllegalStateException("could not file the track"))
                }
            }
            Result.success(Unit)
        } catch (error: Exception) {
            Log.i(TAG, "transfer of $trackUri stopped: ${error.message}")
            Result.failure(error)
        } finally {
            runCatching { channels.close(channel).await() }
        }
    }

    /** One line, up to the newline, without buffering past it: the bytes after it are the file. */
    private fun readLine(input: InputStream): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next < 0) return null
            if (next == '\n'.code) return bytes.toString(Charsets.UTF_8.name())
            bytes.write(next)
            if (bytes.size() > MAX_HEADER) return null
        }
    }

    private companion object {
        const val TAG = "PhoneFileTransfer"
        const val BUFFER = 64 * 1024
        const val MAX_HEADER = 64 * 1024
        const val HEADER_WAIT_MS = 20_000L
        const val STAGE_WAIT_MS = 180_000L
    }
}
