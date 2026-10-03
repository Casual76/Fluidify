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
)

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
    val reason: String? = null,
)
