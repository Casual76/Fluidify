package dev.lelonio.square.data

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * What is downloaded, and who asked for it.
 *
 * The audio itself is the engine's business — `native/src/downloads.rs` writes
 * one encrypted file and one sidecar per track, and the player finds them
 * without ever asking this class. What lives here is everything the engine has
 * no opinion about: which playlists wanted a track, what a track is called so
 * it can be drawn with no connection, and which downloads have yet to happen.
 *
 * ### Owners, and why a track is not simply "downloaded"
 *
 * The requirement this whole class exists for is that a song in two downloaded
 * playlists is one file that both playlists know about. So nothing records "the
 * listener downloaded this track". What is recorded is a set of *owners* — a
 * playlist, an album, an artist, the loose collection of singles, the liked
 * songs — each holding the tracks it asked for, in order.
 *
 * Everything follows from that. Downloading a playlist that shares tracks with
 * one already downloaded costs no bytes. Removing a playlist removes an owner,
 * and the file goes only once no owner is left ([pruneOrphans]). A track shows
 * as downloaded inside the playlist it belongs to because that is the question
 * being asked, not because of a flag on the track.
 *
 * ### The queue is not stored
 *
 * There is no list of pending downloads on disk. What is pending is whatever an
 * owner asked for and has no file yet ([pending]), which means an app killed
 * mid-playlist works out what it still owes the moment it starts, and a
 * download interrupted halfway resumes from the `.part` file the engine left.
 * A queue file would have been a second truth to keep in step with this one.
 */
