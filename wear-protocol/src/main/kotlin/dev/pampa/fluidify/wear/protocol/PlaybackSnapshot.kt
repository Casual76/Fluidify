package dev.pampa.fluidify.wear.protocol

import kotlinx.serialization.Serializable

/**
 * What is playing, as the phone sees it, at one instant.
 *
 * Sent only when something changes, never on a timer. The position is the one
 * value that changes on its own, and it is never sent as a stream: the phone
 * says where the song was at [sampledAtEpochMs] and how fast it is moving, and
 * the watch works out the rest ([dev.pampa.fluidify.wear.protocol.logic.PositionExtrapolator]).
 * That is what lets a three-minute song cost one message instead of a hundred
 * and eighty, which on a watch is the difference between a remote and a drain.
 *
 * [seq] grows with every snapshot the phone builds. A command's ack carries the
 * seq that already reflects it, so the watch knows when to stop trusting its own
 * optimistic guess.
 */
@Serializable
data class PlaybackSnapshot(
    val seq: Long,
    val sentAtEpochMs: Long,
    val source: PlaybackSource = PlaybackSource.NONE,
    val track: TrackInfo? = null,
    val positionMs: Long = 0,
    val sampledAtEpochMs: Long = 0,
    val speed: Float = 1f,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val buffering: Boolean = false,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    /** Null when the phone does not know yet; the heart is drawn neither filled nor empty. */
    val liked: Boolean? = null,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val context: ContextInfo? = null,
    val device: DeviceInfo? = null,
    val sleep: SleepInfo? = null,
    val offline: Boolean = false,
    /** The cover the phone expects to need next, sent ahead so a skip shows it at once. */
    val nextArtKey: String? = null,
)

@Serializable
enum class PlaybackSource {
    /** Nothing loaded. */
    NONE,

    /** The phone itself is playing. */
    PHONE,

    /** The phone is controlling another Spotify Connect device. */
    CONNECT_REMOTE,

    /**
     * The watch is playing on its own. Never sent by a phone: the watch builds
     * snapshots of its own playback with it, so the screens read one model.
     */
    WATCH,
}

@Serializable
enum class RepeatMode { OFF, ALL, ONE }

@Serializable
data class TrackInfo(
    val uri: String,
    val title: String,
    val artist: String = "",
    val artistUri: String? = null,
    val album: String? = null,
    val albumUri: String? = null,
    val durationMs: Long = 0,
    val explicit: Boolean = false,
    /** Key of the cover in the art cache: the Spotify image id, or a hash of the URL. */
    val artKey: String? = null,
    /** The cover's URL, for a watch that is online on its own and missed the asset. */
    val artUrl: String? = null,
)

@Serializable
data class ContextInfo(
    val uri: String,
    val label: String = "",
)

@Serializable
enum class DeviceKind { PHONE, WATCH, COMPUTER, TABLET, SPEAKER, TV, CAR, HEADPHONES, OTHER }

@Serializable
data class DeviceInfo(
    val id: String,
    val name: String,
    val kind: DeviceKind = DeviceKind.OTHER,
    /** The phone the watch is talking to, as opposed to a Connect device it controls. */
    val isThisPhone: Boolean = false,
    /** 0..1. */
    val volume: Float = 0f,
    val canSetVolume: Boolean = false,
)

@Serializable
data class SleepInfo(
    val endsAtEpochMs: Long? = null,
    val atTrackEnd: Boolean = false,
)

/**
 * The key a cover is cached under on the watch.
 *
 * Spotify serves covers as `https://i.scdn.co/image/<id>`, and the id is shared
 * by every track of an album, so keying on it sends an album's cover once. Any
 * other URL falls back to a stable hash of itself.
 */
fun artKeyOf(url: String?): String? {
    if (url.isNullOrBlank()) return null
    val marker = "/image/"
    val at = url.indexOf(marker)
    if (at >= 0) {
        val id = url.substring(at + marker.length).substringBefore('?').substringBefore('/')
        if (id.isNotEmpty() && id.all { it.isLetterOrDigit() }) return id
    }
    return "u" + fnv1a64(url).toULong().toString(16)
}

private fun fnv1a64(text: String): Long {
    var hash = -0x340d631b7bdddcdbL
    for (byte in text.encodeToByteArray()) {
        hash = hash xor (byte.toLong() and 0xff)
        hash *= 0x100000001b3L
    }
    return hash
}
