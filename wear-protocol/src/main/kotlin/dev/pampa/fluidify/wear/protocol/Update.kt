package dev.pampa.fluidify.wear.protocol

import kotlinx.serialization.Serializable

/**
 * The phone has a newer watch build and offers to send it.
 *
 * Unversioned like [Hello] (see [WearPaths.UPDATE_OFFER]): a watch any number of
 * releases behind must still understand this one message, because it is how it
 * catches up.
 */
@Serializable
data class UpdateOffer(
    val versionName: String,
    val versionCode: Long = 0,
    val sizeBytes: Long = 0,
    /** Hex SHA-256 of the APK. The watch checks it before installing. */
    val sha256: String = "",
    /** The protocol major the offered build speaks. */
    val protoMajor: Int = ProtocolVersion.MAJOR,
    /** True when the user asked for it on the phone, rather than an automatic push. */
    val requestedByUser: Boolean = false,
    /**
     * The release line this build comes from, as [UpdateChannels] spells it.
     *
     * A string and not an enum on purpose: an old watch decoding a value it has never heard of
     * would fail the whole offer, which is the one message that must always get through.
     */
    val channel: String = UpdateChannels.STABLE,
    /**
     * What is new in this build, already shortened for a small screen (see [UpdateChangelog]).
     *
     * Blank for a phone from before this field, or for a build without notes.
     */
    val changelog: String = "",
    /**
     * Where the APK can be fetched from directly: the release asset the phone itself downloaded.
     *
     * A watch that can reach the internet on its own Wi-Fi takes it from here, which is many times
     * faster than the same bytes over Bluetooth, and says so with [UpdateStatus.REASON_WIFI]; it
     * still checks [sha256] and the signature exactly as for a transfer. Blank (an older phone, a
     * file picked by hand) means Bluetooth, as before.
     */
    val downloadUrl: String = "",
)

/**
 * The phone's answer to a watch that asked, on [WearPaths.UPDATE_REQUEST], for an update check.
 *
 * Needed because "nothing new" is otherwise silent: an offer is the only message a check ever
 * produces, and a watch waiting on its "Check for updates" row could not tell a phone that found
 * nothing from one that never heard. [result] is one of the words of [Companion], a string and
 * not an enum for the reason in [UpdateOffer.channel].
 */
@Serializable
data class UpdateCheckReply(
    val result: String,
    /** The version on its way ([UPDATE]), or the one found current, when there is one to name. */
    val versionName: String = "",
) {
    companion object {
        /** The manifest has nothing newer than what the watch runs. */
        const val UP_TO_DATE = "up-to-date"

        /** There is a newer build: it is being fetched, and an [UpdateOffer] will follow. */
        const val UPDATE = "update"

        /** The phone could not tell (no network, no manifest). */
        const val FAILED = "failed"
    }
}

/**
 * The release lines a build can be followed on, as they travel between the phone and the watch.
 *
 * Words and not an enum for the reason in [UpdateOffer.channel]: a third line added one day is
 * read by an old peer as [parse] reads any word it does not know, as the stable one.
 */
object UpdateChannels {
    const val STABLE = "stable"
    const val BETA = "beta"

    /** [value] as a channel word, with anything unknown or blank meaning [STABLE]. */
    fun parse(value: String?): String = if (value.equals(BETA, ignoreCase = true)) BETA else STABLE
}

/** Keeps release notes small enough for where they are going. */
object UpdateChangelog {
    /** What the watch gets: a few paragraphs at most, it has a small screen and a thin pipe. */
    const val WATCH_MAX_CHARS = 600

    /** What rides in a WorkManager input, whose whole budget is 10 KB. */
    const val WORK_MAX_CHARS = 2_000

    /**
     * [text] trimmed, and cut to at most [maxChars] characters with an ellipsis when it is longer.
     *
     * The cut is at the last line break or space in the final fifth, when there is one, so a
     * word is not left in half; and never between the two halves of a surrogate pair.
     */
    fun truncate(text: String, maxChars: Int): String {
        val clean = text.trim()
        if (maxChars <= 1 || clean.length <= maxChars) return clean
        var end = maxChars - 1
        val floor = end - end / 5
        val at = clean.lastIndexOfAny(charArrayOf('\n', ' '), end)
        if (at >= floor) end = at
        if (end > 0 && Character.isHighSurrogate(clean[end - 1])) end--
        return clean.substring(0, end).trimEnd() + "…"
    }
}

@Serializable
enum class UpdatePhase {
    /** The watch wants the APK; the phone should open the channel. */
    ACCEPT,

    /** Not now (busy, low battery, already newer). */
    DECLINE,

    /** Bytes are arriving. */
    RECEIVING,

    /** Verified and handed to the system installer. */
    INSTALLING,

    /** The system asked the person on the watch to confirm. */
    AWAITING_CONFIRMATION,

    INSTALLED,
    FAILED,
}

@Serializable
data class UpdateStatus(
    val phase: UpdatePhase,
    val versionName: String = "",
    /** 0..1 while receiving. */
    val progress: Float = 0f,
    /**
     * Why, as a short token for the phone to turn into a sentence.
     *
     * New meanings travel here and not as new [UpdatePhase] values, which an older peer could not
     * decode: [REASON_WAITING_PLAYBACK] on an [UpdatePhase.ACCEPT] is "yes, but not while I am
     * playing"; an old phone reads it as a plain accept, which is what it is.
     */
    val reason: String? = null,
) {
    companion object {
        /** The watch has the APK (or will have it) and installs it once its own music stops. */
        const val REASON_WAITING_PLAYBACK = "waiting-playback"

        /**
         * On [UpdatePhase.ACCEPT]: the watch fetches the build itself, over its own Wi-Fi, so the
         * phone sends nothing. A plain accept follows if that does not work out, and the phone
         * then sends it over Bluetooth as always. On [UpdatePhase.RECEIVING]: the progress is of
         * that download. An old phone reads the accept as a plain one and starts sending, which
         * the watch refuses while it downloads.
         */
        const val REASON_WIFI = "wifi"
    }
}
