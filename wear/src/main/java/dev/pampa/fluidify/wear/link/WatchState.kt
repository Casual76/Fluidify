package dev.pampa.fluidify.wear.link

import android.content.Context
import dev.pampa.fluidify.wear.protocol.PlaybackSnapshot
import dev.pampa.fluidify.wear.protocol.WearCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File

/** A snapshot from the phone and the moment it arrived, on this watch's clock. */
@Serializable
data class ReceivedSnapshot(
    val snapshot: PlaybackSnapshot,
    val receivedAtMs: Long,
)

/**
 * The last thing the phone said about what is playing.
 *
 * Kept on disk as well as in memory, because the watch app is opened cold far
 * more often than it is left open: the first frame should show the song that
 * was playing, not a spinner waiting for Bluetooth. A snapshot older than the
 * one held is ignored ([PlaybackSnapshot.seq] only grows), which is what makes a
 * late delivery of a stale DataItem harmless.
 */
class WatchState(private val file: File) {

    constructor(context: Context) : this(File(context.filesDir, "last-snapshot.json"))

    private val _current = MutableStateFlow(load())

    val current: StateFlow<ReceivedSnapshot?> = _current.asStateFlow()

    fun accept(snapshot: PlaybackSnapshot, receivedAtMs: Long = System.currentTimeMillis()): Boolean {
        val held = _current.value
        // A phone whose clock went backwards restarts its counter lower; a snapshot sent well
        // after the held one is still news.
        val newer = held == null || snapshot.seq > held.snapshot.seq ||
            snapshot.sentAtEpochMs > held.snapshot.sentAtEpochMs + RESTART_GRACE_MS
        if (!newer) return false
        val received = ReceivedSnapshot(snapshot, receivedAtMs)
        _current.value = received
        runCatching {
            file.writeBytes(WearCodec.encode(ReceivedSnapshot.serializer(), received))
        }
        return true
    }

    private fun load(): ReceivedSnapshot? = runCatching {
        if (!file.isFile) return null
        WearCodec.decodeOrNull(ReceivedSnapshot.serializer(), file.readBytes())
    }.getOrNull()

    private companion object {
        const val RESTART_GRACE_MS = 60_000L
    }
}
