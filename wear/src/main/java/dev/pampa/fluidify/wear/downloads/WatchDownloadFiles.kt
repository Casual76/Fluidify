package dev.pampa.fluidify.wear.downloads

import android.content.Context
import android.os.StatFs
import android.util.Log
import dev.pampa.fluidify.wear.protocol.SpotifyIds
import dev.pampa.fluidify.wear.protocol.WatchDownloadOwner
import dev.pampa.fluidify.wear.protocol.WearCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException

/** A playlist or album kept on the watch, and the tracks it had when last read. */
@Serializable
data class KeptOwner(
    val uri: String,
    val title: String,
    val tracks: List<String> = emptyList(),
    val artUrl: String? = null,
    val addedAtEpochMs: Long = 0,
)

/**
 * What the watch keeps for offline listening, on its own disk.
 *
 * The files are the native store's (native/src/downloads.rs), laid out the
 * same way as on the phone: `audio/<xx>/<rest>` is the CDN file still
 * encrypted, `meta/<xx>/<rest>.json` its sidecar with the key. The engine reads
 * them when it plays; this class reads them to know what is there, and writes
 * them when a file arrives from the phone over Bluetooth — a byte-for-byte copy
 * of the phone's own download, which is why the watch never has to ask Spotify
 * for a key for it.
 *
 * Which playlists and albums to keep is the watch's list ([owners]), edited
 * from the wrist or the phone.
 *
 * Everything here touches the disk (a list of owners is a file, a track is two), so none of it
 * belongs on the main thread: the screens go through [WatchDownloads.edits].
 */
internal interface DownloadFileAccess {
    fun <T> use(block: () -> T): T
}

