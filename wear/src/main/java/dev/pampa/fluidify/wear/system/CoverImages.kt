package dev.pampa.fluidify.wear.system

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.pampa.fluidify.wear.link.ArtStore
import java.io.ByteArrayOutputStream

/**
 * Covers at the size the system's surfaces want them.
 *
 * The phone sends 480 px covers for the player. A tile or a complication
 * shows the same picture at a fraction of that, and both hand it to another
 * process (the tile renderer, the watch face), so it is scaled down first:
 * a few kilobytes cross the boundary instead of a few hundred.
 */
object CoverImages {

    fun bitmap(art: ArtStore, key: String?, sizePx: Int): Bitmap? {
        val file = art.fileFor(key) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= sizePx) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        if (decoded.width == sizePx && decoded.height == sizePx) return decoded
        return Bitmap.createScaledBitmap(decoded, sizePx, sizePx, true).also { if (it !== decoded) decoded.recycle() }
    }

    /** The cover compressed again, for a tile's inline image. */
    fun compressed(art: ArtStore, key: String?, sizePx: Int): ByteArray? {
        val bitmap = bitmap(art, key, sizePx) ?: return null
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, QUALITY, out)
            out.toByteArray()
        }
    }

    private const val QUALITY = 85
}
