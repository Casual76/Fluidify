package dev.lelonio.square.wear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Scale
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import dev.pampa.fluidify.wear.protocol.WearPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import androidx.core.graphics.drawable.toBitmap

/**
 * Sends covers to the watch, once each.
 *
 * A cover goes over as a Data Layer asset under its own key ([WearPaths.art]),
 * sized for the largest watch screen (480 px) and encoded as lossy WebP, which
 * puts an album cover at 30-60 KB: about a second of Bluetooth, paid once per
 * album rather than once per track, because tracks of the same album share the
 * Spotify image id the key is made of. The Data Layer itself drops a put whose
 * content has not changed, so sending a key twice costs nothing on the radio.
 *
 * Only the most recent [KEEP] covers are kept on the Data Layer; the watch keeps
 * its own copies in its art cache, so pruning here does not take a cover off its
 * screen.
 */
class WearArtworkSender(private val context: Context, private val link: WearLink) {

    private val lock = Mutex()
    private val recent = ArrayDeque<String>()
    private var pruned = false

    /**
     * Makes sure the cover [key] is on the Data Layer.
     *
     * @param bytes the encoded cover the player already holds, when it has one.
     * @param url where to fetch it otherwise: https, or a file:// of a download.
     * @param urgent the cover of the song playing now: sent at once, not when the Data Layer gets
     *   round to it, which may be many minutes. The next song's, sent ahead, can wait.
     */
    suspend fun ensure(key: String, bytes: ByteArray?, url: String?, urgent: Boolean = false) = lock.withLock {
        if (key in recent) return@withLock
        val image = withContext(Dispatchers.IO) { render(bytes, url) } ?: return@withLock
        val request = PutDataRequest.create(WearPaths.art(key)).apply {
            putAsset(ASSET_KEY, Asset.createFromBytes(image))
            if (urgent) setUrgent()
        }
        if (!link.put(request)) return@withLock
        recent.addLast(key)
        while (recent.size > KEEP) {
            val old = recent.removeFirst()
            link.delete(WearPaths.art(old))
        }
        if (!pruned) {
            pruned = true
            pruneStale()
        }
    }

    /** Drops covers left on the Data Layer by an earlier run that this run is not tracking. */
    private suspend fun pruneStale() {
        runCatching {
            val client = Wearable.getDataClient(context)
            val prefix = android.net.Uri.Builder().scheme("wear").path(WearPaths.ART_PREFIX).build()
            val items = client.getDataItems(prefix, DataClient.FILTER_PREFIX).await()
            val stale = items.mapNotNull { it.uri.path }
                .filter { path -> path.removePrefix(WearPaths.ART_PREFIX) !in recent }
            items.release()
            if (stale.size > KEEP) stale.drop(KEEP).forEach { link.delete(it) }
        }.onFailure { Log.i(TAG, "art prune failed: ${it.message}") }
    }

    private suspend fun render(bytes: ByteArray?, url: String?): ByteArray? {
        val bitmap = bytes?.let { decode(it) } ?: url?.let { fetch(it) } ?: return null
        val scaled = scale(bitmap)
        val out = ByteArrayOutputStream(64 * 1024)
        @Suppress("DEPRECATION")
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }
        scaled.compress(format, QUALITY, out)
        return out.toByteArray()
    }

    private fun decode(bytes: ByteArray): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= SIZE_PX && bounds.outHeight / (sample * 2) >= SIZE_PX) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    private suspend fun fetch(url: String): Bitmap? = runCatching {
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(SIZE_PX)
            .scale(Scale.FILL)
            .allowHardware(false)
            .build()
        (context.imageLoader.execute(request) as? SuccessResult)?.drawable?.toBitmap()
    }.onFailure { Log.i(TAG, "cover fetch failed: ${it.message}") }.getOrNull()

    private fun scale(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= SIZE_PX) return bitmap
        val factor = SIZE_PX.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * factor).toInt().coerceAtLeast(1),
            (bitmap.height * factor).toInt().coerceAtLeast(1),
            true,
        )
    }

    companion object {
        private const val TAG = "WearArtwork"
        const val ASSET_KEY = "img"
        private const val SIZE_PX = 480
        private const val QUALITY = 80
        private const val KEEP = 12
    }
}
