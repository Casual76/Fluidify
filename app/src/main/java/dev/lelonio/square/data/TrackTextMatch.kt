package dev.lelonio.square.data

import java.text.Normalizer
import java.util.Locale

/** Match loaded title, artist or album metadata; no lyrics or catalog lookup. */
fun matchesTrackText(track: CatalogTrack, query: String): Boolean {
    val needle = foldTrackText(query.trim())
    return needle.isEmpty() || sequenceOf(track.name, track.artist, track.album)
        .any { foldTrackText(it).contains(needle) }
}

private fun foldTrackText(text: String): String {
    val decomposed = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
    val folded = StringBuilder(decomposed.length)
    var latinBase = false
    decomposed.codePoints().forEach { codePoint ->
        val mark = when (Character.getType(codePoint)) {
            Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt() -> true
            else -> false
        }
        // Marks in other scripts can distinguish letters or vowels. Keep those
        // intact rather than turning accent tolerance into destructive transliteration.
        if (!mark) latinBase = Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN
        if (!mark || !latinBase) folded.appendCodePoint(codePoint)
    }
    return Normalizer.normalize(folded, Normalizer.Form.NFC)
}