class DownloadStore internal constructor(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher,
    private val persistIndex: (File, String) -> Unit,
    private val accounts: AccountLocalDataCoordinator,
) {
    constructor(context: Context, accounts: AccountLocalDataCoordinator) : this(
        context, Dispatchers.IO, { file, text -> writeDownloadIndexAtomically(file, text) }, accounts,
    )

    /**
     * A file that is actually on disk, as the engine reported it.
     *
     * Present only for completed downloads: the entry is written after
     * `downloadTrack` returns, so an entry here and a sidecar there mean the
     * same thing.
     */
    @Serializable
    data class FileRecord(
        val trackId: String,
        val format: String,
        val bytes: Long,
        /** The quality actually stored, so a re-download at 320 knows to run. */
        val kbps: Int,
        val downloadedAt: Long,
    )

    /** Enough to draw a downloaded playlist with no connection. */
    @Serializable
    data class OwnerLabel(
        val name: String,
        val artworkUrl: String? = null,
        val kind: String = KIND_PLAYLIST,
    )

    @Serializable
    data class Failure(val reason: String, val attempts: Int, val at: Long) {
        /**
         * When this track is worth trying again: two seconds, then four, then
         * eight, up to five minutes.
         *
         * The first failure of a batch is almost always the connection going,
         * and the whole batch recovers together — so the early waits are short
         * enough that the listener never sees the pause, and the late ones long
         * enough that a track Spotify genuinely will not serve stops costing
         * anything.
         */
        fun readyAt(): Long =
            at + (BASE_BACKOFF_MS shl (attempts - 1).coerceIn(0, 8)).coerceAtMost(MAX_BACKOFF_MS)

        private companion object {
            const val BASE_BACKOFF_MS = 2_000L
            const val MAX_BACKOFF_MS = 5L * 60 * 1000
        }
    }

    /** Position, not URI alone, identifies an occurrence (playlists allow repeats). */
    @Serializable
    private data class OwnerOccurrence(val uri: String, val addedAt: String? = null)

    @Serializable
    private data class Index(
        val version: Int = 1,
        val files: Map<String, FileRecord> = emptyMap(),
        val owners: Map<String, List<String>> = emptyMap(),
        /**
         * Shared audio metadata for every track any owner asked for. Context
         * dates live only in ownerOccurrences, never in this URI-keyed map.
         *
         * Kept here rather than read from [ContextCacheStore] because that
         * cache expires after a week and is trimmed to forty contexts, and a
         * download is meant to outlive both. Offline, this is what the rows are
         * drawn from.
         */
        val tracks: Map<String, CatalogTrack> = emptyMap(),
        val labels: Map<String, OwnerLabel> = emptyMap(),
        val failures: Map<String, Failure> = emptyMap(),
        val ownerOccurrences: Map<String, List<OwnerOccurrence>> = emptyMap(),
        val legacyOwnerUris: Map<String, String> = emptyMap(),
    )

    /** One atomic document owns every namespace and the legacy assignment decision. */
    @Serializable
    private data class AccountIndex(
        val version: Int = 1,
        val accounts: Map<String, Index> = emptyMap(),
        // Physical records outlive an individual namespace's last reference. Never projected globally.
        val assets: Map<String, FileRecord> = emptyMap(),
        val legacy: Index? = null,
        val legacyAssignedTo: String? = null,
    )

    data class LegacyStatus(val pending: Boolean = false, val collections: Int = 0, val files: Int = 0)
    class LegacyAssignment internal constructor(
        internal val store: DownloadStore,
        internal val access: AccountLocalDataCoordinator.Access,
        internal val fingerprint: String,
    ) {
        val recipient: String get() = access.account.id
    }

    /** Where the engine keeps the audio. Handed to it once, at startup. */
    private val appContext = context.applicationContext
    val root: File = File(appContext.filesDir, DIR_NAME)

    private val indexFile = File(root, "account-index-v1.json")
    private val legacyFile = File(root, INDEX_NAME)
    private var book = AccountIndex()
    private val _legacy = MutableStateFlow(LegacyStatus())
    val legacy: StateFlow<LegacyStatus> = _legacy.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }
    private val writeLock = Mutex()

    private val _index = MutableStateFlow(Index(version = 2))
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())

    /** What is on disk, by track URI. */
    val files: StateFlow<Map<String, FileRecord>>
        get() = _files.asStateFlow()
    private val _files = MutableStateFlow<Map<String, FileRecord>>(emptyMap())

    /** Who asked for what, by owner URI, in the order the owner lists them. */
    val owners: StateFlow<Map<String, List<String>>>
        get() = _owners.asStateFlow()
    private val _owners = MutableStateFlow<Map<String, List<String>>>(emptyMap())

    /** Downloads in flight, as a fraction. Never persisted. */
    val progress: StateFlow<Map<String, Float>> = _progress.asStateFlow()

    val failures: StateFlow<Map<String, Failure>>
        get() = _failures.asStateFlow()
    private val _failures = MutableStateFlow<Map<String, Failure>>(emptyMap())

    /**
     * [ownerState] for every owner, ready for a screen to read.
     *
     * A flow, so a page redraws its button as the queue moves. Only the owners
     * are computed this way — there are tens of them. The per-track answer is
     * left to the caller, which reads [files] and [progress] and asks about the
     * rows it is actually drawing: building a map of several thousand tracks
     * several times a second, to mark the dozen on screen, is the version of
     * this that makes a playlist scroll badly.
     */
    private val _ownerStates = MutableStateFlow<Map<String, OwnerState>>(emptyMap())
    val ownerStates: StateFlow<Map<String, OwnerState>> = _ownerStates.asStateFlow()

    private val ownerRefreshFences = mutableMapOf<String, Any>()

    init {
        accounts.observe { access ->
            ownerRefreshFences.clear()
            _progress.value = emptyMap()
            publish(access?.let { book.accounts[it.namespace] } ?: Index(version = 2))
        }
    }

    internal fun observeAccess(observer: (AccountLocalDataCoordinator.Access?) -> Unit) = accounts.observe(observer)
    fun captureAccess(): AccountLocalDataCoordinator.Access? = accounts.capture()
    fun captureOnlineAccess(): AccountLocalDataCoordinator.Access? = accounts.captureOnline()
    fun isOnlineCurrent(access: AccountLocalDataCoordinator.Access): Boolean = accounts.isOnlineCurrent(access)
    fun isCurrent(access: AccountLocalDataCoordinator.Access): Boolean = accounts.isCurrent(access)
    fun <T> read(access: AccountLocalDataCoordinator.Access, block: DownloadStore.() -> T): T =
        accounts.use(access) { block() }

    /** No local authority is a valid empty bootstrap, never a reason to skip online authentication. */
    fun nativeBindingForBoot(): String = captureAccess()?.let(::nativeBindingJson) ?: "null"

    /** Captured under the account monitor; a physical file is readable only through its namespace. */
    fun nativeBindingJson(access: AccountLocalDataCoordinator.Access): String = read(access) {
        kotlinx.serialization.json.buildJsonObject {
            put("owner", kotlinx.serialization.json.JsonPrimitive(access.binding.lineage))
            put("namespace", kotlinx.serialization.json.JsonPrimitive(access.namespace))
            put("root", kotlinx.serialization.json.JsonPrimitive(root.absolutePath))
            put("records", kotlinx.serialization.json.buildJsonObject {
                files.value.forEach { (uri, record) ->
                    put(uri, kotlinx.serialization.json.JsonPrimitive(record.trackId))
                }
            })
        }.toString()
    }

    // ------------------------------------------------------------- lifecycle

    /**
     * Reads the index. Safe to call more than once; the second call is a no-op.
     *
     * Unknown or malformed account data fails closed and keeps every physical file.
     * It must never reopen the legacy index after a prior assignment.
     */
    suspend fun load() = withContext(ioDispatcher) {
        writeLock.withLock {
            accounts.locked {
                if (loaded) return@locked
                root.mkdirs()
                // Fail closed on malformed account data. Never fall back to the global index:
                // that could expose a previously assigned legacy collection to another account.
                book = if (indexFile.exists()) {
                    json.decodeFromString(AccountIndex.serializer(), indexFile.readText()).also {
                        check(it.version == 1) { "Unsupported account download index" }
                    }
                } else {
                    val legacy = if (legacyFile.exists()) normalized(
                        json.decodeFromString(Index.serializer(), legacyFile.readText()),
                    ) else null
                    AccountIndex(legacy = legacy, assets = legacy?.files.orEmpty()).also { persist(it) }
                }
                loaded = true
                updateLegacyStatus()
                publish(accounts.capture()?.let { book.accounts[it.namespace] } ?: Index(version = 2))
            }
        }
    }

    private fun normalized(index: Index): Index {
        check(index.version in 1..2) { "Unsupported legacy download index" }
        return index.copy(version = 2,
            tracks = index.tracks.mapValues { (_, track) -> track.copy(addedAt = null) })
    }

    private suspend fun <T> mutate(access: AccountLocalDataCoordinator.Access, block: () -> T): T =
        withContext(ioDispatcher) {
            load()
            writeLock.withLock { accounts.use(access, block) }
        }

    private var loaded = false

    /**
     * Drops index entries whose audio has gone, and forgets tracks and labels
     * nothing points at any more.
     *
     * The two halves of this can drift for reasons neither side controls: the
     * listener can clear the app's storage from Android's settings, which takes
     * the audio and leaves the index, and a crash between the rename and the
     * index write leaves a file nothing claims. Cheap enough to run at startup.
     */
    suspend fun reconcile(access: AccountLocalDataCoordinator.Access) = mutate(access) {
        val current = _index.value
        val surviving = current.files.filterValues { audioFile(it.trackId).isFile }
        if (surviving.size != current.files.size) commit(current.copy(files = surviving))
    }

    // ------------------------------------------------------------- questions

    fun isDownloaded(trackUri: String): Boolean = accounts.locked {
        accounts.capture() != null && _files.value.containsKey(trackUri)
    }

    /**
     * How a single row should draw itself.
     *
     * [DownloadState.None] for anything the listener has not asked for, which
     * is deliberately indistinguishable from "not started": a row shows nothing
     * at all until a download for it exists, so an undownloaded playlist looks
     * exactly as it did before this feature.
     */
    fun stateOf(trackUri: String): DownloadState = accounts.locked { when {
        accounts.capture() == null -> DownloadState.None
        _files.value.containsKey(trackUri) -> DownloadState.Done
        _progress.value.containsKey(trackUri) ->
            DownloadState.Running(_progress.value.getValue(trackUri))
        _failures.value.containsKey(trackUri) ->
            DownloadState.Failed(_failures.value.getValue(trackUri).reason)
        isWanted(trackUri) -> DownloadState.Queued
        else -> DownloadState.None
    } }

    /** Whether any owner still asks for this track. */
    fun isWanted(trackUri: String): Boolean = accounts.locked {
        accounts.capture() != null && _owners.value.values.any { tracks -> tracks.contains(trackUri) }
    }

    /**
     * How a playlist, album or artist page should draw its button.
     *
     * `Partial` is not a failure. Some tracks are not available on the account's
     * market and never will be, and a playlist that says "38 of 40" is telling
     * the truth about something the app cannot fix.
     */
    fun ownerState(ownerUri: String): OwnerState {
        if (accounts.capture() == null) return OwnerState.None
        val wanted = _owners.value[ownerUri] ?: return OwnerState.None
        if (wanted.isEmpty()) return OwnerState.None

        val files = _files.value
        val progress = _progress.value
        val failures = _failures.value

        val done = wanted.count(files::containsKey)
        if (done == wanted.size) return OwnerState.Complete
        if (ownerUri.startsWith("downloads:legacy:")) return OwnerState.Partial(done, wanted.size)

        val running = wanted.filter(progress::containsKey)
        val outstanding = wanted.filterNot(files::containsKey)
        val stuck = outstanding.count(failures::containsKey)

        // Everything left has already failed as far as it is going to, so the
        // owner is as complete as it will get. Saying "downloading" for ever
        // would be the alternative, and the ring would never stop turning.
        if (stuck == outstanding.size) return OwnerState.Partial(done, wanted.size)

        // The share of the whole, not of what is left: a ring that restarts
        // every time a track finishes is worse than one that creeps.
        val fraction =
            (done + running.sumOf { progress[it]?.toDouble() ?: 0.0 }) / wanted.size
        return OwnerState.Running(fraction.toFloat().coerceIn(0f, 1f), done, wanted.size)
    }

    /**
     * Tracks an owner asked for that have no file yet and are worth trying now,
     * in the order their owners list them.
     *
     * Failures are held back rather than dropped: a track that failed once is
     * offered again after a wait that doubles each time, and only stops being
     * offered after [MAX_ATTEMPTS]. Without the wait a track Spotify does not
     * serve on this account would be retried in a tight loop for as long as the
     * queue ran; without the ceiling it would be retried for ever, on every
     * launch, and the queue would never report itself finished.
     */
    fun pending(now: Long = System.currentTimeMillis()): List<String> {
        if (accounts.capture() == null) return emptyList()
        val files = _files.value
        val failures = _failures.value
        val ordered = LinkedHashSet<String>()
        for ((owner, tracks) in _owners.value) {
            if (owner.startsWith("downloads:legacy:")) continue // attribution is not ongoing-download consent
            for (uri in tracks) {
                if (files.containsKey(uri)) continue
                val failure = failures[uri]
                if (failure != null && failure.attempts >= MAX_ATTEMPTS) continue
                if (failure != null && failure.readyAt() > now) continue
                ordered.add(uri)
            }
        }
        return ordered.toList()
    }

    /**
     * When the next track held back by a failure becomes worth trying again, or
     * null when nothing is waiting.
     *
     * The queue sleeps until this rather than polling, so a phone with one
     * stubborn track in a playlist is not woken every few seconds for it.
     */
    fun retryAt(): Long? {
        if (accounts.capture() == null) return null
        val files = _files.value
        val claimed = _owners.value.filterKeys { !it.startsWith("downloads:legacy:") }.values.flatten().toSet()
        return _failures.value
            .filterKeys { it in claimed && !files.containsKey(it) }
            .values
            .filter { it.attempts < MAX_ATTEMPTS }
            .minOfOrNull { it.readyAt() }
    }

    /** Tracks that will not be tried again unless the listener asks. */
    fun givenUp(): List<String> {
        if (accounts.capture() == null) return emptyList()
        val claimed = _owners.value.filterKeys { !it.startsWith("downloads:legacy:") }.values.flatten().toSet()
        val files = _files.value
        return _failures.value
            .filter { (uri, failure) ->
                uri in claimed && !files.containsKey(uri) && failure.attempts >= MAX_ATTEMPTS
            }
            .keys
            .toList()
    }

    /** Metadata for a downloaded track, for drawing it with no connection. */
    fun trackOf(trackUri: String): CatalogTrack? = accounts.locked {
        accounts.capture()?.let { _index.value.tracks[trackUri] }
    }

    /**
     * One immutable index snapshot, in owner order, including repeated and pending
     * tracks. Context dates never come from the shared audio metadata. Legacy
     * occurrences without a date stay unknown until this owner is refreshed.
     */
    fun tracksForOwner(ownerUri: String): List<CatalogTrack> {
        if (accounts.capture() == null) return emptyList()
        val snapshot = _index.value
        val occurrences = snapshot.ownerOccurrences[ownerUri].orEmpty()
        return snapshot.owners[ownerUri].orEmpty().mapIndexedNotNull { index, uri ->
            val track = snapshot.tracks[uri] ?: return@mapIndexedNotNull null
            val occurrence = occurrences.getOrNull(index)?.takeIf { it.uri == uri }
            track.copy(addedAt = occurrence?.addedAt)
        }
    }

    fun originalLegacyOwner(ownerUri: String): String? = accounts.locked {
        accounts.capture()?.let { _index.value.legacyOwnerUris[ownerUri] }
    }

    fun labelOf(ownerUri: String): OwnerLabel? = accounts.locked {
        accounts.capture()?.let {
            _index.value.labels[ownerUri] ?: if (ownerUri in _index.value.owners) when (ownerUri) {
                SINGLES -> OwnerLabel(appContext.getString(dev.lelonio.square.R.string.downloaded_tracks))
                LIKED -> OwnerLabel(appContext.getString(dev.lelonio.square.R.string.liked_songs))
                else -> null
            } else null
        }
    }

    fun totalBytes(): Long = accounts.locked {
        if (accounts.capture() == null) 0L else _files.value.values.sumOf { it.bytes }
    }

    fun bytesOf(ownerUri: String): Long {
        if (accounts.capture() == null) return 0L
        val files = _files.value
        return _owners.value[ownerUri].orEmpty().sumOf { files[it]?.bytes ?: 0L }
    }

    // ------------------------------------------------------------ what to own

    // Process-local fences, protected by writeLock; never persisted. A replacement
    // invalidates readers even if its URI sequence (and dates) are identical.
    class OwnerRefreshToken internal constructor(
        internal val store: DownloadStore,
        internal val ownerUri: String,
        internal val fence: Any,
        internal val access: AccountLocalDataCoordinator.Access,
        internal val consent: () -> Boolean,
    )

    enum class OwnerRefreshResult { STALE, UNCHANGED, METADATA, MEMBERSHIP }

    /** Capture BEFORE reading the source. Only liked-song opt-in may create an owner. */
    suspend fun beginOwnerRefresh(
        access: AccountLocalDataCoordinator.Access,
        ownerUri: String,
        allowCreate: Boolean = false,
        consent: () -> Boolean = { false },
    ): OwnerRefreshToken? = mutate(access) {
        if ((!allowCreate && ownerUri !in _index.value.owners) ||
            (allowCreate && (ownerUri != LIKED || !consent()))) return@mutate null
        val requiredConsent = if (allowCreate) consent else ({ true })
        OwnerRefreshToken(this, ownerUri, ownerRefreshFences.getOrPut(ownerUri) { Any() }, access, requiredConsent)
    }

    /**
     * First accepted response wins among reads sharing a fence, including a
     * no-op response. Explicit replacement/removal always invalidates that fence.
     * Empty responses retain the existing collection policy and consume nothing.
     * Dates are compared by owner and position; shared track metadata is untouched.
     */
    suspend fun refreshOwner(
        token: OwnerRefreshToken,
        tracks: List<CatalogTrack>,
        label: OwnerLabel?,
    ): OwnerRefreshResult {
        if (!accounts.isCurrent(token.access)) return OwnerRefreshResult.STALE
        return try {
            mutate(token.access) {
                val uri = token.ownerUri
                if (token.store !== this || !token.consent() || ownerRefreshFences[uri] !== token.fence) {
                    return@mutate OwnerRefreshResult.STALE
                }
                if (tracks.isEmpty()) return@mutate OwnerRefreshResult.UNCHANGED
                val current = _index.value
                ownerRefreshFences[uri] = Any()
                if (current.owners[uri] != tracks.map { it.uri }) {
                    replaceOwnerLocked(uri, tracks, label)
                    return@mutate OwnerRefreshResult.MEMBERSHIP
                }
                val occurrences = tracks.map { OwnerOccurrence(it.uri, it.addedAt) }
                if (occurrencesFor(current, uri) == occurrences) return@mutate OwnerRefreshResult.UNCHANGED
                commit(current.copy(ownerOccurrences = current.ownerOccurrences + (uri to occurrences)))
                OwnerRefreshResult.METADATA
            }
        } catch (cancelled: CancellationException) {
            if (!accounts.isCurrent(token.access)) OwnerRefreshResult.STALE else throw cancelled
        }
    }

    /**
     * Records that [ownerUri] wants these tracks, replacing whatever it wanted
     * before.
     *
     * Replacing rather than merging is what keeps a downloaded playlist honest
     * as it changes: a track removed from the playlist upstream stops being
     * wanted here on the next sync, and its file goes once nothing else claims
     * it.
     */
    suspend fun setOwner(
        access: AccountLocalDataCoordinator.Access,
        ownerUri: String,
        tracks: List<CatalogTrack>,
        label: OwnerLabel?,
    ) = mutate(access) {
        replaceOwnerLocked(ownerUri, tracks, label)
    }

    private fun reusableFiles(uris: Collection<String>): Map<String, FileRecord> {
        val shared = book.assets
        return uris.mapNotNull { uri ->
            shared[uri]?.takeIf { audioFile(it.trackId).isFile }?.let { uri to it }
        }.toMap()
    }

    private fun replaceOwnerLocked(ownerUri: String, tracks: List<CatalogTrack>, label: OwnerLabel?) {
        ownerRefreshFences[ownerUri] = Any()
        val current = _index.value
        commit(
            current.copy(
                files = reusableFiles(tracks.map { it.uri }) + current.files,
                owners = current.owners + (ownerUri to tracks.map { it.uri }),
                tracks = current.tracks + tracks.associate { it.uri to it.copy(addedAt = null) },
                ownerOccurrences = current.ownerOccurrences +
                    (ownerUri to tracks.map { OwnerOccurrence(it.uri, it.addedAt) }),
                labels = label?.let { current.labels + (ownerUri to it) } ?: current.labels,
                // Asking again is the listener saying "try this", so the record
                // of past failures for these tracks goes.
                failures = current.failures - tracks.map { it.uri }.toSet(),
            ),
        )
    }

    /** Adds one track to the loose collection of singles. */
    suspend fun addSingle(access: AccountLocalDataCoordinator.Access, track: CatalogTrack) {
        mutate(access) {
            val current = _index.value
            val existing = current.owners[SINGLES].orEmpty()
            if (existing.contains(track.uri)) return@mutate
            ownerRefreshFences[SINGLES] = Any()
            commit(
                current.copy(
                    files = reusableFiles(listOf(track.uri)) + current.files,
                    owners = current.owners + (SINGLES to existing + track.uri),
                    tracks = current.tracks + (track.uri to track.copy(addedAt = null)),
                    // The source playlist's date is not an addition date in singles.
                    ownerOccurrences = current.ownerOccurrences + (SINGLES to
                        (occurrencesFor(current, SINGLES) + OwnerOccurrence(track.uri))),
                    failures = current.failures - track.uri,
                ),
            )
        }
    }

    suspend fun removeSingle(access: AccountLocalDataCoordinator.Access, trackUri: String) {
        mutate(access) {
            val current = _index.value
            val existing = current.owners[SINGLES].orEmpty()
            if (!existing.contains(trackUri)) return@mutate
            ownerRefreshFences[SINGLES] = Any()
            commit(current.copy(
                owners = current.owners + (SINGLES to existing - trackUri),
                ownerOccurrences = current.ownerOccurrences + (SINGLES to
                    occurrencesFor(current, SINGLES).filterNot { it.uri == trackUri }),
            ))
        }
    }

    /**
     * Forgets an owner. What it was alone in wanting is left for [pruneOrphans].
     *
     * Deliberately two steps. Pruning here would drop the files from the index
     * with nobody left holding a list of what to delete, and the audio would
     * stay on disk for good — several hundred megabytes that nothing in the app
     * could ever see again, let alone remove.
     */
    suspend fun removeOwner(access: AccountLocalDataCoordinator.Access, ownerUri: String) = mutate(access) {
        ownerRefreshFences.remove(ownerUri)
        val current = _index.value
        if (ownerUri !in current.owners) return@mutate
        commit(
            current.copy(
                owners = current.owners - ownerUri,
                ownerOccurrences = current.ownerOccurrences - ownerUri,
                labels = current.labels - ownerUri,
                legacyOwnerUris = current.legacyOwnerUris - ownerUri,
            ),
        )
    }

    // ---------------------------------------------------- what the queue says

    fun onProgress(access: AccountLocalDataCoordinator.Access, trackUri: String, fraction: Float) =
        accounts.locked {
            if (!accounts.isCurrent(access) || !isWanted(trackUri)) return@locked
            _progress.value = _progress.value + (trackUri to fraction.coerceIn(0f, 1f))
            updateOwnerStates()
        }

    fun clearProgress(access: AccountLocalDataCoordinator.Access, trackUri: String) = accounts.locked {
        if (!accounts.isCurrent(access)) return@locked
        _progress.value = _progress.value - trackUri
        updateOwnerStates()
    }

    suspend fun onCompleted(access: AccountLocalDataCoordinator.Access, trackUri: String, record: FileRecord) {
        mutate(access) {
            if (!isWanted(trackUri)) return@mutate
            val current = _index.value
            commit(
                current.copy(
                    files = current.files + (trackUri to record),
                    failures = current.failures - trackUri,
                ),
            )
            _progress.value = _progress.value - trackUri
            updateOwnerStates()
        }
    }

    suspend fun onFailed(access: AccountLocalDataCoordinator.Access, trackUri: String, reason: String) {
        mutate(access) {
            if (!isWanted(trackUri)) return@mutate
            val current = _index.value
            val before = current.failures[trackUri]
            commit(
                current.copy(
                    failures = current.failures + (
                        trackUri to Failure(
                            reason = reason,
                            attempts = (before?.attempts ?: 0) + 1,
                            at = System.currentTimeMillis(),
                        )
                        ),
                ),
            )
            _progress.value = _progress.value - trackUri
            updateOwnerStates()
        }
    }

    /** Forgets past failures so [pending] offers those tracks again. */
    suspend fun retryFailed(access: AccountLocalDataCoordinator.Access) = mutate(access) {
        commit(_index.value.copy(failures = emptyMap()))
    }

    // ------------------------------------------------------------ tidying up

    /**
     * Deletes files, metadata and labels nothing claims any more.
     *
     * This is the other half of the owner model: [removeOwner] only forgets who
     * wanted a track, and this is what notices that nobody does. Deliberately
     * separate, so removing one playlist never deletes a file another playlist
     * is still holding.
     *
     * Runs the synchronous native deletion callback while the global reference check
     * is still locked. Extras must be retained: they can be shared by other tracks.
     */
    suspend fun pruneOrphans(
        access: AccountLocalDataCoordinator.Access,
        deleteAudio: (String) -> Unit,
    ) = mutate(access) { pruneLocked(access, deleteAudio) }

    /** Clear only this account. Never delete the shared native root, sidecars or extras tree. */
    suspend fun clearAll(
        access: AccountLocalDataCoordinator.Access,
        deleteAudio: (String) -> Unit,
    ) = mutate(access) {
        ownerRefreshFences.clear()
        commit(_index.value.copy(owners = emptyMap(), ownerOccurrences = emptyMap(), labels = emptyMap(), legacyOwnerUris = emptyMap()))
        _progress.value = emptyMap()
        pruneLocked(access, deleteAudio)
    }

    private fun pruneLocked(access: AccountLocalDataCoordinator.Access, deleteAudio: (String) -> Unit) {
        val current = _index.value
        val claimed = current.owners.values.flatten().toSet()
        val orphaned = current.files.keys.filterNot(claimed::contains).toSet()
        val others = book.accounts.filterKeys { it != access.namespace }.values + listOfNotNull(book.legacy)
        val retired = mutableSetOf<String>()
        // Include physical records left between the last two namespaces' removals. A namespace
        // may own a pending track without yet having its own completion record.
        for ((uri, record) in book.assets) {
            if (uri in claimed || (uri in current.files && uri !in orphaned)) continue
            val referencedHere = claimed.any { key -> book.assets[key]?.trackId == record.trackId }
            val referenced = referencedHere || others.any { other ->
                uri in other.files || other.owners.values.any { uri in it } ||
                    other.files.values.any { it.trackId == record.trackId } ||
                    other.owners.values.flatten().any { book.assets[it]?.trackId == record.trackId }
            }
            // No suspending/network callback here. The queue must have really joined before GC.
            // Failure retains the physical record for retry; account data and bytes stay recoverable.
            if (!referenced) {
                deleteAudio(uri)
                retired += uri
            }
        }
        val stranded = current.tracks.keys.filterNot(claimed::contains).toSet()
        commit(current.copy(files = current.files - orphaned, tracks = current.tracks - stranded,
            failures = current.failures - stranded), retired)
    }

    suspend fun beginLegacyAssignment(access: AccountLocalDataCoordinator.Access): LegacyAssignment? =
        mutate(access) {
            book.legacy?.let { LegacyAssignment(this, access, legacyFingerprint(it)) }
        }

    /**
     * One atomic commit is the journal: before publication the old pending document wins;
     * after it, the full destination plus consumed decision wins. No file move, deletion,
     * network request, consent or partial multi-file transition is needed on restart.
     */
    suspend fun confirmLegacyAssignment(choice: LegacyAssignment) = mutate(choice.access) {
        require(choice.store === this) { "Legacy choice belongs to another store" }
        val legacy = checkNotNull(book.legacy) { "Legacy downloads already assigned" }
        check(choice.fingerprint == legacyFingerprint(legacy)) { "Legacy downloads changed" }
        val current = _index.value
        val existingFiles = legacy.files.filterValues { audioFile(it.trackId).isFile }
        var merged = current.copy(files = existingFiles + current.files,
            tracks = legacy.tracks + current.tracks, failures = legacy.failures + current.failures)
        for ((originalUri, uris) in legacy.owners) {
            // Archive identifiers preserve provenance and prevent claiming that attribution
            // also granted ongoing Spotify refresh or automatic liked-song downloads.
            val archived = "downloads:legacy:" + digest(originalUri)
            val occurrences = occurrencesFor(legacy, originalUri)
            val label = legacy.labels[originalUri] ?: OwnerLabel(when (originalUri) {
                SINGLES -> appContext.getString(dev.lelonio.square.R.string.downloaded_tracks)
                LIKED -> appContext.getString(dev.lelonio.square.R.string.liked_songs)
                else -> originalUri
            })
            merged = merged.copy(
                owners = merged.owners + (archived to uris),
                ownerOccurrences = merged.ownerOccurrences + (archived to occurrences),
                labels = merged.labels + (archived to label),
                legacyOwnerUris = merged.legacyOwnerUris + (archived to originalUri),
            )
        }
        val next = book.copy(accounts = book.accounts + (choice.access.namespace to merged),
            assets = legacy.files + book.assets, legacy = null, legacyAssignedTo = choice.access.namespace)
        persist(next) // on failure, pending state and memory remain unchanged
        book = next
        updateLegacyStatus()
        accounts.use(choice.access) { publish(merged) }
    }

    private fun legacyFingerprint(index: Index) = digest(json.encodeToString(Index.serializer(), index))
    private fun digest(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun updateLegacyStatus() {
        _legacy.value = book.legacy?.let { LegacyStatus(true, it.owners.size, it.files.size) } ?: LegacyStatus()
    }

    // ------------------------------------------------------------- internals

    private fun occurrencesFor(index: Index, ownerUri: String): List<OwnerOccurrence> =
        index.owners[ownerUri].orEmpty().mapIndexed { position, uri ->
            index.ownerOccurrences[ownerUri]?.getOrNull(position)?.takeIf { it.uri == uri }
                ?: OwnerOccurrence(uri)
        }

    private fun audioFile(trackId: String): File =
        File(File(root, "audio"), trackId.take(2)).resolve(trackId.drop(2))

    private fun publish(index: Index) {
        _ownerStates.value = emptyMap()
        val effective = index.copy(files = index.files.mapValues { (uri, record) -> book.assets[uri] ?: record })
        _index.value = effective
        _files.value = effective.files
        _owners.value = index.owners
        _failures.value = index.failures
        updateOwnerStates()
    }

    private fun updateOwnerStates() {
        val access = accounts.capture()
        if (access == null) { _ownerStates.value = emptyMap(); return }
        val states = _owners.value.keys.associateWith(::ownerState)
        _ownerStates.value = if (accounts.isCurrent(access)) states else emptyMap()
    }

    private fun persist(next: AccountIndex) {
        root.mkdirs()
        persistIndex(indexFile, json.encodeToString(AccountIndex.serializer(), next))
    }

    private fun commit(index: Index, retiredAssets: Set<String> = emptySet()) {
        val access = checkNotNull(accounts.capture())
        val next = book.copy(accounts = book.accounts + (access.namespace to index),
            assets = (book.assets + index.files) - retiredAssets)
        persist(next)
        book = next
        accounts.use(access) { publish(index) }
    }

    companion object {
        /**
         * How many times one track is tried before it is left alone.
         *
         * Reached, it is not an error state to recover from: some tracks are
         * simply not served to this account, and a playlist that stops at 38 of
         * 40 is telling the truth. The listener can ask again from the
         * downloads panel, which is what [retryFailed] is for.
         */
        const val MAX_ATTEMPTS = 5

        const val DIR_NAME = "downloads"
        private const val INDEX_NAME = "index.json"

        /** The owner every single track downloaded on its own belongs to. */
        const val SINGLES = "downloads:tracks"

        /** The owner the automatic download of the liked songs writes to. */
        const val LIKED = "downloads:liked"

        const val KIND_PLAYLIST = "playlist"
        const val KIND_ALBUM = "album"
        const val KIND_ARTIST = "artist"
    }
}

/** How one track should draw itself. */
sealed interface DownloadState {
    /** Not asked for. Draws nothing at all — see [DownloadStore.stateOf]. */
    data object None : DownloadState
    data object Queued : DownloadState
    data class Running(val progress: Float) : DownloadState
    data object Done : DownloadState
    data class Failed(val reason: String) : DownloadState
}

/** How a playlist, album or artist should draw its button. */
sealed interface OwnerState {
    data object None : OwnerState
    data class Running(val progress: Float, val done: Int, val total: Int) : OwnerState
    data object Complete : OwnerState
    /** Everything that could be fetched was; the rest is not available here. */
    data class Partial(val done: Int, val total: Int) : OwnerState
}
