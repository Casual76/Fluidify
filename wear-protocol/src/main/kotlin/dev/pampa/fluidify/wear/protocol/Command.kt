package dev.pampa.fluidify.wear.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Something the watch asks the phone to do.
 *
 * Every command is fire-and-acknowledge: the watch applies it to its own copy of
 * the state at once, so the button answers the finger, and the phone's
 * [CommandAck] confirms it or takes it back. Values are absolute wherever they
 * can be (a position, a volume level, a shuffle state) rather than deltas, so a
 * command delivered twice does no harm.
 */
@Serializable
sealed interface Command {

    @Serializable @SerialName("play")
    data object Play : Command

    @Serializable @SerialName("pause")
    data object Pause : Command

    @Serializable @SerialName("toggle")
    data object TogglePlay : Command

    @Serializable @SerialName("next")
    data object Next : Command

    @Serializable @SerialName("prev")
    data object Previous : Command

    @Serializable @SerialName("seek")
    data class SeekTo(val positionMs: Long) : Command

    @Serializable @SerialName("shuffle")
    data class SetShuffle(val enabled: Boolean) : Command

    @Serializable @SerialName("repeat")
    data class SetRepeat(val mode: RepeatMode) : Command

    @Serializable @SerialName("like")
    data class SetLiked(val uri: String, val liked: Boolean) : Command

    /** [level] is 0..1. [deviceId] null means whatever is playing now. */
    @Serializable @SerialName("vol")
    data class SetVolume(val level: Float, val deviceId: String? = null) : Command

    @Serializable @SerialName("playctx")
    data class PlayContext(
        val contextUri: String,
        val startTrackUri: String? = null,
        val shuffle: Boolean = false,
        val label: String = "",
    ) : Command

    @Serializable @SerialName("playidx")
    data class PlayQueueIndex(val index: Int, val uri: String) : Command

    @Serializable @SerialName("enqueue")
    data class AddToQueue(val uri: String) : Command

    @Serializable @SerialName("radio")
    data class StartRadio(val seedUri: String) : Command

    @Serializable @SerialName("transfer")
    data class Transfer(val deviceId: String) : Command

    /** Exactly one of the three: [minutes], [atTrackEnd] or [cancel]. */
    @Serializable @SerialName("sleep")
    data class SleepTimer(
        val minutes: Int? = null,
        val atTrackEnd: Boolean = false,
        val cancel: Boolean = false,
    ) : Command
}

@Serializable
data class CommandEnvelope(
    val id: Long,
    val command: Command,
)

@Serializable
data class CommandAck(
    val id: Long,
    val ok: Boolean,
    val error: String? = null,
    /** The first snapshot seq that already reflects this command, when there is one. */
    val appliedSeq: Long? = null,
)

/**
 * The reasons a command can fail, as [CommandAck.error] carries them.
 *
 * The watch turns each into a sentence on its screen, so an unknown one still shows something
 * ("did not work") rather than nothing — which is what a silent failure looked like before.
 */
object AckErrors {
    /** The phone could not be reached, or did not answer in time. Never sent by a phone. */
    const val UNREACHABLE = "unreachable"

    /** The phone's player could not be started. */
    const val PHONE_UNAVAILABLE = "phone-unavailable"

    /** The device did not take the playback. */
    const val TRANSFER = "transfer"

    /** Nothing loaded to skip in (the account's last song, not playing anywhere). */
    const val NOTHING_TO_SKIP = "nothing-to-skip"

    /** A radio could not be made from this song. */
    const val RADIO = "radio"

    /** The heart could not be set. */
    const val LIKE = "like"

    /** The playlist or album could not be played. */
    const val CONTEXT = "context"

    const val NOT_FOUND = "not-found"
    const val EMPTY = "empty"
    const val NOT_IN_QUEUE = "not-in-queue"
    const val UNSUPPORTED = "unsupported"
}

