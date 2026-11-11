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
): List<CatalogTrack> = orderByAddedAt(tracks, oldestFirst) { it }

/** Carries the source position through sorting, even for equal duplicate values. */
internal fun orderPlaylistOccurrencesByAddedAt(
    tracks: List<IndexedValue<CatalogTrack>>,
    oldestFirst: Boolean,
): List<IndexedValue<CatalogTrack>> = orderByAddedAt(tracks, oldestFirst) { it.value }

private fun <T> orderByAddedAt(items: List<T>, oldestFirst: Boolean, track: (T) -> CatalogTrack): List<T> =
    items.map { it to track(it).playlistAddedInstant() }
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
