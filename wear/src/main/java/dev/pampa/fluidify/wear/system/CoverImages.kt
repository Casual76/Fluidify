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

    /**
     * The cover compressed again, for a tile's inline image. Kept for the last cover asked for:
     * the tile asks on every layout, and decoding a cover to compress it again each time was the
     * most expensive part of a press.
     */
    fun compressed(art: ArtStore, key: String?, sizePx: Int): ByteArray? {
        if (key == null) return null
        return lastCompressed.getOrCompute(key to sizePx) {
            val bitmap = bitmap(art, key, sizePx) ?: return@getOrCompute null
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, QUALITY, out)
                out.toByteArray()
            }
        }
    }

    /**
     * The cover as a backdrop: small, blurred and darkened, for a tile to stretch behind its
     * buttons. Blurred here, once per cover, because a tile renderer cannot blur anything; at
     * [BACKDROP_PX] a few box-blur passes cost nothing and the renderer's upscaling finishes the job.
     * Kept for the last cover asked for, so "once per cover" is true.
     */
    fun backdrop(art: ArtStore, key: String?): ByteArray? {
        if (key == null) return null
        return lastBackdrop.getOrCompute(key) { blurred(art, key) }
    }

    private fun blurred(art: ArtStore, key: String): ByteArray? {
        val small = bitmap(art, key, BACKDROP_PX) ?: return null
        val pixels = IntArray(BACKDROP_PX * BACKDROP_PX)
        small.getPixels(pixels, 0, BACKDROP_PX, 0, 0, BACKDROP_PX, BACKDROP_PX)
        small.recycle()
        repeat(BLUR_PASSES) { boxBlur(pixels, BACKDROP_PX, BLUR_RADIUS) }
        for (i in pixels.indices) pixels[i] = darken(pixels[i])
        val blurred = Bitmap.createBitmap(pixels, BACKDROP_PX, BACKDROP_PX, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { out ->
            blurred.compress(Bitmap.CompressFormat.WEBP_LOSSY, BACKDROP_QUALITY, out)
            blurred.recycle()
            out.toByteArray()
        }
    }

    /** One horizontal and one vertical running-average pass over a square of [size]. */
    internal fun boxBlur(pixels: IntArray, size: Int, radius: Int) {
        val line = IntArray(size)
        fun pass(get: (Int, Int) -> Int, set: (Int, Int, Int) -> Unit) {
            for (row in 0 until size) {
                var r = 0; var g = 0; var b = 0
                for (k in -radius..radius) {
                    val c = get(row, k.coerceIn(0, size - 1))
                    r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
                }
                val window = radius * 2 + 1
                for (col in 0 until size) {
                    line[col] = (0xFF shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
                    val out = get(row, (col - radius).coerceIn(0, size - 1))
                    val inn = get(row, (col + radius + 1).coerceIn(0, size - 1))
                    r += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                    g += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                    b += (inn and 0xFF) - (out and 0xFF)
                }
                for (col in 0 until size) set(row, col, line[col])
            }
        }
        pass({ row, col -> pixels[row * size + col] }) { row, col, c -> pixels[row * size + col] = c }
        pass({ col, row -> pixels[row * size + col] }) { col, row, c -> pixels[row * size + col] = c }
    }

    /** Toward black, enough for white text and translucent buttons to sit on any cover. */
    private fun darken(color: Int): Int {
        val r = ((color shr 16) and 0xFF) * DARKEN / 100
        val g = ((color shr 8) and 0xFF) * DARKEN / 100
        val b = (color and 0xFF) * DARKEN / 100
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private val lastCompressed = LastValue<Pair<String, Int>, ByteArray>()
    private val lastBackdrop = LastValue<String, ByteArray>()

    private const val QUALITY = 85
    private const val BACKDROP_PX = 48
    private const val BACKDROP_QUALITY = 80
    private const val BLUR_PASSES = 3
    private const val BLUR_RADIUS = 4

    /** Percent of each channel kept. */
    private const val DARKEN = 46
}
