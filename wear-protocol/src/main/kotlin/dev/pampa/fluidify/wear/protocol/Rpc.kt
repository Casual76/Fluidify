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

    /** What the phone has downloaded of these tracks. Answers [PhoneDownloads]. */
    @Serializable @SerialName("phone-downloads")
    data class Downloads(val uris: List<String>) : RpcMethod

    /**
     * Which of these songs are in Liked Songs: for the heart while the watch plays on its own,
     * which has no way to read the library by itself. Answers [LikedAnswer].
     */
    @Serializable @SerialName("liked")
    data class Liked(val uris: List<String>) : RpcMethod
}

/**
 * What the phone allows a watch's question to ask for.
 *
 * The watch is trusted to be a Fluidify, not to be right: a request is a few bytes anyone paired
 * with the phone could write, and a negative count that reaches `take()` is an exception on the
 * phone, an enormous one is work and memory the phone spends for nobody. [sanitised] is applied
 * to every request before it is answered, so the handlers can rely on the numbers.
 */
object RpcLimits {
    /** The most tracks of a context one question may ask for. */
    const val MAX_CONTEXT_LIMIT = 200

    /** A search of this many characters is already longer than any title. */
    const val MAX_QUERY_LENGTH = 200

    /** The most queue entries either side of what is playing. */
    const val MAX_QUEUE_SIDE = 100

    /** The most songs one question about downloads or hearts may name. */
    const val MAX_URIS = 200

    fun sanitised(method: RpcMethod): RpcMethod = when (method) {
        is RpcMethod.Queue -> RpcMethod.Queue(
            before = method.before.coerceIn(0, MAX_QUEUE_SIDE),
            after = method.after.coerceIn(0, MAX_QUEUE_SIDE),
        )
        is RpcMethod.Context -> method.copy(
            offset = method.offset.coerceAtLeast(0),
            limit = method.limit.coerceIn(0, MAX_CONTEXT_LIMIT),
        )
        is RpcMethod.Search -> method.copy(query = method.query.take(MAX_QUERY_LENGTH))
        is RpcMethod.Downloads -> method.copy(uris = method.uris.take(MAX_URIS))
        is RpcMethod.Liked -> method.copy(uris = method.uris.take(MAX_URIS))
        RpcMethod.Devices, RpcMethod.Home, is RpcMethod.Library -> method
    }
}

/** The songs asked about that are in Liked Songs, and those that are not; one the phone could not tell is in neither. */
@Serializable
data class LikedAnswer(
    val liked: List<String> = emptyList(),
    val notLiked: List<String> = emptyList(),
)

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
    /** Pinned to the top of the library on the phone. */
    val pinned: Boolean = false,
    /**
     * For a playlist: whether the account may add tracks to it (false for one it only follows).
     * Null when the phone did not say, which the watch treats as "try".
     */
    val editable: Boolean? = null,
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
    val unavailableReason: String? = null,
)
