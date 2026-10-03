package dev.lelonio.square.wear

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import dev.lelonio.square.SquareApplication
import dev.pampa.fluidify.wear.protocol.ThumbHeader
import dev.pampa.fluidify.wear.protocol.ThumbRequest
import dev.pampa.fluidify.wear.protocol.ThumbWant
import dev.pampa.fluidify.wear.protocol.WearCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Small covers for the watch's lists ([ThumbRequest]).
 *
 * Through the phone's own image loader, so a cover the phone has shown comes out of its disk
 * cache, and one it has not is fetched on the phone's connection — not through the watch's
 * Bluetooth proxy at 640 px, which is how the watch's Home ended up full of blank tiles. Each
 * answer is one small WebP at the size the watch asked for.
 */
class WatchThumbServer(private val app: SquareApplication) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channels by lazy { Wearable.getChannelClient(app) }

    fun serve(channel: ChannelClient.Channel) {
        scope.launch {
            try {
                answer(channel)
            } catch (error: Exception) {
                Log.i(TAG, "thumbnails for the watch stopped: ${error.message}")
            } finally {
                runCatching { channels.close(channel).await() }
            }
        }
    }

    private suspend fun answer(channel: ChannelClient.Channel) {
        val input = channels.getInputStream(channel).await()
        val line = readLine(input) ?: return
        val request = WearCodec.decodeOrNull(ThumbRequest.serializer(), line.encodeToByteArray()) ?: return
        val size = request.sizePx.coerceIn(MIN_PX, MAX_PX)
        channels.getOutputStream(channel).await().use { out ->
            for (want in request.wants.take(ThumbRequest.MAX_WANTS)) {
                val bytes = thumbnail(want, size) ?: continue
                header(out, ThumbHeader(want.key, bytes.size))
                out.write(bytes)
                out.flush()
            }
        }
    }

    private suspend fun thumbnail(want: ThumbWant, size: Int): ByteArray? {
        val url = want.url ?: imageUrlFor(want.key) ?: return null
        val result = runCatching {
            app.imageLoader.execute(
                ImageRequest.Builder(app)
                    .data(url)
                    .size(size)
                    .allowHardware(false)
                    .build(),
            )
        }.getOrNull() as? SuccessResult ?: return null
        val bitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: return null
        return ByteArrayOutputStream().use { buffer ->
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, QUALITY, buffer)
            buffer.toByteArray()
        }
    }

    /** A key that is a Spotify image id stands for its CDN address; anything else needs its URL. */
    private fun imageUrlFor(key: String): String? =
        key.takeIf { it.length == SPOTIFY_IMAGE_ID && it.all(Char::isLetterOrDigit) }?.let { "https://i.scdn.co/image/$it" }

    private fun header(out: OutputStream, header: ThumbHeader) {
        out.write(WearCodec.encode(ThumbHeader.serializer(), header))
        out.write('\n'.code)
    }

    private fun readLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next < 0) return null
            if (next == '\n'.code) return bytes.toString(Charsets.UTF_8.name())
            bytes.write(next)
            if (bytes.size() > MAX_LINE) return null
        }
    }

    private companion object {
        const val TAG = "WatchThumbServer"
        const val MIN_PX = 48
        const val MAX_PX = 300
        const val QUALITY = 80
        const val MAX_LINE = 64 * 1024
        const val SPOTIFY_IMAGE_ID = 40
    }
}
