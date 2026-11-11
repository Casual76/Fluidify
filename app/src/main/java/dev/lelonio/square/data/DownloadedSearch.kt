package dev.lelonio.square.data

/** Search only completed audio with known metadata, retaining snapshot iteration order. */
fun findDownloadedTracks(
    query: String,
    completedUris: Set<String>,
    trackOf: (String) -> CatalogTrack?,
): List<CatalogTrack> {
    // The global search host owns the history shown for an empty query.
    if (query.isBlank()) return emptyList()
    return completedUris.asSequence()
        .mapNotNull(trackOf)
        .filter { it.uri in completedUris }
        .distinctBy { it.uri }
        .filter { matchesTrackText(it, query) }
        .toList()
}
