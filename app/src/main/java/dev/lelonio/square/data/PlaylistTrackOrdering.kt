package dev.lelonio.square.data

import java.time.Instant
import java.time.format.DateTimeParseException

/** Dates belong to playlist occurrences. Missing or malformed dates remain unknown. */
private fun CatalogTrack.playlistAddedInstant(): Instant? = addedAt?.let {
    try { Instant.parse(it) } catch (_: DateTimeParseException) { null }
}

fun hasKnownPlaylistAddedAt(tracks: List<CatalogTrack>): Boolean =
    tracks.any { it.playlistAddedInstant() != null }

/** Stable in either direction: ties and unknowns retain occurrence order; unknowns stay last. */
fun orderPlaylistTracksByAddedAt(
    tracks: List<CatalogTrack>,
    oldestFirst: Boolean,
): List<CatalogTrack> = tracks.map { it to it.playlistAddedInstant() }
    .sortedWith { a, b ->
        val left = a.second
        val right = b.second
        when {
            left == null && right == null -> 0
            left == null -> 1
            right == null -> -1
            oldestFirst -> left.compareTo(right)
            else -> right.compareTo(left)
        }
    }.map { it.first }
