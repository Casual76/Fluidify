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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val contextsLock = Mutex()
    private val contexts = LinkedHashMap<String, Pair<Long, List<CatalogTrack>>>()

    /**
     * The watch's Home: the listener's pins on top, then what they played lately, then what they
     * opened lately ([WatchHome.top]); then Spotify's playlists made for them ([WatchHome.madeForYou]);
     * then Spotify's own home shelves, without the rows already shown above.
     *
     * The engine is woken first: the gateway and the playlist list both go through it, and a
     * listener service that woke the process for this question has not started it — which is how
     * the watch's Home used to come back half empty. If it is not up in time, the last Home this
     * phone answered stands in, with today's pins.
     */
    suspend fun home(): LibraryPage = withContext(Dispatchers.IO) {
        if (!engineReady()) cachedHome()?.let { return@withContext it }
        val keys = app.pathfinderKeys
        runCatching { keys.refresh() }
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
        val playlists = runCatching { app.spotifyBackend.playlists() }.getOrDefault(emptyList())
        if (shelves.isEmpty() && playlists.isEmpty()) cachedHome()?.let { return@withContext it }

        val pinned = app.pinnedPlaylists.pinned.value
        val top = WatchHome.top(pinned, app.recentContexts.contexts.value, app.playlistOrder.order.value, playlists, HOME_TOP_ROWS)
        val fromHome = shelves.flatMap { shelf -> shelf.items.map { HomeEntry(it.uri, it.name, it.artworkUrl) } }
        val made = WatchHome.madeForYou(fromHome, playlists, rememberedMadeForYou(fromHome + top, playlists), MADE_FOR_YOU_ROWS)
        val shown = (top.map { it.uri } + made.map { it.uri }).toHashSet()

        val withCovers = withCovers(top + made)
        val page = LibraryPage(
            shelves = buildList {
                if (top.isNotEmpty()) add(LibraryShelf(title = "", items = top.map { withCovers.getValue(it.uri).toItem() }))
                if (made.isNotEmpty()) {
                    add(LibraryShelf(app.getString(R.string.watch_made_for_you), made.map { withCovers.getValue(it.uri).toItem() }))
                }
                shelves.forEach { shelf ->
                    val items = shelf.items.filter { it.uri !in shown }.take(SHELF_ITEMS).map { it.toItem() }
                    if (items.isNotEmpty()) add(LibraryShelf(title = shelf.title, items = items))
                }
            },
        )
        saveHome(page)
        page
    }

    /** Starts the phone's engine if it is not running, and waits a little for it to connect. */
    private suspend fun engineReady(): Boolean = app.wearBridge.engineReady(ENGINE_WAIT_MS)

    /**
     * The made-for-you playlists found before, and once a day a search for the two that matter
     * most when neither Spotify's home nor the library has them: Discover Weekly and Release Radar
     * are the listener's own, so their ids never change, and remembering them costs nothing.
     */
    private suspend fun rememberedMadeForYou(seen: List<HomeEntry>, playlists: List<CatalogPlaylist>): List<HomeEntry> {
        val prefs = app.getSharedPreferences(MADE_FOR_YOU_PREFS, android.content.Context.MODE_PRIVATE)
        val known = prefs.getString(KEY_MADE, null)?.let { saved ->
            runCatching { dev.pampa.fluidify.wear.protocol.WearCodec.json.decodeFromString(MadeList.serializer(), saved).items }.getOrNull()
        }.orEmpty().map { HomeEntry(it.uri, it.name, it.artworkUrl) }
        val present = (seen.map { it.uri } + playlists.map { it.uri } + known.map { it.uri }).filter(WatchHome::isMadeForYou)
        val hasWeekly = present.any { it.removePrefix("spotify:playlist:").startsWith("37i9dQZEVX") }
        val lastSearch = prefs.getLong(KEY_SEARCHED_AT, 0L)
        if (hasWeekly || System.currentTimeMillis() - lastSearch < SEARCH_EVERY_MS) return known
        prefs.edit().putLong(KEY_SEARCHED_AT, System.currentTimeMillis()).apply()
        val labels = SearchLabels(app.getString(R.string.artist), app.getString(R.string.album), app.getString(R.string.playlist))
        val found = SEARCHES.flatMap { query ->
            runCatching { app.spotifyBackend.search(query, labels).playlists }.getOrDefault(emptyList())
                .filter { WatchHome.isMadeForYou(it.uri) }
                .take(1)
                .map { HomeEntry(it.uri, it.title, it.artworkUrl) }
        }
        val merged = (found + known).distinctBy { it.uri }.take(MADE_FOR_YOU_ROWS)
        prefs.edit().putString(KEY_MADE, dev.pampa.fluidify.wear.protocol.WearCodec.json.encodeToString(MadeList.serializer(), MadeList(merged.map { MadeItem(it.uri, it.name, it.artworkUrl) }))).apply()
        return merged
    }

    /**
     * Covers for the rows that came without one. Spotify's own playlists — Discover Weekly, the
     * mixes — keep their picture on the playlist, not in the account's list of it, so those rows
     * arrived blank. Looked up once each and remembered for as long as the process lives.
     */
    private suspend fun withCovers(entries: List<HomeEntry>): Map<String, HomeEntry> {
        var lookups = 0
        return entries.associate { entry ->
            val art = entry.artworkUrl ?: coverCache[entry.uri] ?: if (
                lookups < MAX_COVER_LOOKUPS && entry.uri.startsWith("spotify:playlist:") && entry.uri !in noCover
            ) {
                lookups++
                // A playlist with no cover is remembered as such too: asked again on every Home, the
                // same twelve lookups ran each time and pushed the answer past the watch's patience.
                runCatching { dev.lelonio.square.data.Catalog.playlistCover(entry.uri) }.getOrNull()
                    ?.also { coverCache[entry.uri] = it } ?: null.also { noCover += entry.uri }
            } else {
                null
            }
            entry.uri to entry.copy(artworkUrl = art)
        }
    }

    private val coverCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val noCover: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val homeFile get() = homeFile(app)

    private fun saveHome(page: LibraryPage) {
        runCatching { homeFile.writeBytes(dev.pampa.fluidify.wear.protocol.WearCodec.encode(LibraryPage.serializer(), page)) }
    }

    /** The last Home answered, with the pins as they are now. */
    fun cachedHome(): LibraryPage? {
        val page = runCatching { dev.pampa.fluidify.wear.protocol.WearCodec.decodeOrNull(LibraryPage.serializer(), homeFile.readBytes()) }.getOrNull()
            ?: return null
        val pinned = app.pinnedPlaylists.pinned.value.toSet()
        return page.copy(
            shelves = page.shelves.mapIndexed { index, shelf ->
                if (index != 0) shelf else shelf.copy(items = shelf.items.map { it.copy(pinned = it.uri in pinned) })
            },
        )
    }

    private fun HomeEntry.toItem() = LibraryItem(
        uri = uri,
        title = name,
        kind = when {
            uri.endsWith(":collection") -> LibraryKind.LIKED
            uri.startsWith("spotify:album:") -> LibraryKind.ALBUM
            uri.startsWith("spotify:artist:") -> LibraryKind.ARTIST
            else -> LibraryKind.PLAYLIST
        },
        artKey = artKeyOf(artworkUrl),
        artUrl = artworkUrl?.takeIf { it.startsWith("https://") },
        pinned = pinned,
    )

    @kotlinx.serialization.Serializable
    private data class MadeItem(val uri: String, val name: String, val artworkUrl: String? = null)

    @kotlinx.serialization.Serializable
    private data class MadeList(val items: List<MadeItem>)

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
        val tracks = contextsLock.withLock {
            val now = android.os.SystemClock.elapsedRealtime()
            contexts.entries.removeAll { now - it.value.first > 120_000L }
            if (offset == 0) contexts.remove(uri)
            contexts[uri]?.second ?: app.spotifyBackend.tracksOf(uri).also {
                if (contexts.size >= 8) contexts.remove(contexts.keys.first())
                contexts[uri] = now to it
            }
        }
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
        editable = editable,
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

    companion object {
        private const val HOME_TOP_ROWS = 10
        private const val MADE_FOR_YOU_ROWS = 8
        private const val SHELF_ITEMS = 10
        private const val MAX_COVER_LOOKUPS = 12
        private const val ENGINE_WAIT_MS = 6_000L
        fun homeFile(context: android.content.Context) = java.io.File(context.filesDir, "wear-home.json")

        /**
         * What the watch's Home remembers of an account — its last Home, the made-for-you lists
         * found for it — gone with the account: the next one to sign in must not be shown the last
         * one's Discover Weekly, nor have its own search skipped because those ids looked known.
         */
        fun forgetAccount(context: android.content.Context) {
            homeFile(context).delete()
            context.getSharedPreferences(MADE_FOR_YOU_PREFS, android.content.Context.MODE_PRIVATE).edit().clear().apply()
        }

        private const val MADE_FOR_YOU_PREFS = "wear_made_for_you"
        private const val KEY_MADE = "items"
        private const val KEY_SEARCHED_AT = "searched_at"
        private const val SEARCH_EVERY_MS = 24 * 60 * 60_000L

        /** Their names are the same in every language Spotify ships. */
        val SEARCHES = listOf("Discover Weekly", "Release Radar")
    }
}
