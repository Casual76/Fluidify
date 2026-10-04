package dev.pampa.fluidify.wear.protocol

import dev.pampa.fluidify.wear.protocol.logic.TransferPreference
import kotlinx.serialization.Serializable

/*
 * Downloads kept on the watch.
 *
 * The watch decides what it keeps: its list of playlists and albums is the truth, edited
 * from the wrist or from the phone. The phone asks ([DownloadRequest], a message, resent
 * on the next hello if the watch was away) and reads back what the watch has
 * ([WatchDownloads], a DataItem the watch keeps current).
 */

/**
 * Phone to watch, message at [WearPaths.DOWNLOAD_PLAN]: keep (or stop keeping) a
 * playlist or album, and/or change how the watch downloads. A request without
 * an [owner] only changes the settings.
 */
@Serializable
data class DownloadRequest(
    val owner: String? = null,
    val title: String = "",
    val keep: Boolean = true,
    val artUrl: String? = null,
    /** The watch's download quality: 96, 160 or 320. */
    val qualityKbps: Int? = null,
    val preference: TransferPreference? = null,
)

/** One playlist or album the watch keeps, and how far it has got. */
@Serializable
data class WatchDownloadOwner(
    val uri: String,
    val title: String,
    val tracks: Int = 0,
    val done: Int = 0,
    val unavailable: Int = 0,
)

/** Watch to phone, DataItem at [WearPaths.DOWNLOAD_STATUS]. */
@Serializable
data class WatchDownloads(
    val owners: List<WatchDownloadOwner> = emptyList(),
    val bytesUsed: Long = 0,
    val bytesFree: Long = 0,
    /** Tracks still to fetch. */
    val waiting: Int = 0,
    /** The track being fetched now, if any. */
    val active: String? = null,
    /** Why the queue is stopped, when it is: no network, no phone, storage full. */
    val paused: String? = null,
    val qualityKbps: Int = 160,
    val preference: TransferPreference = TransferPreference.BLUETOOTH_FIRST,
    val updatedAtEpochMs: Long = 0,
    val unavailable: Int = 0,
)

/** What the phone has of one track: its sidecar (format, file id, key), or nothing. */
@Serializable
data class PhoneDownload(
    val uri: String,
    /** The download's sidecar as the native store wrote it; null when the phone does not have it. */
    val sidecar: String? = null,
)

@Serializable
data class PhoneDownloads(val tracks: List<PhoneDownload> = emptyList())

/**
 * The first line the watch writes into a [WearPaths.DOWNLOAD_FILE] channel.
 *
 * [offset] is how much of the file the watch already has (its `.part`), so a
 * transfer cut off by the watch walking out of range resumes instead of
 * starting again. [stageKbps] asks the phone to fetch the track at that
 * quality first, into a scratch area, when it does not have it as wanted.
 */
@Serializable
data class FileRequest(
    val uri: String,
    val offset: Long = 0,
    val stageKbps: Int? = null,
    /** Identity of the partial file, required before an offset can be trusted. */
    val fileId: String? = null,
)

/** The first line the phone writes back, before the bytes. */
@Serializable
data class FileHeader(
    val ok: Boolean,
    val sidecar: String? = null,
    /** The whole file's length; the bytes that follow start at the request's offset. */
    val totalBytes: Long = 0,
    val error: String? = null,
    /** The actual accepted offset; zero when the partial file belongs to another encoding. */
    val offset: Long = 0,
)
