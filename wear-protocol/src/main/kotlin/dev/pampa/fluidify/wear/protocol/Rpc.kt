package dev.pampa.fluidify.wear.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A question the watch asks the phone and waits for: the queue, the devices, a
 * page of the library.
 *
 * Unlike a [Command], which changes something and is acknowledged, a request
 * changes nothing and is answered with data ([RpcResponse.payload], decoded with
 * the serializer the method names). Small answers come back as a message; ones
 * too big for a message ([RPC_MESSAGE_LIMIT]) over a channel.
 */
@Serializable
sealed interface RpcMethod {

    /** The queue around what is playing: [before] items back, [after] ahead. Answers [QueueWindow]. */
    @Serializable @SerialName("queue")
    data class Queue(val before: Int = 3, val after: Int = 60) : RpcMethod

    /** The Spotify Connect devices the phone can see. Answers [DeviceList]. */
    @Serializable @SerialName("devices")
    data object Devices : RpcMethod

    /** The home page. Answers [LibraryPage]. */
    @Serializable @SerialName("home")
    data object Home : RpcMethod

    /** One section of the library. Answers [LibraryPage]. */
    @Serializable @SerialName("library")
    data class Library(val section: LibrarySection) : RpcMethod

    /** The tracks of a playlist, album, artist or Liked Songs. Answers [ContextPage]. */
    @Serializable @SerialName("context")
    data class Context(val uri: String, val offset: Int = 0, val limit: Int = 100) : RpcMethod

    /** A search. Answers [LibraryPage] with tracks first. */
    @Serializable @SerialName("search")
    data class Search(val query: String) : RpcMethod
}

@Serializable
data class RpcRequest(val id: Long, val method: RpcMethod)

@Serializable
data class RpcResponse(
    val id: Long,
    val ok: Boolean,
    val error: String? = null,
    val payload: JsonElement? = null,
)

/** Larger answers go over a channel instead of a message. */
const val RPC_MESSAGE_LIMIT = 90 * 1024

@Serializable
data class QueueEntry(
    val index: Int,
    val uri: String,
    val title: String,
    val artist: String = "",
    val artKey: String? = null,
    val artUrl: String? = null,
)

@Serializable
data class QueueWindow(
    val currentIndex: Int,
    val total: Int,
    val items: List<QueueEntry>,
)

@Serializable
data class DeviceList(
    val devices: List<DeviceInfo>,
    val activeId: String? = null,
)

@Serializable
enum class LibrarySection { PLAYLISTS, ALBUMS, ARTISTS, DOWNLOADS, RECENT }

/** One row of a library list: a playlist, an album, an artist, a track. */
@Serializable
data class LibraryItem(
    val uri: String,
    val title: String,
    val subtitle: String = "",
    val kind: LibraryKind = LibraryKind.PLAYLIST,
    val artKey: String? = null,
    val artUrl: String? = null,
)

@Serializable
enum class LibraryKind { PLAYLIST, ALBUM, ARTIST, TRACK, LIKED, SHELF }

/** A titled group of items: a home shelf, a library section, search results. */
@Serializable
data class LibraryShelf(
    val title: String,
    val items: List<LibraryItem>,
)

@Serializable
data class LibraryPage(
    val shelves: List<LibraryShelf>,
    /** When the phone cannot answer this section (e.g. it needs the user's Web API app). */
    val unavailableReason: String? = null,
)

@Serializable
data class ContextPage(
    val uri: String,
    val title: String,
    val subtitle: String = "",
    val artKey: String? = null,
    val artUrl: String? = null,
    val tracks: List<LibraryItem>,
    val total: Int = tracks.size,
)
