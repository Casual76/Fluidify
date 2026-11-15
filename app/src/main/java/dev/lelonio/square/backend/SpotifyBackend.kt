package dev.lelonio.square.backend

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dev.lelonio.square.SquareApplication
import dev.lelonio.square.backend.lyrics.Lossless
import dev.lelonio.square.data.Catalog
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.data.PlaylistDetailsDto
import dev.lelonio.square.data.SearchResults
import dev.lelonio.square.data.toCatalogTrack
import dev.lelonio.square.data.toResults
import dev.lelonio.square.nativecore.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import dev.lelonio.square.playback.AudioOutput
import dev.lelonio.square.playback.LibrespotPlayer
import dev.lelonio.square.playback.PlayQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The existing Spotify path, behind [MusicBackend].
 *
 * Nothing here is new: the catalogue calls are the ones the UI already made
 * against [Catalog] and the Web API, and [createPlayer] builds the same
 * [LibrespotPlayer] `PlaybackService` built inline. The engine's *lifetime* is
 * deliberately still the service's — starting it needs a token, a language, a
 * bitrate and a restart path for each, none of which a backend should own.
 */
@UnstableApi
class SpotifyBackend(private val container: SquareApplication) : MusicBackend {


    override var searchNeedsSetup: Boolean = false
        private set

    private val _authState = MutableStateFlow<BackendAuthState>(
        if (container.spotifySignedIn) BackendAuthState.Connecting else BackendAuthState.LoggedOut,
    )
    override val authState: StateFlow<BackendAuthState> = _authState.asStateFlow()

    /**
     * Logged in *and* the access point has answered.
     *
     * Both halves matter: a stored token with no engine behind it yet is a
     * session every catalogue call would fail against.
     */
    override val isReady: Boolean
        get() = container.spotifySignedIn && NativeBridge.isConnected

    override suspend fun refreshAuth() {
        if (!container.spotifySignedIn) {
            _authState.value = BackendAuthState.LoggedOut
            return
        }
        _authState.value = runCatching { Catalog.username() }
            .fold(
                onSuccess = { BackendAuthState.LoggedIn(it) },
                onFailure = { BackendAuthState.Failed(it.message ?: it::class.java.simpleName) },
            )
    }

    override suspend fun logOut() {
        container.tokenStore.clear()
        _authState.value = BackendAuthState.LoggedOut
        // The watch signs out with the phone: now if it can hear, or when it next connects.
        runCatching { container.wearBridge.auth.onSignedOut() }
    }

    /**
     * Spotify's own search, with the Web API behind it.
     *
     * The gateway first, for two reasons. It searches the way Spotify's own
     * clients do, which includes the words *inside* a song — type a line you
     * remember and the song is the first result — and it answers anyone who is
     * signed in, where the Web API wants an application the listener has to
     * register for themselves.
     *
     * What it does not offer is any promise of being there tomorrow: the query
     * is addressed by a hash Spotify retires whenever it rebuilds its web
     * client. So the registered application stays as the fallback, and a search
     * only fails when both have nothing; see [dev.lelonio.square.data.Gateway].
     */
    override suspend fun search(query: String, labels: SearchLabels): SearchResults =
        when (val outcome = searchOutcome(query, labels)) {
            is SearchOutcome.Success -> { searchNeedsSetup = false; outcome.results }
            SearchOutcome.NeedsSetup -> { searchNeedsSetup = true; SearchResults() }
            is SearchOutcome.Failure -> { searchNeedsSetup = false; throw outcome.cause }
        }

    override suspend fun searchOutcome(query: String, labels: SearchLabels): SearchOutcome {
        val term = query.trim()
        val gatewayFailure = try {
            val raw = container.gateway.searchOrThrow(term)
            val results = raw?.let { dev.lelonio.square.data.GatewaySearch.parse(it, labels) }
            if (results != null) return SearchOutcome.Success(results)
            val body = org.json.JSONObject(raw.orEmpty())
            val errors = body.optJSONArray("errors")?.toString().orEmpty()
            when {
                errors.contains("PersistedQueryNotFound", ignoreCase = true) ||
                    errors.contains("PersistedQueryNotSupported", ignoreCase = true) -> SearchOutcome.NeedsSetup
                errors.contains("UNAUTHENTICATED", ignoreCase = true) ||
                    errors.contains("FORBIDDEN", ignoreCase = true) -> SearchOutcome.Failure(
                        SearchFailureKind.AUTH, IllegalStateException("Gateway search authorization failed"),
                    )
                else -> SearchOutcome.Failure(SearchFailureKind.RESPONSE, IllegalStateException("Gateway search response unavailable"))
            }
        } catch (error: Throwable) {
            searchFailure(error)
        }
        // Keep the useful Web API fallback even after gateway transport failure.
        if (container.webApi.isReady) return try {
            SearchOutcome.Success(container.api.search(term).toResults(
                artistLabel = labels.artist, albumLabel = labels.album, playlistLabel = labels.playlist,
            ))
        } catch (error: Throwable) {
            searchFailure(error)
        }
        return gatewayFailure
    }

