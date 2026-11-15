// SPDX-License-Identifier: GPL-3.0-only
package dev.lelonio.square.data

import java.text.Normalizer
import java.util.Locale

data class HistoryArchiveCursor(val playedAt: Long, val id: String, val revision: Long)
data class HistoryArchiveEntry(val event: ListeningEvent, val playedAt: Long)
data class HistoryArchivePage(val entries: List<HistoryArchiveEntry>, val next: HistoryArchiveCursor?)

/** Literal title/credited-artist substring search. Album, URIs and JSON keys are never searched. */
internal class HistoryArchiveMatcher(query: String) {
    private val needle = fold(query.trim())
    fun matches(track: CatalogTrack): Boolean = needle.isEmpty() ||
        sequenceOf(track.name, track.artist).plus(track.artists.asSequence().map { it.name })
            .any { fold(it).contains(needle) }

    private fun fold(text: String): String {
        val decomposed = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        var latin = false
        decomposed.codePoints().forEach { point ->
            val mark = when (Character.getType(point)) {
                Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(),
                Character.ENCLOSING_MARK.toInt() -> true
                else -> false
            }
            if (!mark) latin = Character.UnicodeScript.of(point) == Character.UnicodeScript.LATIN
            // Preserve vowel/letter marks in other scripts; tolerate Latin accents only.
            if (!mark || !latin) when (point) {
                0x00DF -> out.append("ss")
                0x03C2 -> out.appendCodePoint(0x03C3)
                else -> out.appendCodePoint(point)
            }
        }
        return Normalizer.normalize(out, Normalizer.Form.NFC)
    }
}
