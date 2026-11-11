package dev.lelonio.square.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import dev.lelonio.square.data.SpotifyNetworkPolicy
import dev.lelonio.square.io.HttpFetch
import java.io.EOFException

/** A source's lease outlives its retries, ranges and DRM requests. File/content sources stay local. */
@UnstableApi
internal object SpotifyMediaDataSource {
    fun factory(context: Context, permit: SpotifyNetworkPolicy.Permit? =
        runCatching { SpotifyNetworkPolicy.capture() }.getOrNull(), ownerCurrent: () -> Boolean = { true }): DataSource.Factory =
        DefaultDataSource.Factory(context, DataSource.Factory { Remote(permit, ownerCurrent) })

    internal class Remote(private val permit: SpotifyNetworkPolicy.Permit?, private val ownerCurrent: () -> Boolean) : BaseDataSource(true) {
        private var body: HttpFetch.Body? = null
        private var remaining = C.LENGTH_UNSET.toLong()
        override fun open(dataSpec: DataSpec): Long {
            if (!ownerCurrent()) throw java.io.IOException("Media owner retired")
            transferInitializing(dataSpec)
            val lease = permit ?: throw SpotifyNetworkPolicy.Offline()
            lease.check()
            val headers = dataSpec.httpRequestHeaders.toMutableMap()
            if (dataSpec.position != 0L || dataSpec.length != C.LENGTH_UNSET.toLong()) {
                val end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) "" else (dataSpec.position + dataSpec.length - 1).toString()
                headers["Range"] = "bytes=${dataSpec.position}-$end"
            }
            val method = when (dataSpec.httpMethod) { DataSpec.HTTP_METHOD_POST -> "POST"; DataSpec.HTTP_METHOD_HEAD -> "HEAD"; else -> "GET" }
            val opened = HttpFetch.open(dataSpec.uri.toString(), lease, headers, method, dataSpec.httpBody, ownerCurrent)
            try {
                if (opened.connection.responseCode == 200 && dataSpec.position > 0) {
                    var skip = dataSpec.position
                    while (skip > 0) {
                        lease.check()
                        val count = opened.input.skip(skip)
                        if (count == 0L) { if (opened.input.read() < 0) throw EOFException(); skip-- } else skip -= count
                    }
                }
                lease.check()
                body = opened
                remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else {
                    opened.connection.getHeaderFieldLong("Content-Length", -1L).let {
                        if (it < 0) C.LENGTH_UNSET.toLong() else (it - if (opened.connection.responseCode == 200) dataSpec.position else 0L).coerceAtLeast(0)
                    }
                }
                transferStarted(dataSpec)
                return remaining
            } catch (failure: Throwable) { opened.close(); throw failure }
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (!ownerCurrent()) throw java.io.IOException("Media owner retired")
            permit?.check() ?: throw SpotifyNetworkPolicy.Offline()
            if (remaining == 0L) return C.RESULT_END_OF_INPUT
            val count = body!!.input.read(buffer, offset, if (remaining < 0) length else minOf(length.toLong(), remaining).toInt())
            if (count < 0) return C.RESULT_END_OF_INPUT
            if (remaining >= 0) remaining -= count
            bytesTransferred(count)
            return count
        }
        override fun getUri(): Uri? = body?.connection?.url?.toString()?.let(Uri::parse)
        override fun getResponseHeaders(): Map<String, List<String>> = body?.connection?.headerFields
            ?.filterKeys { it != null }?.mapKeys { it.key!! }.orEmpty()
        override fun close() { val old = body; body = null; if (old != null) try { old.close() } finally { transferEnded() } }
    }
}
