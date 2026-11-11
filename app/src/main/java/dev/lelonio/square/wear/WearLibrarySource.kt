@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.lelonio.square.wear

import dev.lelonio.square.R
import dev.lelonio.square.SquareApplication
import dev.lelonio.square.backend.SearchLabels
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.data.AccountLocalDataCoordinator.Access
import dev.lelonio.square.data.AccountRequestCache
import dev.lelonio.square.data.writeDownloadIndexAtomically
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
    private val contexts = AccountRequestCache<Pair<Long, List<CatalogTrack>>>(app.accountLocalData)

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
    suspend fun home(access: Access): LibraryPage = withContext(Dispatchers.IO) {
        app.accountLocalData.use(access) { Unit }
        if (!app.accountLocalData.isOnlineCurrent(access)) {
            return@withContext cachedHome(access) ?: section(access, LibrarySection.DOWNLOADS)
        }
        if (!engineReady(access)) return@withContext cachedHome(access) ?: section(access, LibrarySection.DOWNLOADS)
        app.accountLocalData.requireOnline(access)
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
        app.accountLocalData.requireOnline(access)
        val playlists = runCatching { app.spotifyBackend.playlists() }.getOrDefault(emptyList())
        if (shelves.isEmpty() && playlists.isEmpty()) cachedHome(access)?.let { return@withContext it }

        app.accountLocalData.use(access) { Unit }
        val pinned = app.pinnedPlaylists.pinned.value
        val top = WatchHome.top(pinned, app.recentContexts.contexts.value, app.playlistOrder.order.value, playlists, HOME_TOP_ROWS)
        val fromHome = shelves.flatMap { shelf -> shelf.items.map { HomeEntry(it.uri, it.name, it.artworkUrl) } }
        val made = WatchHome.madeForYou(fromHome, playlists, rememberedMadeForYou(access, fromHome + top, playlists), MADE_FOR_YOU_ROWS)
        val shown = (top.map { it.uri } + made.map { it.uri }).toHashSet()

        val withCovers = withCovers(access, top + made)
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
        saveHome(access, page)
        page
    }

    /** Starts the phone's engine if it is not running, and waits a little for it to connect. */
    private suspend fun engineReady(access: Access): Boolean {
        app.accountLocalData.requireOnline(access)
        val ready = app.wearBridge.engineReady(ENGINE_WAIT_MS)
        app.accountLocalData.requireOnline(access)
        return ready
    }

    /**
     * The made-for-you playlists found before, and once a day a search for the two that matter
     * most when neither Spotify's home nor the library has them: Discover Weekly and Release Radar
     * are the listener's own, so their ids never change, and remembering them costs nothing.
     */
    private suspend fun rememberedMadeForYou(access: Access, seen: List<HomeEntry>, playlists: List<CatalogPlaylist>): List<HomeEntry> {
        val prefs = app.getSharedPreferences("$MADE_FOR_YOU_PREFS:${access.namespace}", android.content.Context.MODE_PRIVATE)
        val known = prefs.getString(KEY_MADE, null)?.let { saved ->
            runCatching { dev.pampa.fluidify.wear.protocol.WearCodec.json.decodeFromString(MadeList.serializer(), saved).items }.getOrNull()
        }.orEmpty().map { HomeEntry(it.uri, it.name, it.artworkUrl) }
        val present = (seen.map { it.uri } + playlists.map { it.uri } + known.map { it.uri }).filter(WatchHome::isMadeForYou)
        val hasWeekly = present.any { it.removePrefix("spotify:playlist:").startsWith("37i9dQZEVX") }
        val lastSearch = prefs.getLong(KEY_SEARCHED_AT, 0L)
        if (hasWeekly || System.currentTimeMillis() - lastSearch < SEARCH_EVERY_MS) return known
        app.accountLocalData.use(access) { prefs.edit().putLong(KEY_SEARCHED_AT, System.currentTimeMillis()).commit() }
        val labels = SearchLabels(app.getString(R.string.artist), app.getString(R.string.album), app.getString(R.string.playlist))
        val found = SEARCHES.flatMap { query ->
            app.accountLocalData.requireOnline(access)
            runCatching { app.spotifyBackend.search(query, labels).playlists }.getOrDefault(emptyList())
                .filter { WatchHome.isMadeForYou(it.uri) }
                .take(1)
                .map { HomeEntry(it.uri, it.title, it.artworkUrl) }
        }
        val merged = (found + known).distinctBy { it.uri }.take(MADE_FOR_YOU_ROWS)
        app.accountLocalData.use(access) { prefs.edit().putString(KEY_MADE, dev.pampa.fluidify.wear.protocol.WearCodec.json.encodeToString(MadeList.serializer(), MadeList(merged.map { MadeItem(it.uri, it.name, it.artworkUrl) }))).commit() }
        return merged
    }

    /**
     * Covers for the rows that came without one. Spotify's own playlists — Discover Weekly, the
     * mixes — keep their picture on the playlist, not in the account's list of it, so those rows
     * arrived blank. Looked up once each and remembered for as long as the process lives.
     */
    private suspend fun withCovers(access: Access, entries: List<HomeEntry>): Map<String, HomeEntry> {
        var lookups = 0
        return entries.associate { entry ->
            val art = entry.artworkUrl ?: coverCache.get(access, entry.uri) ?: if (
                lookups < MAX_COVER_LOOKUPS && entry.uri.startsWith("spotify:playlist:") && noCover.get(access, entry.uri) != true
            ) {
                lookups++
                // A playlist with no cover is remembered as such too: asked again on every Home, the
                // same twelve lookups ran each time and pushed the answer past the watch's patience.
                app.accountLocalData.requireOnline(access)
                runCatching { dev.lelonio.square.data.Catalog.playlistCover(entry.uri) }.getOrNull()
                    ?.also { coverCache.put(access, entry.uri, it) } ?: null.also { noCover.put(access, entry.uri, true) }
            } else {
                null
            }
            entry.uri to entry.copy(artworkUrl = art)
        }
    }

    private val coverCache = AccountRequestCache<String>(app.accountLocalData)
    private val noCover = AccountRequestCache<Boolean>(app.accountLocalData)
    private fun homeFile(access: Access) = java.io.File(app.filesDir, "watch-home-v2/${access.namespace}.json")

    private fun saveHome(access: Access, page: LibraryPage) = app.accountLocalData.use(access) {
        val file = homeFile(access)
        file.parentFile!!.mkdirs()
        writeDownloadIndexAtomically(file, dev.pampa.fluidify.wear.protocol.WearCodec.json.encodeToString(LibraryPage.serializer(), page))
    }

    /** The last Home answered, with the pins as they are now. */
    fun cachedHome(access: Access): LibraryPage? {
        app.accountLocalData.use(access) { Unit }
        val page = runCatching { dev.pampa.fluidify.wear.protocol.WearCodec.decodeOrNull(LibraryPage.serializer(), homeFile(access).readBytes()) }.getOrNull()
            ?: return null
        app.accountLocalData.use(access) { Unit }
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

    suspend fun section(access: Access, section: LibrarySection): LibraryPage = withContext(Dispatchers.IO) {
        app.accountLocalData.use(access) { Unit }
        if (section != LibrarySection.DOWNLOADS && section != LibrarySection.RECENT) app.accountLocalData.requireOnline(access)
        val page = when (section) {
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
                store.load()
                app.accountLocalData.use(access) { Unit }
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
        app.accountLocalData.use(access) { page }
    }

    suspend fun context(access: Access, uri: String, offset: Int, limit: Int): ContextPage = withContext(Dispatchers.IO) {
        app.downloads.load()
        val offline = app.downloads.read(access) {
            if (owners.value.containsKey(uri)) tracksForOwner(uri) else null
        }
        if (offline != null) return@withContext app.accountLocalData.use(access) {
            val label = app.downloads.labelOf(uri)
            ContextPage(uri, label?.name.orEmpty(), artKey = artKeyOf(label?.artworkUrl),
                artUrl = label?.artworkUrl, tracks = offline.drop(offset).take(limit).map { it.toItem() }, total = offline.size)
        }
        app.accountLocalData.requireOnline(access)
        if (uri.startsWith("spotify:artist:") && !app.webApi.isReady) {
            return@withContext ContextPage(uri, "", tracks = emptyList(),
                unavailableReason = app.getString(R.string.connect_app_in_settings))
        }
        val tracks = contextsLock.withLock {
            val now = android.os.SystemClock.elapsedRealtime()
            if (offset == 0) contexts.remove(access, uri)
            val cached = contexts.get(access, uri)?.takeIf { now - it.first <= 120_000L }
            cached?.second ?: app.spotifyBackend.tracksOf(uri).also {
                contexts.put(access, uri, now to it)
            }
        }
        app.accountLocalData.requireOnline(access)
        val playlist = runCatching { app.spotifyBackend.playlists().firstOrNull { it.uri == uri } }.getOrNull()
        val label = app.downloads.labelOf(uri)
        val art = playlist?.artworkUrl ?: label?.artworkUrl ?: tracks.firstOrNull()?.artworkUrl
        ContextPage(
            uri = uri,
            title = playlist?.name ?: label?.name ?: tracks.firstOrNull()?.album.orEmpty().takeIf { uri.startsWith("spotify:album:") }.orEmpty(),
            artKey = artKeyOf(art),
            artUrl = art?.takeIf { it.startsWith("https://") },
            tracks = tracks.drop(offset).take(limit).map { it.toItem() },
            total = tracks.size,
        )
    }

    suspend fun search(access: Access, query: String): LibraryPage = withContext(Dispatchers.IO) {
        app.accountLocalData.requireOnline(access)
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
        title = name.ifBlank { app.getString(R.string.unnamed) },
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
        // The old global wear-home.json and preferences stay untouched and unassigned.

        private const val MADE_FOR_YOU_PREFS = "wear_made_for_you"
        private const val KEY_MADE = "items"
        private const val KEY_SEARCHED_AT = "searched_at"
        private const val SEARCH_EVERY_MS = 24 * 60 * 60_000L

        /** Their names are the same in every language Spotify ships. */
        val SEARCHES = listOf("Discover Weekly", "Release Radar")
    }
}