    /**
     * The account's Liked Songs, which Spotify keeps outside the playlists.
     *
     * It behaves like one everywhere it matters — a name, a cover, a list to
     * open and play — so it is put at the head of the library rather than given
     * a screen of its own, with the same purple cover Spotify's own clients use.
     */
    private suspend fun likedSongs(): CatalogPlaylist? {
        val uri = withContext(Dispatchers.IO) {
            runCatching { NativeBridge.collectionUri() }.getOrDefault("")
        }
        if (uri.isEmpty()) return null
        return CatalogPlaylist(
            uri = uri,
            name = container.getString(dev.lelonio.square.R.string.liked_songs),
            artworkUrl = LIKED_SONGS_COVER,
        )
    }

    /** The access point first, the Web API when `rootlist` fails; see MainViewModel. */
    override suspend fun playlists(): List<CatalogPlaylist> =
        likedSongs().let { liked ->
            listOfNotNull(liked) +
                rootlist().filterNot { it.uri == liked?.uri || it.uri == Catalog.DJ_URI }
        }

    private suspend fun rootlist(): List<CatalogPlaylist> {
        val access = checkNotNull(container.accountLocalData.captureOnline()) { "Spotify account not ready" }
        val network = dev.lelonio.square.data.SpotifyNetworkPolicy.capture()
        return readSpotifyRootlist(
            accountId = access.account.id,
            checkCurrent = { network.check(); container.accountLocalData.requireOnline(access) },
            primary = { Catalog.playlists() },
            fallbackReady = { container.webApi.isReady },
            fallbackPage = { offset -> container.api.playlists(limit = WEB_API_LIBRARY_PAGE, offset = offset) },
            fallbackUsed = { android.util.Log.w(TAG, "rootlist unusable, asking the Web API: ${it.message}") },
        )
    }

    /**
     * A whole playlist, album or artist, resolved in one go.
     *
     * The detail screen does not call this: it publishes each page as it lands
     * and needs the loop itself. This is for callers that just want the list.
     */
    override suspend fun tracksOf(uri: String): List<CatalogTrack> = when {
        uri.startsWith("spotify:artist:") -> {
            check(container.webApi.isReady) { "un artista richiede la tua applicazione Spotify" }
            container.api.artistTopTracks(uri.substringAfterLast(':')).tracks
                .map { it.toCatalogTrack() }
        }

        // Spotify's own gateway first, for both of the lists it answers for:
        // it needs nothing of the listener beyond being logged in, and hands
        // back whole tracks rather than URIs to look up one at a time. The Web
        // API, which is metered against an application the listener has to
        // register themselves, is what is left when a query hash is retired.
        uri.startsWith("spotify:playlist:") ->
            container.gateway.readAll { offset -> container.gateway.playlistTracks(uri, offset) }
                ?: if (container.webApi.isReady) {
                    webApiPlaylistTracks(uri.substringAfterLast(':'))
                } else {
                    Catalog.tracks(Catalog.contextTrackUris(uri))
                }

        // Liked Songs is not a context the access point will resolve either:
        // asked for it answers 503. So when both of these fail there is
        // nothing left to try.
        uri.endsWith(":collection") ->
            container.gateway.readAll { offset -> container.gateway.savedTracks(offset) } ?: run {
                check(container.webApi.isReady) {
                    container.getString(dev.lelonio.square.R.string.liked_songs_failed)
                }
                savedTracks()
            }

        else -> Catalog.tracks(Catalog.contextTrackUris(uri))
    }

    /** Everything the account has saved, newest first, a page at a time. */
    private suspend fun savedTracks(): List<CatalogTrack> {
        val network = dev.lelonio.square.data.SpotifyNetworkPolicy.capture()
        val access = checkNotNull(container.accountLocalData.captureOnline())
        return readCompleteLibraryPages(
            checkCurrent = { network.check(); container.accountLocalData.requireOnline(access) },
            page = { offset -> container.api.savedTracks(limit = WEB_API_LIBRARY_PAGE, offset = offset) },
        ).mapNotNull { saved ->
            saved.track.takeIf { it.isPlayable != false && it.uri.startsWith("spotify:track:") }
                ?.toCatalogTrack(saved.addedAt)
        }
    }

    private suspend fun webApiPlaylistTracks(id: String): List<CatalogTrack> {
        val network = dev.lelonio.square.data.SpotifyNetworkPolicy.capture()
        val access = checkNotNull(container.accountLocalData.captureOnline())
        return readCompleteLibraryPages(
            checkCurrent = { network.check(); container.accountLocalData.requireOnline(access) },
            page = { offset -> container.api.playlistTracks(id, limit = WEB_API_PAGE, offset = offset) },
        ).mapNotNull { item ->
            item.track?.takeIf { it.isPlayable != false && it.uri.startsWith("spotify:track:") }
                ?.toCatalogTrack(item.addedAt)
        }
    }

