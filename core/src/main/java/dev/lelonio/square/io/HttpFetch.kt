package dev.lelonio.square.io

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Plain GETs for immutable files on a CDN — covers, Canvas clips, lyrics — with the two limits
 * `URL.openStream()` does not have.
 *
 * A time limit, because a connection that stops answering held its thread for ever (the cover
 * thread, a download's extras) and nothing could interrupt it. A size limit, because the bytes
 * were read in full before anyone checked how many there were.
 */
object HttpFetch {
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000

    /** The body, or `null` when it is larger than [maxBytes]. Throws on a network failure. */
    fun bytes(url: String, maxBytes: Int): ByteArray? = open(url).useBody { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (out.size() + read > maxBytes) return@useBody null
            out.write(buffer, 0, read)
        }
        out.toByteArray()
    }

    /** Copies the body into [target]. Throws on a network failure or a body over [maxBytes]. */
    fun toFile(url: String, target: File, maxBytes: Long) = open(url).useBody { input ->
        target.outputStream().use { out ->
            val buffer = ByteArray(32 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) throw IOException("larger than $maxBytes bytes")
                out.write(buffer, 0, read)
            }
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
        }

    private inline fun <T> HttpURLConnection.useBody(read: (java.io.InputStream) -> T): T {
        try {
            if (responseCode !in 200..299) throw IOException("HTTP $responseCode")
            return inputStream.use(read)
        } finally {
            disconnect()
        }
    }
}
