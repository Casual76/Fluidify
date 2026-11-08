// SPDX-License-Identifier: GPL-3.0-only
package dev.lelonio.square.playback

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import dev.lelonio.square.data.*
import kotlinx.coroutines.*

/** Service-owned acquisition; UI and remote controllers never manufacture measured minutes. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ListeningRecorder(
    private val repository: ListeningRepository,
    private val scope: CoroutineScope,
    private val currentPlayer: () -> Player,
    private val wallNow: () -> Long = System::currentTimeMillis,
    private val tickNow: () -> Long = SystemClock::elapsedRealtime,
    private val isRemote: (Player) -> Boolean = { (it as? LibrespotPlayer)?.isRemotePlayback == true },
    private val occurrenceSource: (Player) -> PlaybackOccurrence? = { (it as? LibrespotPlayer)?.listeningOccurrence },
) {
    private var sampler = ListeningSampler()
    private var access: HistoryAccess? = null
    private var attached: Player? = null
    private var occurrence: PlaybackOccurrence? = null
    private var occurrenceId: String? = null
    private var restoring: Job? = null
    private val occurrenceChanged: () -> Unit = { sample(true) }
    private var lastSaved = 0L
    private val pending = mutableListOf<Job>()
    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Native players publish their authoritative token. Media3 may emit the same
            // boundary as well, and must not close the new occurrence a second time.
            if (occurrence == null) save(sampler.finish(wallNow(), tickNow()))
            sample(true)
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) { sample(true) }
        override fun onPlaybackStateChanged(playbackState: Int) { sample(true) }
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                sampler.discontinuity(tickNow())
            }
        }
    }
    private val job = scope.launch {
        try { while (isActive) { sample(false); delay(1_000) } }
        finally { close() }
    }

    private fun save(event: ListeningEvent?) {
        val owner = access ?: return
        if (event == null) return
        // Already measured snapshots may finish writing during service teardown, but revocation
        // in the repository still rejects logout, account changes and deletion races.
        pending.removeAll { it.isCompleted }
        pending += scope.launch(NonCancellable) { runCatching { repository.record(owner, event) } }
    }
    private fun sample(force: Boolean) {
        val session = repository.session.value
        if (access != session.access) {
            val hadAccess = access != null
            restoring?.cancel(); restoring = null
            sampler = ListeningSampler()
            access = session.access
            occurrenceId = null
            if (hadAccess) {
                val p = currentPlayer()
                p.currentMediaItem?.let { occurrenceSource(p)?.select(p.currentMediaItemIndex, it.mediaId) }
            }
        }
        val player = currentPlayer()
        if (attached !== player) {
            save(sampler.finish(wallNow(), tickNow()))
            attached?.removeListener(listener)
            occurrence?.removeListener(occurrenceChanged)
            occurrence = occurrenceSource(player)
            occurrenceId = null
            occurrence?.addListener(occurrenceChanged)
            attached = player
            player.addListener(listener)
        }
        val source = occurrence
        if (source != null && source.id != occurrenceId) {
            restoring?.cancel(); restoring = null
            save(sampler.finish(wallNow(), tickNow()))
            occurrenceId = source.id
            val token = source.id
            val owner = access
            if (source.restored && token != null && owner != null) {
                restoring = scope.launch(start = CoroutineStart.LAZY) {
                    val checkpoint = runCatching { repository.resumeOccurrence(owner, "local:$token") }.getOrNull()
                    ensureActive()
                    if (access == owner && source.id == token) {
                        restoring = null
                        val item = currentPlayer().currentMediaItem
                        val exact = checkpoint?.takeIf { it.track.uri == item?.mediaId }
                        if (exact != null) {
                            sampler.restore(exact)
                            sample(true)
                        } else if (item != null) {
                            // A missing/unreadable/mismatching checkpoint cannot share an old
                            // measured ID with a zero-origin sampler. Start an independent token.
                            source.select(currentPlayer().currentMediaItemIndex, item.mediaId)
                        } else {
                            source.nativeEvent("end_of_track", "", currentPlayer().currentMediaItemIndex, false)
                        }
                    }
                }.also { it.start() }
                return
            }
        }
        if (restoring != null) return
        val item = player.currentMediaItem
        val remote = isRemote(player)
        if (remote && source?.id != null) {
            source.nativeEvent("", "", player.currentMediaItemIndex, true)
            return
        }
        val allowed = (source == null || source.id != null) && session.recording && access != null && !remote && item?.mediaId?.startsWith("spotify:track:") == true
        val metadata = item?.mediaMetadata
        val track = if (allowed) CatalogTrack(item!!.mediaId, metadata?.title?.toString().orEmpty(),
            metadata?.artist?.toString().orEmpty(), album = metadata?.albumTitle?.toString().orEmpty(),
            artworkUrl = metadata?.artworkUri?.toString(), durationMs = player.duration.coerceAtLeast(0)) else null
        val tick = tickNow()
        val event = sampler.sample(track, allowed && player.isPlaying && player.playbackState == Player.STATE_READY,
            wallNow(), tick, occurrenceId?.let { "local:$it" })
        if (force || tick - lastSaved >= 5_000 || !allowed) { save(event); lastSaved = tick }
    }
    fun stop() { job.cancel() }

    /** Reconciles revocation before a queue snapshot can persist a continuity handoff. */
    fun playbackHandoff(): Pair<String, String>? {
        sample(true)
        val session = repository.session.value
        if (session.access != access || !session.recording || isRemote(currentPlayer())) return null
        val token = occurrence?.id ?: return null
        val fence = repository.playbackContinuity() ?: return null
        return token to fence
    }

    /** Allows orderly owners and regression tests to wait for durable checkpoints. */
    suspend fun awaitCheckpoints() { pending.toList().joinAll() }

    /** Waits for an exact restored-token lookup, without extrapolating elapsed time. */
    suspend fun awaitRestore() { restoring?.join() }

    fun close() {
        restoring?.cancel(); restoring = null
        occurrence?.removeListener(occurrenceChanged); occurrence = null
        attached?.removeListener(listener); attached = null
        save(sampler.finish(wallNow(), tickNow()))
    }
}
