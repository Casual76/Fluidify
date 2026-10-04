package dev.pampa.fluidify.wear.downloads

import android.content.Context
import android.os.StatFs
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
 */
class WatchDownloadStore(context: Context) {

    val root: File = File(context.filesDir, "downloads").apply { mkdirs() }
    private val ownersFile = File(root, "owners.json")
    private val failures = context.getSharedPreferences("watch_download_failures", Context.MODE_PRIVATE)
    private val _owners = MutableStateFlow(load())

    val owners: StateFlow<List<KeptOwner>> = _owners.asStateFlow()

    fun isKept(uri: String): Boolean = _owners.value.any { it.uri == uri }

    @Synchronized fun keep(uri: String, title: String, artUrl: String? = null) {
        if (isKept(uri)) return
        save(_owners.value + KeptOwner(uri, title, artUrl = artUrl, addedAtEpochMs = System.currentTimeMillis()))
    }

    /** Stops keeping [uri], and deletes the tracks no other kept owner has. */
    @Synchronized fun drop(uri: String) {
        val leaving = _owners.value.firstOrNull { it.uri == uri } ?: return
        val remaining = _owners.value - leaving
        val stillWanted = remaining.flatMap { it.tracks }.toSet()
        leaving.tracks.filterNot { it in stillWanted }.forEach(::delete)
        save(remaining)
    }

    /** The owner's tracks as read now; tracks taken out of the playlist go from the disk too. */
    @Synchronized fun setTracks(uri: String, tracks: List<String>, title: String? = null) {
        val owner = _owners.value.firstOrNull { it.uri == uri } ?: return
        val removed = owner.tracks.toSet() - tracks.toSet()
        val updated = _owners.value.map { if (it.uri == uri) it.copy(tracks = tracks, title = title ?: it.title) else it }
        val stillWanted = updated.flatMap { it.tracks }.toSet()
        removed.filterNot { it in stillWanted }.forEach(::delete)
        save(updated)
    }

    /** Every kept track not yet on the disk, in the order the owners were added. */
    fun pending(): List<String> =
        _owners.value.flatMap { it.tracks }.distinct().filterNot { has(it) || unavailable(it) }

    fun unavailable(uri: String): Boolean = failures.getInt(uri, 0) >= MAX_FAILURES
    @Synchronized fun recordFailure(uri: String) {
        failures.edit().putInt(uri, (failures.getInt(uri, 0) + 1).coerceAtMost(MAX_FAILURES)).commit()
    }
    @Synchronized fun clearFailure(uri: String) { failures.edit().remove(uri).commit() }
    fun unavailableCount(): Int = _owners.value.flatMap { it.tracks }.distinct().count { !has(it) && unavailable(it) }

    fun has(trackUri: String): Boolean = metaFile(trackUri)?.isFile == true && audioFile(trackUri)?.isFile == true

    fun sidecar(trackUri: String): JsonObject? = runCatching {
        val file = metaFile(trackUri)?.takeIf { it.isFile } ?: return null
        WearCodec.json.parseToJsonElement(file.readText()).jsonObject
    }.getOrNull()

    /** The kbps of a stored track's format, read from its sidecar ("OGG_VORBIS_160" is 160). */
    fun kbpsOf(sidecar: JsonObject?): Int? = formatKbps(sidecar?.get("format")?.jsonPrimitive?.contentOrNull)

    fun audioFile(trackUri: String): File? = shard("audio", trackUri, "")
    fun partFile(trackUri: String): File? = audioFile(trackUri)?.let { File(it.path + ".part") }
    fun partIdentityFile(trackUri: String): File? = partFile(trackUri)?.let { File(it.path + ".id") }
    fun metaFile(trackUri: String): File? = shard("meta", trackUri, ".json")

    /**
     * Files a track that arrived whole: the `.part` becomes the audio file and the
     * sidecar is written last, through a temporary file — its presence is the one
     * signal the engine trusts that a download is complete.
     */
    @Synchronized fun adopt(trackUri: String, sidecarJson: String): Boolean {
        val part = partFile(trackUri) ?: return false
        val audio = audioFile(trackUri) ?: return false
        val meta = metaFile(trackUri) ?: return false
        if (!part.isFile) return false
        audio.parentFile?.mkdirs()
        if (!part.renameTo(audio)) return false
        meta.parentFile?.mkdirs()
        val tmp = File(meta.path + ".tmp")
        tmp.writeText(sidecarJson)
        return tmp.renameTo(meta).also { if (it) { partIdentityFile(trackUri)?.delete(); clearFailure(trackUri) } }
    }

    @Synchronized fun delete(trackUri: String) {
        metaFile(trackUri)?.delete()
        audioFile(trackUri)?.delete()
        partFile(trackUri)?.delete()
        partIdentityFile(trackUri)?.delete()
        clearFailure(trackUri)
    }

    /**
     * The kept tracks of [ownerUri] that are on the disk, read from their sidecars:
     * what the watch plays when there is no session to read the playlist through.
     */
    fun offlineTracks(ownerUri: String): List<dev.lelonio.square.data.CatalogTrack> {
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

    fun ownerStatus(): List<WatchDownloadOwner> = _owners.value.map { owner ->
        WatchDownloadOwner(owner.uri, owner.title, tracks = owner.tracks.size, done = owner.tracks.count(::has), unavailable = owner.tracks.count { !has(it) && unavailable(it) })
    }

    fun bytesUsed(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun bytesFree(): Long = runCatching { StatFs(root.path).availableBytes }.getOrDefault(0L)

    private fun shard(kind: String, trackUri: String, suffix: String): File? {
        val hex = SpotifyIds.trackHex(trackUri) ?: return null
        return File(File(File(root, kind), hex.substring(0, 2)), hex.substring(2) + suffix)
    }

    private fun load(): List<KeptOwner> = runCatching {
        if (!ownersFile.isFile) return emptyList()
        WearCodec.json.decodeFromString(ListSerializer(KeptOwner.serializer()), ownersFile.readText())
    }.getOrDefault(emptyList())

    private fun save(owners: List<KeptOwner>) {
        _owners.value = owners
        val tmp = File(ownersFile.path + ".tmp")
        tmp.writeText(WearCodec.json.encodeToString(ListSerializer(KeptOwner.serializer()), owners))
        tmp.renameTo(ownersFile)
    }

    companion object {
        const val MAX_FAILURES = 3
        /** "OGG_VORBIS_320" → 320; the formats without a number are the phone's MP3/AAC, which a watch never asks for. */
        fun formatKbps(format: String?): Int? = format?.substringAfterLast('_')?.toIntOrNull()
    }
}
