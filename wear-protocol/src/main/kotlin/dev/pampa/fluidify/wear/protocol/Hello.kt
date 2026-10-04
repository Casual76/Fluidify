package dev.pampa.fluidify.wear.protocol

import kotlinx.serialization.Serializable

/**
 * The version of the conversation, as opposed to the version of either app.
 *
 * [MAJOR] changes only when a message one side sends could be misread by the
 * other: a removed field, a field whose meaning changed, a path that moved.
 * Adding something is a [MINOR] bump, and an optional capability is a feature
 * string in [Hello.features] instead, so that the two apps can be a release
 * apart and still work.
 */
object ProtocolVersion {
    const val MAJOR = 1
    const val MINOR = 0
}

@Serializable
enum class Role { PHONE, WATCH }

/** Optional capabilities, advertised in [Hello.features]. Absent means "not this version". */
object Features {
    const val AUDIO_LIGHT = "audio-light"
    /** Volume through the phone (stream volume, or the Connect device's). */
    const val VOLUME = "volume"

    /** Spotify Connect device list and transfer. */
    const val DEVICES = "devices"

    /** The queue window and jumping into it. */
    const val QUEUE = "queue"

    /** Home, library, search and contexts over RPC. */
    const val LIBRARY = "library"

    /** The instant library snapshot. */
    const val LIBRARY_SNAPSHOT = "library-snapshot"

    /** Sleep timer. */
    const val SLEEP = "sleep"

    /** Self-update of the watch app through the phone. */
    const val UPDATE_PUSH = "update-push"

    /** Standalone sign-in on the watch. */
    const val AUTH = "auth"

    /** Downloads kept on the watch. */
    const val DOWNLOADS = "downloads"

    /** Moving playback between the phone and the watch. */
    const val HANDOFF = "handoff"
}

/**
 * The first thing either side says, and the answer it expects back.
 *
 * Carries enough to decide, without another round trip, whether the two can
 * talk ([compatibility]), whether one of them should be updated, and, through
 * [certSha256], why they might be failing to hear each other at all: the Data
 * Layer silently drops traffic between two builds signed with different keys, so
 * the one message that does get through (the capability) is paired with this
 * fingerprint to say so out loud.
 */
@Serializable
data class Hello(
    val role: Role,
    val versionName: String,
    val versionCode: Long,
    val protoMajor: Int = ProtocolVersion.MAJOR,
    val protoMinor: Int = ProtocolVersion.MINOR,
    val features: Set<String> = emptySet(),
    val buildType: String = "",
    val certSha256: String = "",
    val abis: List<String> = emptyList(),
    val sdk: Int = 0,
    /** True when this hello expects a hello back. */
    val wantsReply: Boolean = true,
    /** Clock sample carried by an immediate message, never a queued playback DataItem. */
    val sentAtEpochMs: Long = 0,
)

enum class Compatibility {
    /** Same major: everything works, optional features by [Hello.features]. */
    OK,

    /** The other side speaks an older major and should be updated. */
    PEER_OUTDATED,

    /** This side speaks an older major and should be updated. */
    SELF_OUTDATED,

    /** Both are present but signed with different keys. Nothing but hello gets through. */
    SIGNATURE_MISMATCH,
}

/** Whether [local] and [remote] can work together, and if not, which one has to move. */
fun compatibility(local: Hello, remote: Hello): Compatibility {
    if (local.certSha256.isNotEmpty() && remote.certSha256.isNotEmpty() &&
        !local.certSha256.equals(remote.certSha256, ignoreCase = true)
    ) {
        return Compatibility.SIGNATURE_MISMATCH
    }
    return when {
        remote.protoMajor == local.protoMajor -> Compatibility.OK
        remote.protoMajor < local.protoMajor -> Compatibility.PEER_OUTDATED
        else -> Compatibility.SELF_OUTDATED
    }
}

/** The features both sides have, which is the only set either may use. */
fun sharedFeatures(local: Hello, remote: Hello): Set<String> = local.features intersect remote.features
