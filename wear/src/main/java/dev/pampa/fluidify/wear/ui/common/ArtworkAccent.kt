package dev.pampa.fluidify.wear.ui.common

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import dev.pampa.fluidify.wear.link.ArtStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The colour of the cover for [artKey], to tint the player with — the same choice the phone makes
 * for its own player (the vivid swatch first), so the watch and the phone wear the same colour for
 * the same song.
 *
 * Null until the cover has been read. When the song changes and its cover has not arrived yet the
 * last colour stays rather than flashing back to the brand. Worked out off the main thread on a
 * 64 px decode, and remembered per cover.
 */
@Composable
fun rememberArtworkAccent(artKey: String?, art: ArtStore): Color? {
    val revision by art.revision.collectAsState()
    val accent by produceState(initialValue = artKey?.let(AccentCache::get), artKey, revision) {
        val key = artKey ?: return@produceState
        AccentCache.get(key)?.let {
            value = it
            return@produceState
        }
        val color = withContext(Dispatchers.Default) { art.fileFor(key)?.let(::accentOf) } ?: return@produceState
        AccentCache.put(key, color)
        value = color
    }
    return accent
}

/** The accent of a cover file, or null if it cannot be read. */
internal fun accentOf(file: File): Color? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= DECODE_PX) sample *= 2
    val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return@runCatching null
    val palette = Palette.from(bitmap).clearFilters().generate()
    bitmap.recycle()
    val rgb = palette.vibrantSwatch?.rgb
        ?: palette.lightVibrantSwatch?.rgb
        ?: palette.mutedSwatch?.rgb
        ?: palette.dominantSwatch?.rgb
        ?: return@runCatching null
    Color(rgb)
}.getOrNull()

private object AccentCache {
    private val colors = object : LinkedHashMap<String, Color>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Color>?) = size > 48
    }

    @Synchronized fun get(key: String): Color? = colors[key]

    @Synchronized fun put(key: String, color: Color) {
        colors[key] = color
    }
}

private const val DECODE_PX = 64