    /**
     * lossless.wtf first, Spotify's own behind it; see [Catalog.lyrics].
     *
     * Spotify's are line-synced and only cover what Spotify itself holds. The
     * TTML archive is timed to the word where it has a track, so it is asked
     * first here for the same reason it is on the other backend, and the access
     * point answers for everything it does not have.
     */
    override suspend fun lyrics(
        uri: String,
        title: String,
        artist: String,
        durationMs: Long,
    ) = dev.lelonio.square.backend.lyrics.Amll.lyrics(uri)
        ?: Lossless.lyrics(title, artist, durationMs)
        ?: Catalog.lyrics(uri)

    /**
     * Whether playlists can be made and changed.
     *
     * The listener's own registered application is what does the writing, so
     * that is what this asks about. It used to ask for the app's own OAuth
     * session as well, and that session lapses while everything else goes on
     * working — playback holds the access point's credential and does not need
     * it. What the listener saw was the long press on a playlist doing nothing
     * and the button for a new one gone, on an account that was signed in and
     * could perfectly well edit.
     */
    override val canEditPlaylists: Boolean
        get() = container.spotifySignedIn && container.webApi.isReady

    override suspend fun createPlaylist(name: String): CatalogPlaylist {
        val access = checkNotNull(container.accountLocalData.captureOnline()) { "Spotify account not ready" }
        val network = dev.lelonio.square.data.SpotifyNetworkPolicy.capture()
        suspend fun checkCurrent() {
            currentCoroutineContext().ensureActive()
            network.check()
            container.accountLocalData.requireOnline(access)
        }
        checkCurrent()
        val userId = container.api.me().id
        checkCurrent()
        check(userId == access.account.id) { "Spotify profile belongs to another account" }
        val writer = dev.lelonio.square.data.accountWriteApi(container.accountLocalData, access,
            container.webApi.tokens, dev.lelonio.square.data.PLAYLIST_EDIT_SCOPES, network)
        val created = writer.createPlaylist(userId, PlaylistDetailsDto(name))
        checkCurrent()
        return createdSpotifyPlaylist(created, userId)
    }

    override suspend fun renamePlaylist(uri: String, name: String) {
        val access = checkNotNull(container.accountLocalData.captureOnline()) { "Spotify account not ready" }
        dev.lelonio.square.data.accountWriteApi(container.accountLocalData, access,
            container.webApi.tokens, dev.lelonio.square.data.PLAYLIST_EDIT_SCOPES)
            .updatePlaylistDetails(uri.substringAfterLast(':'), PlaylistDetailsDto(name))
    }

    override suspend fun deletePlaylist(uri: String) {
        val access = checkNotNull(container.accountLocalData.captureOnline()) { "Spotify account not ready" }
        dev.lelonio.square.data.accountWriteApi(container.accountLocalData, access,
            container.webApi.tokens, dev.lelonio.square.data.PLAYLIST_EDIT_SCOPES)
            .unfollowPlaylist(uri.substringAfterLast(':'))
    }

    override fun owns(uri: String) = uri.startsWith("spotify:")

    /**
     * The queue behind the player built by [createPlayer].
     *
     * Exposed because only the queue knows the pre-shuffle order and the
     * permutation on top of it, which is what the service writes to disk; the
     * Media3 timeline carries just the current sequence.
     */
    lateinit var queue: PlayQueue
        private set

    /** Owns the AudioTrack the native sink writes into. */
    val audioOutput = AudioOutput()

    override fun createPlayer(host: PlaybackHost): Player {
        queue = PlayQueue()
        return LibrespotPlayer(
            host.context,
            host.looper,
            queue,
            audioOutput::setSpeedAndPitch,
            audioOutput::fadeOutThen,
            audioOutput::fadeIn,
            audioOutput::setPlaybackActive,
            audioOutput::duckReverb,
        )
    }

    private companion object {
        /** The Web API's own maximum page size for playlist tracks. */
        /** What the playlist endpoints take a page at a time. */
        const val WEB_API_PAGE = 100

        /**
         * What the account's own lists take, which is half that.
         *
         * `me/playlists` and `me/tracks` cap at 50 and answer 400 to anything
         * larger — not an empty page, an error. It surfaced only when the access
         * point was unreachable and these were asked instead of it, which is to
         * say on a bad connection: the library then failed twice over and
         * reported the second failure.
         */
        const val WEB_API_LIBRARY_PAGE = 50

        /** The same tag the rest of the library load writes under. */
        private const val TAG = "SquareUi"

        /**
         * The purple heart Spotify's own clients draw for Liked Songs.
         *
         * A fixed asset of theirs rather than anything account-specific, and
         * the only way to have this row look like the row it stands for: it is
         * not a playlist, so no cover comes back for it anywhere.
         */
        const val LIKED_SONGS_COVER = "https://misc.scdn.co/liked-songs/liked-songs-640.png"
    }
}
