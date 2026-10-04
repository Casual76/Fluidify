package dev.pampa.fluidify.wear.downloads

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import dev.pampa.fluidify.wear.protocol.FileHeader
import dev.pampa.fluidify.wear.protocol.FileRequest
import dev.pampa.fluidify.wear.protocol.WearCodec
import dev.pampa.fluidify.wear.protocol.WearPaths
import dev.lelonio.square.io.ReadWatchdog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.delay
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

    /**
     * Fetches [trackUri], picking up where a cut-off attempt stopped.
     *
     * A Bluetooth channel drops for ordinary reasons — the phone's radio reconnecting, the watch at
     * the edge of range, Android pausing the phone's process for a moment — and the tests saw
     * "Channel closed unexpectedly" mid-file. Until now the half file waited for the next
     * WorkManager pass, minutes later. Now the same pass asks again from the `.part`'s length, a
     * few times, as long as the phone is still there; a refusal (the phone does not have the
     * track) is not asked twice.
     */
    suspend fun fetch(nodeId: String, trackUri: String, stageKbps: Int? = null): Result<Unit> {
        var result = fetchOnce(nodeId, trackUri, stageKbps)
        var attempt = 0
        while (result.isFailure && TransferRetry.shouldRetry(result.exceptionOrNull(), attempt) && phoneStillThere(nodeId)) {
            delay(TransferRetry.backoffMs(attempt))
            attempt++
            Log.i(TAG, "resuming $trackUri, attempt ${attempt + 1}")
            result = fetchOnce(nodeId, trackUri, stageKbps)
        }
        return result
    }

    private suspend fun phoneStillThere(nodeId: String): Boolean = runCatching {
        Wearable.getNodeClient(context).connectedNodes.await().any { it.id == nodeId }
    }.getOrDefault(false)

    private suspend fun fetchOnce(nodeId: String, trackUri: String, stageKbps: Int?): Result<Unit> = withContext(Dispatchers.IO) {
        val part = store.partFile(trackUri) ?: return@withContext Result.failure(IllegalArgumentException("not a track"))
        part.parentFile?.mkdirs()
        val identity = store.partIdentityFile(trackUri)!!
        val fileId = runCatching { identity.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
        val offset = if (part.isFile && fileId != null) part.length() else 0L
        val channel = runCatching { channels.openChannel(nodeId, WearPaths.DOWNLOAD_FILE).await() }
            .getOrElse { return@withContext Result.failure(it) }
        try {
            val request = WearCodec.encode(FileRequest.serializer(), FileRequest(trackUri, offset, stageKbps, fileId))
            channels.getOutputStream(channel).await().use { out ->
                out.write(request)
                out.write('\n'.code)
                out.flush()
            }
            val guarded = ReadWatchdog(channels.getInputStream(channel).await(), if (stageKbps != null) STAGE_WAIT_MS else HEADER_WAIT_MS) { channels.close(channel) }
            val input = DataInputStream(guarded)
            input.use {
                // Staging may take the phone a while: it is fetching the track first.
                val headerLine = runInterruptible { readLine(input) }
                    ?: return@withContext Result.failure(IllegalStateException("the phone did not answer"))
                val header = WearCodec.decodeOrNull(FileHeader.serializer(), headerLine.encodeToByteArray())
                    ?: return@withContext Result.failure(IllegalStateException("unreadable answer"))
                val sidecar = header.sidecar
                if (!header.ok || sidecar == null) {
                    return@withContext Result.failure(TransferRefused(header.error ?: "refused"))
                }
                // A staged copy may be a different file from the one half-received before.
                val actualId = runCatching { WearCodec.json.parseToJsonElement(sidecar).jsonObject["fileId"]?.jsonPrimitive?.content }.getOrNull()
                    ?: return@withContext Result.failure(TransferRefused("missing file identity"))
                val resumeFrom = header.offset
                if (resumeFrom != 0L && (resumeFrom != offset || actualId != fileId || resumeFrom >= header.totalBytes)) {
                    return@withContext Result.failure(TransferRefused("invalid resume offset"))
                }
                if (resumeFrom == 0L && part.isFile) part.delete()
                identity.writeText(actualId)
                guarded.timeoutAfterProgress(30_000L)
                runInterruptible { FileOutputStream(part, resumeFrom > 0).use { out -> input.copyTo(out, BUFFER) } }
                if (part.length() != header.totalBytes) {
                    return@withContext Result.failure(IllegalStateException("cut off at ${part.length()} of ${header.totalBytes}"))
                }
                if (!store.adopt(trackUri, sidecar)) {
                    return@withContext Result.failure(IllegalStateException("could not file the track"))
                }
            }
            Result.success(Unit)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.i(TAG, "transfer of $trackUri stopped: ${error.message}")
            Result.failure(error)
        } finally {
            withContext(NonCancellable) { runCatching { channels.close(channel).await() } }
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

/** The phone answered and said no (it does not have the track): asking again changes nothing. */
class TransferRefused(reason: String) : IllegalStateException(reason)

/** When a cut-off transfer is worth asking for again, and after how long. */
object TransferRetry {
    /** Attempts after the first. */
    const val MAX_RETRIES = 3

    fun shouldRetry(error: Throwable?, attempt: Int): Boolean =
        error != null && error !is CancellationException && error !is TransferRefused && error !is IllegalArgumentException && attempt < MAX_RETRIES

    /** 2, 5, 10 seconds: long enough for a radio to come back, short enough to stay in the pass. */
    fun backoffMs(attempt: Int): Long = when (attempt) {
        0 -> 2_000L
        1 -> 5_000L
        else -> 10_000L
    }
}

