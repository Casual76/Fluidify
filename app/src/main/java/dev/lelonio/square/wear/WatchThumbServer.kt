package dev.lelonio.square.wear

import android.graphics.Bitmap
import android.os.Build
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import dev.lelonio.square.SquareApplication
import dev.lelonio.square.io.ReadWatchdog
import dev.pampa.fluidify.wear.protocol.ImageHosts
import dev.pampa.fluidify.wear.protocol.ThumbHeader
import dev.pampa.fluidify.wear.protocol.ThumbRequest
import dev.pampa.fluidify.wear.protocol.ThumbWant
import dev.pampa.fluidify.wear.protocol.WearCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Small covers for the watch's lists ([ThumbRequest]).
 *
 * Through the phone's own image loader, so a cover the phone has shown comes out of its disk
 * cache, and one it has not is fetched on the phone's connection — not through the watch's
 * Bluetooth proxy at 640 px, which is how the watch's Home ended up full of blank tiles. Each
 * answer is one small WebP at the size the watch asked for.
 *
 * The address a watch names is fetched only when it is one of Spotify's own image hosts, over
 * https ([ImageHosts]); the phone is not a way to reach whatever else is on its network.
 */
class WatchThumbServer(private val app: SquareApplication) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channels by lazy { Wearable.getChannelClient(app) }

    fun serve(channel: ChannelClient.Channel) {
        scope.launch {
            val wrapUp = ChannelWrapUp(channels, channel)
            var answered = false
            try {
                wrapUp.start()
                answered = answer(channel)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.i(TAG, "thumbnails for the watch stopped: ${error.message}")
            } finally {
                // Closed only once the watch has read what was written, not on the heels of the
                // last byte: see ChannelWrapUp.
                withContext(NonCancellable) { wrapUp.finish(delivered = answered) }
            }
        }
    }

    /** True when the covers that could be had were written to the watch in full. */
    private suspend fun answer(channel: ChannelClient.Channel): Boolean {
        // A request line that never comes is a watch that went away: the read is cut after a while
        // rather than holding this thread (and the channel) for as long as the link stays up.
        val line = ReadWatchdog(channels.getInputStream(channel).await(), REQUEST_WAIT_MS) { channels.close(channel) }
            .use { WearChannelIo.readLine(it, MAX_LINE) } ?: return false
        val request = WearCodec.decodeOrNull(ThumbRequest.serializer(), line.encodeToByteArray()) ?: return false
        val size = request.sizePx.coerceIn(MIN_PX, MAX_PX)
        channels.getOutputStream(channel).await().use { out ->
            for (want in request.wants.take(ThumbRequest.MAX_WANTS)) {
                val bytes = thumbnail(want, size) ?: continue
                WearChannelIo.writeLine(out, ThumbHeader.serializer(), ThumbHeader(want.key, bytes.size), flush = false)
                out.write(bytes)
                out.flush()
            }
        }
        return true
    }

    private suspend fun thumbnail(want: ThumbWant, size: Int): ByteArray? {
        // The watch's address only when it is Spotify's; otherwise the key, which can only ever
        // stand for an i.scdn.co image, or nothing.
        val url = want.url?.takeIf(ImageHosts::isAllowed) ?: imageUrlFor(want.key) ?: return null
        val result = catchingNonCancel {
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
            // WEBP_LOSSY is API 30; before it the old constant is the same lossy WebP.
            @Suppress("DEPRECATION")
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
            bitmap.compress(format, QUALITY, buffer)
            buffer.toByteArray()
        }
    }

    /** A key that is a Spotify image id stands for its CDN address; anything else needs its URL. */
    private fun imageUrlFor(key: String): String? =
        key.takeIf { it.length == SPOTIFY_IMAGE_ID && it.all(Char::isLetterOrDigit) }?.let { "https://i.scdn.co/image/$it" }

    private companion object {
        const val TAG = "WatchThumbServer"
        const val MIN_PX = 48
        const val MAX_PX = 300
        const val QUALITY = 80
        const val MAX_LINE = 64 * 1024

        /** The watch writes its request the moment it opens the channel. */
        const val REQUEST_WAIT_MS = 15_000L
        const val SPOTIFY_IMAGE_ID = 40
    }
}
