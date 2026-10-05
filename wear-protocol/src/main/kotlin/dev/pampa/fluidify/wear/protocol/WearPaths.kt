package dev.pampa.fluidify.wear.protocol

/**
 * Every path the phone and the watch use on the Wearable Data Layer.
 *
 * The `1` in most of them is [ProtocolVersion.MAJOR]. Two families deliberately
 * carry no version at all: [HELLO] and the [UPDATE_OFFER] / [UPDATE_STATUS] /
 * [UPDATE_APK] trio, with the [UPDATE_REQUEST] that asks for it. Those are how two sides that
 * disagree about everything else find out that they disagree, and how the newer one fixes it. If they moved
 * with the major, a watch two versions behind would stop hearing the very
 * message that offers to bring it up to date.
 *
 * Durable work starts with [PREFIX], which both listener services filter on.
 * Ephemeral visual frames deliberately use a separate, foreground-only path.
 */
object WearPaths {
    const val PREFIX = "/fluidify"

    // --- Unversioned: compatibility and self-update ------------------------------

    /** Message, both ways. [Hello]. */
    const val HELLO = "$PREFIX/hello"

    /** Message, phone to watch. An APK is ready to be sent. */
    const val UPDATE_OFFER = "$PREFIX/update/offer"

    /** Message, watch to phone. Accepted, declined, progress, outcome. */
    const val UPDATE_STATUS = "$PREFIX/update/status"

    /** Channel, opened by the watch; the phone streams the APK into it. */
    const val UPDATE_APK = "$PREFIX/update/apk"

    /**
     * Message, both ways. Watch to phone: "check for an update now", the watch's own "Check for
     * updates" row, carrying the watch's [Hello] so that a phone that has just been woken knows
     * which version to compare with. Phone to watch: the [UpdateCheckReply]. Unversioned like the
     * rest of the family, for the same reason; a phone from before it logs the path as unknown and
     * never answers, which the watch reads as "phone not reachable" after a few seconds.
     */
    const val UPDATE_REQUEST = "$PREFIX/update/request"

    // --- Versioned -----------------------------------------------------------------

    private const val V = "$PREFIX/${ProtocolVersion.MAJOR}"
    const val AUDIO_LIGHT_SUBSCRIBE = "$V/audio-light/subscribe"
    // Outside PREFIX so frames cannot wake a WearableListenerService.
    const val AUDIO_LIGHT_FRAME = "/visual-fluidify/${ProtocolVersion.MAJOR}/frame"

    /** DataItem, phone to watch, urgent. The current [PlaybackSnapshot]. */
    const val STATE = "$V/state"

    /** DataItem prefix, phone to watch. One per cover: `art/<artKey>` holding the image asset. */
    const val ART_PREFIX = "$V/art/"

    /** DataItem, phone to watch, not urgent. The instant library snapshot. */
    const val LIBRARY = "$V/library"

    /** DataItem, phone to watch. Who is signed in, so a watch offline at logout still learns of it. */
    const val ACCOUNT = "$V/account"

    /** DataItem, phone to watch. The phone's version, features and download quality. */
    const val PHONE = "$V/phone"

    /** DataItem, watch to phone. [WatchSurfaces]: how the watch wants the phone's notifications. */
    const val WATCH = "$V/watch"

    /** Message, phone to watch. A [DownloadRequest]: keep, or stop keeping, a playlist or album. */
    const val DOWNLOAD_PLAN = "$V/dl/plan"

    /** DataItem, watch to phone. [WatchDownloads]: what the watch has, and what it is working on. */
    const val DOWNLOAD_STATUS = "$V/dl/status"

    /** Channel, opened by the watch. A [FileRequest] line in; a [FileHeader] line and the bytes out. */
    const val DOWNLOAD_FILE = "$V/dl/file"

    /** Message, watch to phone. A [CommandEnvelope]. */
    const val COMMAND = "$V/cmd"

    /** Message, phone to watch. A [CommandAck]. */
    const val ACK = "$V/ack"

    /** Message, watch to phone. An [RpcRequest]. */
    const val RPC = "$V/rpc"

    /** Message, phone to watch. An [RpcResponse] small enough for a message. */
    const val RPC_REPLY = "$V/rpc/reply"

    /** Channel prefix, opened by the phone, for replies too big for a message: `rpc/stream/<id>`. */
    const val RPC_STREAM_PREFIX = "$V/rpc/stream/"

    /** Channel, opened by the watch. A list of thumbnail keys in, a pack of images out. */
    const val THUMBS = "$V/thumbs"

    /** Message, watch to phone. Asks for something to log in with. Never a DataItem: those persist. */
    const val AUTH_REQUEST = "$V/auth/request"

    /** Message, phone to watch. The answer to [AUTH_REQUEST]. */
    const val AUTH_GRANT = "$V/auth/grant"

    /** Message, phone to watch. The phone signed out. */
    const val AUTH_LOGOUT = "$V/auth/logout"

    /** Message, phone to watch. Carry on playing here. */
    const val HANDOFF_TO_WATCH = "$V/handoff/to-watch"

    /** Message, watch to phone. Carry on playing there. */
    const val HANDOFF_TO_PHONE = "$V/handoff/to-phone"

    /** The DataItem path for one cover. */
    fun art(artKey: String): String = ART_PREFIX + artKey

    /** The channel path for one large RPC reply. */
    fun rpcStream(id: Long): String = RPC_STREAM_PREFIX + id

    /** The Data Layer capability the phone app advertises (res/values/wear.xml). */
    const val CAPABILITY_PHONE = "fluidify_phone"

    /** The Data Layer capability the watch app advertises (res/values/wear.xml). */
    const val CAPABILITY_WATCH = "fluidify_watch"
}
