package dev.lelonio.square.data

/*
 * The Web API's models into the app's own.
 *
 * Kept in the phone app when Catalog.kt moved to :core: the Web API client
 * (SpotifyApi and its DTOs) is the phone's, and the watch never talks to it.
 * Same package as before, so `import dev.lelonio.square.data.toCatalogTrack`
 * still finds it.
 */

/** Bridges a Web API track into the model the UI and the queue already use. */
fun TrackDto.toCatalogTrack(addedAt: String? = null): CatalogTrack = CatalogTrack(
    uri = uri,
    name = name,
    artist = artists.joinToString(", ") { it.name },
    artistUri = artists.firstOrNull()?.uri,
    artists = artists.map { CatalogArtist(it.name, it.uri) },
    album = album?.name.orEmpty(),
    durationMs = durationMs,
    explicit = explicit,
    artworkUrl = album?.images?.firstOrNull()?.url,
    addedAt = addedAt,
)
