package dev.lelonio.square.wear

import dev.lelonio.square.R
import dev.lelonio.square.SquareApplication
import dev.lelonio.square.backend.SearchLabels
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.data.SpotifyHome
import dev.lelonio.square.nativecore.NativeBridge
import dev.pampa.fluidify.wear.protocol.ContextPage
import dev.pampa.fluidify.wear.protocol.LibraryItem
import dev.pampa.fluidify.wear.protocol.LibraryKind
import dev.pampa.fluidify.wear.protocol.LibraryPage
import dev.pampa.fluidify.wear.protocol.LibrarySection
import dev.pampa.fluidify.wear.protocol.LibraryShelf
import dev.pampa.fluidify.wear.protocol.artKeyOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The phone's library, read the way the phone's own screens read it, without a screen.
 *
 * Home through Spotify's gateway (the same feed the home screen shows), the
 * playlists and Liked Songs through the backend, albums and artists through the
 * listener's own Web API app when they have set one up (and an honest "not
 * available" when they have not), downloads from the download index, which
 * needs no network at all.
 */
class WearLibrarySource(private val app: SquareApplication) {

    suspend fun home(): LibraryPage = withContext(Dispatchers.IO) {
        val keys = app.pathfinderKeys
        keys.refresh()
        val shelves = runCatching {
            SpotifyHome.parse(
                NativeBridge.homeFeed(
                    java.util.TimeZone.getDefault().id,
                    java.util.Locale.getDefault().language,
                    keys.home,
                    keys.appVersion,
                ),
            )
        }.getOrDefault(emptyList())
        val pinned = runCatching { app.spotifyBackend.playlists() }.getOrDefault(emptyList())
        val top = pinned.take(HOME_LIBRARY_ROWS).map { it.toItem() }
        LibraryPage(
            shelves = buildList {
                if (top.isNotEmpty()) add(LibraryShelf(title = "", items = top))
                shelves.forEach { shelf ->
                    add(LibraryShelf(title = shelf.title, items = shelf.items.take(SHELF_ITEMS).map { it.toItem() }))
                }
            },
        )
    }

    suspend fun section(section: LibrarySection): LibraryPage = withContext(Dispatchers.IO) {
        when (section) {
            LibrarySection.PLAYLISTS -> LibraryPage(
                listOf(LibraryShelf("", app.spotifyBackend.playlists().map { it.toItem() })),
            )
            LibrarySection.ALBUMS -> if (!app.webApi.isReady) {
                LibraryPage(emptyList(), unavailableReason = app.getString(R.string.connect_app_in_settings))
            } else {
                val albums = app.api.savedAlbums(limit = 50).items.map { saved ->
                    val album = saved.album
                    val art = album.images.firstOrNull()?.url
                    LibraryItem(
                        uri = album.uri.orEmpty(),
                        title = album.name,
                        kind = LibraryKind.ALBUM,
                        artKey = artKeyOf(art),
                        artUrl = art,
                    )
                }.filter { it.uri.isNotEmpty() }
                LibraryPage(listOf(LibraryShelf("", albums)))
            }
            LibrarySection.ARTISTS -> if (!app.webApi.isReady) {
                LibraryPage(emptyList(), unavailableReason = app.getString(R.string.connect_app_in_settings))
            } else {
                val artists = app.api.followedArtists(limit = 50).artists.items.map { artist ->
                    val art = artist.images.firstOrNull()?.url
                    LibraryItem(
                        uri = artist.uri.orEmpty(),
                        title = artist.name,
                        kind = LibraryKind.ARTIST,
                        artKey = artKeyOf(art),
                        artUrl = art,
                    )
                }.filter { it.uri.isNotEmpty() }
                LibraryPage(listOf(LibraryShelf("", artists)))
            }
            LibrarySection.DOWNLOADS -> {
                val store = app.downloads
                val items = store.owners.value.keys.mapNotNull { owner ->
                    val label = store.labelOf(owner) ?: return@mapNotNull null
                    LibraryItem(
                        uri = owner,
                        title = label.name,
                        kind = if (owner.endsWith(":collection")) LibraryKind.LIKED else LibraryKind.PLAYLIST,
                        artKey = artKeyOf(label.artworkUrl),
                        artUrl = label.artworkUrl?.takeIf { it.startsWith("https://") },
                    )
                }
                LibraryPage(listOf(LibraryShelf("", items)))
            }
            LibrarySection.RECENT -> LibraryPage(
                listOf(LibraryShelf("", app.recentStore.tracks.value.map { it.toItem() })),
            )
        }
    }

    suspend fun context(uri: String, offset: Int, limit: Int): ContextPage = withContext(Dispatchers.IO) {
        val tracks = app.spotifyBackend.tracksOf(uri)
        val playlist = runCatching { app.spotifyBackend.playlists().firstOrNull { it.uri == uri } }.getOrNull()
        val label = app.downloads.labelOf(uri)
        val art = playlist?.artworkUrl ?: label?.artworkUrl ?: tracks.firstOrNull()?.artworkUrl
        ContextPage(
            uri = uri,
            title = playlist?.name ?: label?.name ?: tracks.firstOrNull()?.album.orEmpty(),
            artKey = artKeyOf(art),
            artUrl = art?.takeIf { it.startsWith("https://") },
            tracks = tracks.drop(offset).take(limit).map { it.toItem() },
            total = tracks.size,
        )
    }

    suspend fun search(query: String): LibraryPage = withContext(Dispatchers.IO) {
        val labels = SearchLabels(
            artist = app.getString(R.string.artist),
            album = app.getString(R.string.album),
            playlist = app.getString(R.string.playlist),
        )
        val results = app.spotifyBackend.search(query, labels)
        LibraryPage(
            shelves = buildList {
                if (results.tracks.isNotEmpty()) add(LibraryShelf("", results.tracks.take(20).map { it.toItem() }))
                if (results.artists.isNotEmpty()) add(LibraryShelf(labels.artist, results.artists.take(8).map { it.toItem(LibraryKind.ARTIST) }))
                if (results.albums.isNotEmpty()) add(LibraryShelf(labels.album, results.albums.take(8).map { it.toItem(LibraryKind.ALBUM) }))
                if (results.playlists.isNotEmpty()) add(LibraryShelf(labels.playlist, results.playlists.take(8).map { it.toItem(LibraryKind.PLAYLIST) }))
            },
        )
    }

    private fun CatalogPlaylist.toItem() = LibraryItem(
        uri = uri,
        title = name,
        kind = if (uri.endsWith(":collection")) LibraryKind.LIKED else if (uri.startsWith("spotify:album:")) LibraryKind.ALBUM else if (uri.startsWith("spotify:artist:")) LibraryKind.ARTIST else LibraryKind.PLAYLIST,
        artKey = artKeyOf(artworkUrl),
        artUrl = artworkUrl?.takeIf { it.startsWith("https://") },
    )

    private fun CatalogTrack.toItem() = LibraryItem(
        uri = uri,
        title = name,
        subtitle = artist,
        kind = LibraryKind.TRACK,
        artKey = artKeyOf(artworkUrl),
        artUrl = artworkUrl?.takeIf { it.startsWith("https://") },
    )

    private fun dev.lelonio.square.data.SearchItem.toItem(kind: LibraryKind) = LibraryItem(
        uri = uri,
        title = title,
        subtitle = subtitle,
        kind = kind,
        artKey = artKeyOf(artworkUrl),
        artUrl = artworkUrl?.takeIf { it.startsWith("https://") },
    )

    private companion object {
        const val HOME_LIBRARY_ROWS = 6
        const val SHELF_ITEMS = 10
    }
}
