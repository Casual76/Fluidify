package dev.pampa.fluidify.wear.update

import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Fetches an update's APK over the watch's own network, straight from the release.
 *
 * Plain HTTPS through whatever network the process is bound to, which while this runs is the
 * Wi-Fi asked for by [dev.pampa.fluidify.wear.standalone.NetworkBroker]. Nothing is trusted here:
 * the size is checked against the offer, and the caller checks the checksum and the signature
 * before anything is installed, exactly as for a file that came from the phone.
 *
 * @param onProgress the fraction received, 0..1, called often; the caller throttles it.
 * @return whether [target] now holds exactly [expectedBytes] bytes.
 */
internal suspend fun downloadUpdate(
    url: String,
    target: File,
    expectedBytes: Long,
    onProgress: suspend (Float) -> Unit,
): Boolean {
    if (!url.startsWith("https://")) return false
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
        // GitHub answers a release asset with a redirect to its storage, https to https.
        instanceFollowRedirects = true
    }
    try {
        if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}")
        val startedAt = SystemClock.elapsedRealtime()
        var received = 0L
        target.parentFile?.mkdirs()
        connection.inputStream.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    received += read
                    if (received > expectedBytes) throw IOException("larger than offered")
                    if (SystemClock.elapsedRealtime() - startedAt > DEADLINE_MS) throw IOException("too slow")
                    output.write(buffer, 0, read)
                    onProgress((received.toFloat() / expectedBytes.coerceAtLeast(1)).coerceIn(0f, 1f))
                }
            }
        }
        return received == expectedBytes
    } finally {
        connection.disconnect()
    }
}

private const val CONNECT_TIMEOUT_MS = 15_000
private const val READ_TIMEOUT_MS = 30_000
private const val BUFFER_BYTES = 64 * 1024

/** Past this, a download is not "faster than Bluetooth" any more, and Bluetooth gets its turn. */
private const val DEADLINE_MS = 10 * 60_000L