/** Immutable account directory. Every file operation is fenced by the captured capability. */
class WatchDownloadFiles internal constructor(
    val root: File,
    private val failures: android.content.SharedPreferences,
    private val access: DownloadFileAccess,
) {

    init { root.mkdirs() }
    private val ownersFile = File(root, "owners.json")
    private class Shared(val owners: MutableStateFlow<List<KeptOwner>>) {
        val failed = MutableStateFlow(false)
        val presence = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    }
    private val shared = synchronized(states) {
        states.getOrPut(root.absolutePath) { Shared(MutableStateFlow(load())) }.also { state ->
            synchronized(state) { state.owners.value = load(); state.presence.clear() }
        }
    }
    private val _owners = shared.owners

    val owners: StateFlow<List<KeptOwner>> = _owners.asStateFlow()

    private val _saveFailed = shared.failed

    /** True while the list of owners could not be written to the disk (full, most likely). */
    val saveFailed: StateFlow<Boolean> = _saveFailed.asStateFlow()

    private fun isKeptImpl(uri: String): Boolean = _owners.value.any { it.uri == uri }

    /** Whether [uri] is kept and every one of its tracks is on the disk: it can be played without asking anyone. */
    private fun isCompleteImpl(uri: String): Boolean {
        val owner = _owners.value.firstOrNull { it.uri == uri } ?: return false
        return owner.tracks.isNotEmpty() && owner.tracks.all(::has)
    }

    private fun keepImpl(uri: String, title: String, artUrl: String? = null) {
        if (isKept(uri)) return
        save(_owners.value + KeptOwner(uri, title, artUrl = artUrl, addedAtEpochMs = System.currentTimeMillis()))
    }

    /** Stops keeping [uri], and deletes the tracks no other kept owner has. */
    private fun dropImpl(uri: String) {
        val leaving = _owners.value.firstOrNull { it.uri == uri } ?: return
        val remaining = _owners.value - leaving
        val stillWanted = remaining.flatMap { it.tracks }.toSet()
        // The list first: the switch that asked is already off, and a stop half way through the
        // deleting leaves files nobody wants, which is better than a list that wants files gone.
        save(remaining)
        deleteAll(leaving.tracks.filterNot { it in stillWanted })
    }

    /** The owner's tracks as read now; tracks taken out of the playlist go from the disk too. */
    private fun setTracksImpl(uri: String, tracks: List<String>, title: String? = null) {
        val owner = _owners.value.firstOrNull { it.uri == uri } ?: return
        val removed = owner.tracks.toSet() - tracks.toSet()
        val updated = _owners.value.map { if (it.uri == uri) it.copy(tracks = tracks, title = title ?: it.title) else it }
        val stillWanted = updated.flatMap { it.tracks }.toSet()
        save(updated)
        deleteAll(removed.filterNot { it in stillWanted })
    }

    /** Every kept track not yet on the disk, in the order the owners were added. */
    private fun pendingImpl(): List<String> =
        _owners.value.flatMap { it.tracks }.distinct().filterNot { has(it) || unavailable(it) }

    private fun unavailableImpl(uri: String): Boolean = failures.getInt(uri, 0) >= MAX_FAILURES
    private fun recordFailureImpl(uri: String) {
        failures.edit().putInt(uri, (failures.getInt(uri, 0) + 1).coerceAtMost(MAX_FAILURES)).apply()
    }
    private fun clearFailureImpl(uri: String) { clearFailures(listOf(uri)) }

    /** Forgets the failures of [uris] in one edit: one write, not one per track. */
    private fun clearFailures(uris: Collection<String>) {
        if (uris.isEmpty()) return
        val editor = failures.edit()
        uris.forEach { editor.remove(it) }
        editor.apply()
    }
    private fun unavailableCountImpl(): Int = _owners.value.flatMap { it.tracks }.distinct().count { !has(it) && unavailable(it) }

    /**
     * Which tracks are on the disk, as far as this store has looked. The status of the downloads is
     * worked out again at each step of the queue, and each look was two calls to the file system
     * for every track of every kept list: a queue of a thousand tracks did millions. A look is now
     * kept until the store itself changes the track ([adopt], [delete]) or whoever knows the files
     * moved under it — the engine writes its own downloads — says so ([invalidate]).
     */
    private val presence = shared.presence

    private fun hasImpl(trackUri: String): Boolean =
        presence.getOrPut(trackUri) { metaFile(trackUri)?.isFile == true && audioFile(trackUri)?.isFile == true }

    /** Looks at the disk again for [trackUri] next time: its files may have been written or removed by the engine. */
    private fun invalidateImpl(trackUri: String) {
        presence.remove(trackUri)
    }

    /** Looks at the disk again for every track: the start of a pass of the queue. */
    private fun invalidateAllImpl() {
        presence.clear()
    }

    private fun sidecarImpl(trackUri: String): JsonObject? = runCatching {
        val file = metaFile(trackUri)?.takeIf { it.isFile } ?: return null
        WearCodec.json.parseToJsonElement(file.readText()).jsonObject
    }.getOrNull()

    /** The kbps of a stored track's format, read from its sidecar ("OGG_VORBIS_160" is 160). */
    fun kbpsOf(sidecar: JsonObject?): Int? = formatKbps(sidecar?.get("format")?.jsonPrimitive?.contentOrNull)

    private fun audioFileImpl(trackUri: String): File? = shard("audio", trackUri, "")
    private fun partFileImpl(trackUri: String): File? = audioFile(trackUri)?.let { File(it.path + ".part") }
    private fun partIdentityFileImpl(trackUri: String): File? = partFile(trackUri)?.let { File(it.path + ".id") }
    private fun metaFileImpl(trackUri: String): File? = shard("meta", trackUri, ".json")

    /**
     * Files a track that arrived whole: the `.part` becomes the audio file and the
     * sidecar is written last, through a temporary file — its presence is the one
     * signal the engine trusts that a download is complete.
     */
    private fun adoptImpl(trackUri: String, sidecarJson: String): Boolean {
        val part = partFile(trackUri) ?: return false
        val audio = audioFile(trackUri) ?: return false
        val meta = metaFile(trackUri) ?: return false
        if (!part.isFile) return false
        audio.parentFile?.mkdirs()
        if (!part.renameTo(audio)) return false
        meta.parentFile?.mkdirs()
        val tmp = File(meta.path + ".tmp")
        try {
            tmp.writeText(sidecarJson)
        } catch (full: IOException) {
            // No room for even the sidecar: the audio without it is not a download, and is space.
            Log.w(TAG, "sidecar not written for $trackUri: ${full.message}")
            tmp.delete()
            audio.delete()
            return false
        }
        return tmp.renameTo(meta).also {
            if (it) {
                partIdentityFile(trackUri)?.delete()
                clearFailure(trackUri)
                presence[trackUri] = true
            }
        }
    }

    private fun deleteImpl(trackUri: String) {
        deleteAll(listOf(trackUri))
    }

    /** The files of every track in [uris], and what was remembered of their failures, in one go. */
    private fun deleteAll(uris: Collection<String>) {
        for (trackUri in uris) {
            metaFile(trackUri)?.delete()
            audioFile(trackUri)?.delete()
            partFile(trackUri)?.delete()
            partIdentityFile(trackUri)?.delete()
            presence.remove(trackUri)
        }
        clearFailures(uris)
    }

    /**
     * The kept tracks of [ownerUri] that are on the disk, read from their sidecars:
     * what the watch plays when there is no session to read the playlist through.
     */
    private fun offlineTracksImpl(ownerUri: String): List<dev.lelonio.square.data.CatalogTrack> {
        val owner = _owners.value.firstOrNull { it.uri == ownerUri } ?: return emptyList()
        return owner.tracks.filter(::has).mapNotNull { uri ->
            val sidecar = sidecar(uri) ?: return@mapNotNull null
            val text = { key: String -> sidecar[key]?.jsonPrimitive?.contentOrNull }
            val artists = runCatching {
                (sidecar["artists"] as? kotlinx.serialization.json.JsonArray).orEmpty().map { element ->
                    val artist = element.jsonObject
                    dev.lelonio.square.data.CatalogArtist(
                        artist["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        artist["uri"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() },
                    )
                }
            }.getOrDefault(emptyList())
            dev.lelonio.square.data.CatalogTrack(
                uri = uri,
                name = text("name").orEmpty(),
                artist = artists.joinToString(", ") { it.name },
                artistUri = artists.firstOrNull()?.uri,
                artists = artists,
                album = text("album").orEmpty(),
                durationMs = text("durationMs")?.toLongOrNull() ?: 0,
                artworkUrl = text("coverUrl")?.takeIf { it.isNotEmpty() },
            )
        }
    }

    private fun ownerStatusImpl(): List<WatchDownloadOwner> = _owners.value.map { owner ->
        WatchDownloadOwner(owner.uri, owner.title, tracks = owner.tracks.size, done = owner.tracks.count(::has), unavailable = owner.tracks.count { !has(it) && unavailable(it) })
    }

    private fun bytesUsedImpl(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun bytesFreeImpl(): Long = runCatching { StatFs(root.path).availableBytes }.getOrDefault(0L)

    private fun shard(kind: String, trackUri: String, suffix: String): File? {
        val hex = SpotifyIds.trackHex(trackUri) ?: return null
        return File(File(File(root, kind), hex.substring(0, 2)), hex.substring(2) + suffix)
    }

    private fun load(): List<KeptOwner> = runCatching {
        if (!ownersFile.isFile) return emptyList()
        WearCodec.json.decodeFromString(ListSerializer(KeptOwner.serializer()), ownersFile.readText())
    }.getOrDefault(emptyList())

    /**
     * The list in memory at once (the screens follow it), then to the disk through a temporary
     * file. A disk that refuses the write (full) used to throw out of here, into whatever called —
     * a switch on the main thread — and is now said by [saveFailed]; the list stays right in
     * memory, and the next write that fits puts all of it on disk.
     */
    private fun save(owners: List<KeptOwner>) {
        val tmp = File(ownersFile.path + ".tmp")
        try {
            tmp.writeText(WearCodec.json.encodeToString(ListSerializer(KeptOwner.serializer()), owners))
            check(tmp.renameTo(ownersFile)) { "Kept list not committed" }
            _saveFailed.value = false
            _owners.value = owners
            onChanged?.invoke()
        } catch (failed: Exception) {
            tmp.delete()
            _saveFailed.value = true
            onChanged?.invoke()
            throw failed
        }
    }
    internal var onChanged: (() -> Unit)? = null
    internal fun importLegacy(legacy: List<KeptOwner>): Unit = use {
        val existing = _owners.value.map { it.uri }.toSet()
        save(_owners.value + legacy.filterNot { it.uri in existing })
    }

    fun isKept(uri: String): Boolean = use { isKeptImpl(uri) }
    fun isComplete(uri: String): Boolean = use { isCompleteImpl(uri) }
    fun keep(uri: String, title: String, artUrl: String? = null): Unit = use { keepImpl(uri, title, artUrl) }
    fun drop(uri: String): Unit = use { dropImpl(uri) }
    fun setTracks(uri: String, tracks: List<String>, title: String? = null): Unit = use { setTracksImpl(uri, tracks, title) }
    fun pending(): List<String> = use { pendingImpl() }
    fun unavailable(uri: String): Boolean = use { unavailableImpl(uri) }
    fun recordFailure(uri: String): Unit = use { recordFailureImpl(uri) }
    fun clearFailure(uri: String): Unit = use { clearFailureImpl(uri) }
    fun unavailableCount(): Int = use { unavailableCountImpl() }
    fun has(trackUri: String): Boolean = use { hasImpl(trackUri) }
    fun invalidate(trackUri: String): Unit = use { invalidateImpl(trackUri) }
    fun invalidateAll(): Unit = use { invalidateAllImpl() }
    fun sidecar(trackUri: String): JsonObject? = use { sidecarImpl(trackUri) }
    fun audioFile(trackUri: String): File? = use { audioFileImpl(trackUri) }
    fun partFile(trackUri: String): File? = use { partFileImpl(trackUri) }
    fun partIdentityFile(trackUri: String): File? = use { partIdentityFileImpl(trackUri) }
    fun metaFile(trackUri: String): File? = use { metaFileImpl(trackUri) }
    fun adopt(trackUri: String, sidecarJson: String): Boolean = use { adoptImpl(trackUri, sidecarJson) }
    fun delete(trackUri: String): Unit = use { deleteImpl(trackUri) }
    fun offlineTracks(ownerUri: String): List<dev.lelonio.square.data.CatalogTrack> = use { offlineTracksImpl(ownerUri) }
    fun ownerStatus(): List<WatchDownloadOwner> = use { ownerStatusImpl() }
    fun bytesUsed(): Long = use { bytesUsedImpl() }
    fun bytesFree(): Long = use { bytesFreeImpl() }

    private fun <T> use(block: () -> T): T = access.use { synchronized(shared) { block() } }

    companion object {
        private val states = mutableMapOf<String, Shared>()
        private const val TAG = "WatchDownloadStore"
        const val MAX_FAILURES = 3
        /** "OGG_VORBIS_320" → 320; the formats without a number are the phone's MP3/AAC, which a watch never asks for. */
        fun formatKbps(format: String?): Int? = format?.substringAfterLast('_')?.toIntOrNull()
    }
}
