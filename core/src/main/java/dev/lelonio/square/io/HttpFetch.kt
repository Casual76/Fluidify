package dev.lelonio.square.io

import dev.lelonio.square.data.SpotifyNetworkPolicy
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Immutable media GETs. Disk reads remain available; every remote hop uses the original lease. */
object HttpFetch {
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val MAX_REDIRECTS = 5
    // Compatibility for existing three-argument transfer hooks inside DownloadExtras.
    // This carries an immutable permit; it never decides network policy or renews a lease.
    private val operation = ThreadLocal<SpotifyNetworkPolicy.Permit?>()
    fun <T> withPermit(permit: SpotifyNetworkPolicy.Permit, block: () -> T): T {
        permit.check()
        val previous = operation.get()
        operation.set(permit)
        try { return block().also { permit.check() } }
        finally { operation.set(previous) }
    }
    private fun permission() = operation.get()?.also { it.check() } ?: SpotifyNetworkPolicy.capture()

    fun bytes(url: String, maxBytes: Int): ByteArray? = bytes(url, maxBytes, null)
    fun bytes(url: String, maxBytes: Int, permit: SpotifyNetworkPolicy.Permit?, ownerCurrent: () -> Boolean = { true }): ByteArray? {
        if (!ownerCurrent()) throw IOException("Media owner retired")
        if (url.startsWith("file:")) {
            val file = File(java.net.URI(url))
            return if (file.length() > maxBytes) null else file.readBytes()
        }
        val lease = permit ?: permission()
        return lease.dispatch {
            open(url, lease, ownerCurrent = ownerCurrent).use { body ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = body.input.read(buffer)
                    if (count < 0) break
                    if (out.size() + count > maxBytes) return@use null
                    out.write(buffer, 0, count)
                }
                if (!ownerCurrent()) throw IOException("Media owner retired")
                out.toByteArray()
            }
        }
    }
    fun toFile(url: String, target: File, maxBytes: Long) {
        if (url.startsWith("file:")) {
            val source = File(java.net.URI(url))
            if (source.length() > maxBytes) throw IOException("larger than $maxBytes bytes")
            source.copyTo(target, overwrite = true)
            return
        }
        val lease = permission()
        lease.dispatch {
            open(url, lease).use { body ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        val count = body.input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > maxBytes) throw IOException("larger than $maxBytes bytes")
                        out.write(buffer, 0, count)
                    }
                }
            }
        }
    }

    class Body internal constructor(val connection: HttpURLConnection, val input: InputStream) : AutoCloseable {
        override fun close() { try { input.close() } finally { connection.disconnect() } }
    }

    /** Also used by Media3 for segments/ranges and DRM. Automatic redirect/retry loops are absent. */
    fun open(url: String, permit: SpotifyNetworkPolicy.Permit, headers: Map<String, String> = emptyMap(),
        method: String = "GET", requestBody: ByteArray? = null, ownerCurrent: () -> Boolean = { true }): Body {
        var location = URL(url)
        var verb = method
        var payload = requestBody
        var requestHeaders = headers
        repeat(MAX_REDIRECTS + 1) { hop ->
            if (!ownerCurrent()) throw IOException("Media owner retired")
            permit.check()
            if (location.protocol !in setOf("http", "https")) throw IOException("Unsupported media redirect")
            val connection = (location.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                requestMethod = verb
                requestHeaders.forEach { (key, value) -> setRequestProperty(key, value) }
            }
            try {
                // Admit this request before connecting. An admitted request may drain after revoke.
                permit.admit { Unit }
                payload?.let { bytes ->
                    connection.doOutput = true
                    connection.outputStream.use { it.write(bytes) }
                }
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    val next = connection.getHeaderField("Location") ?: throw IOException("Redirect without Location")
                    val redirected = URL(location, next)
                    if (location.protocol == "https" && redirected.protocol != "https") throw IOException("Insecure media redirect")
                    if (hop == MAX_REDIRECTS) throw IOException("Too many media redirects")
                    if (location.host != redirected.host || location.port != redirected.port || location.protocol != redirected.protocol) {
                        requestHeaders = requestHeaders.filterKeys { !it.equals("Authorization", true) }
                    }
                    if (status == 303 || ((status == 301 || status == 302) && verb == "POST")) { verb = "GET"; payload = null }
                    connection.disconnect()
                    location = redirected
                    permit.check() // Same revision for the next hop, including ON -> OFF.
                } else {
                    if (status !in 200..299) throw IOException("HTTP $status")
                    permit.check()
                    if (!ownerCurrent()) throw IOException("Media owner retired")
                    return Body(connection, connection.inputStream)
                }
            } catch (failure: Throwable) { connection.disconnect(); throw failure }
        }
        throw IOException("Too many media redirects")
    }
}
