@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.lelonio.square.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

/** Commands are observed before delegation, including same-value pause/volume commands. */
internal class I05PlaybackIntentPlayer(
    player: Player,
    private val transport: () -> Unit,
    private val volume: () -> Unit,
) : ForwardingPlayer(player) {
    override fun play() { transport(); super.play() }
    override fun pause() { transport(); super.pause() }
    override fun setPlayWhenReady(value: Boolean) { transport(); super.setPlayWhenReady(value) }
    override fun stop() { transport(); super.stop() }
    override fun setVolume(value: Float) { volume(); super.setVolume(value) }
    override fun seekTo(positionMs: Long) { transport(); super.seekTo(positionMs) }
    override fun seekTo(index: Int, positionMs: Long) { transport(); super.seekTo(index, positionMs) }
    override fun seekBack() { transport(); super.seekBack() }
    override fun seekForward() { transport(); super.seekForward() }
    override fun seekToDefaultPosition() { transport(); super.seekToDefaultPosition() }
    override fun seekToDefaultPosition(index: Int) { transport(); super.seekToDefaultPosition(index) }
    override fun seekToNext() { transport(); super.seekToNext() }
    override fun seekToPrevious() { transport(); super.seekToPrevious() }
    override fun seekToNextMediaItem() { transport(); super.seekToNextMediaItem() }
    override fun seekToPreviousMediaItem() { transport(); super.seekToPreviousMediaItem() }
    override fun setMediaItem(item: MediaItem) { transport(); super.setMediaItem(item) }
    override fun setMediaItem(item: MediaItem, positionMs: Long) { transport(); super.setMediaItem(item, positionMs) }
    override fun setMediaItem(item: MediaItem, reset: Boolean) { transport(); super.setMediaItem(item, reset) }
    override fun setMediaItems(items: List<MediaItem>) { transport(); super.setMediaItems(items) }
    override fun setMediaItems(items: List<MediaItem>, reset: Boolean) { transport(); super.setMediaItems(items, reset) }
    override fun setMediaItems(items: List<MediaItem>, index: Int, positionMs: Long) {
        transport(); super.setMediaItems(items, index, positionMs)
    }
    override fun clearMediaItems() { transport(); super.clearMediaItems() }
}

/** Timeline indexes in actual playback order, never a newly drawn shuffle. */
internal fun i05TimelineOrder(player: Player): List<Int>? {
    val timeline = player.currentTimeline
    if (timeline.windowCount != player.mediaItemCount) return null
    val order = mutableListOf<Int>()
    val seen = mutableSetOf<Int>()
    var index = timeline.getFirstWindowIndex(player.shuffleModeEnabled)
    while (index in 0 until timeline.windowCount && seen.add(index)) {
        order += index
        index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
    }
    return order.takeIf { it.size == player.mediaItemCount }
}

/** Every awaited result is admitted again against its original selection. */
internal class I05PlaybackRequestLease(private val current: () -> Boolean) {
    fun requireCurrent() {
        if (!current()) throw CancellationException("Playback request lost its selection")
    }
    suspend fun <T> await(block: suspend () -> T): T {
        requireCurrent()
        val result = block()
        requireCurrent()
        return result
    }
}

/** Restores only its last write on its original player/account/volume revision. */
internal suspend fun i05FadeAndPause(
    steps: Int,
    stepMillis: Long,
    ownsVolume: () -> Boolean,
    read: () -> Float,
    write: (Float) -> Unit,
    pause: () -> Unit,
) {
    val baseline = read()
    var applied = baseline
    fun current() = ownsVolume() && read() == applied
    try {
        repeat(steps) { step ->
            if (!current()) return
            applied = baseline * (1f - (step + 1f) / steps)
            write(applied)
            delay(stepMillis)
            if (!current()) return
        }
        if (current()) pause()
    } finally {
        // No suspension: cancellation cannot skip this write on the player's main looper.
        if (current()) write(baseline)
    }
}

/** Request-scoped wait only; never starts polling for an idle service. */
internal data class I05PhonePlaybackFacts(
    val nativeLocal: Boolean,
    val spotifySource: Boolean,
    val remoteProjection: Boolean,
    val playing: Boolean,
    val mediaId: String?,
    val rendererActive: Boolean,
) {
    fun acquiredFor(requestedUri: String): Boolean = nativeLocal && spotifySource &&
        !remoteProjection && playing && mediaId == requestedUri && rendererActive
}

/** Bounded by its confirmed request's outer timeout; no background maintenance owner. */
internal suspend fun i05AwaitPhoneAcquisition(
    valid: () -> Boolean,
    acquired: suspend () -> Boolean,
    timeoutMillis: Long = 8_000,
    intervalMillis: Long = 100,
): Boolean = kotlinx.coroutines.withTimeoutOrNull(timeoutMillis) {
    while (valid()) {
        if (acquired()) return@withTimeoutOrNull valid()
        if (!valid()) return@withTimeoutOrNull false
        delay(intervalMillis)
    }
    false
} ?: false

/** An accepted request is pending until acquisition; every terminal path closes the receipt. */
internal suspend fun i05CompletePhoneAcquisition(
    done: CompletableDeferred<Boolean>,
    isCurrent: () -> Boolean,
    timeoutMillis: Long,
    acquire: suspend (current: () -> Boolean) -> Boolean,
) {
    var success = false
    fun current() = done.isActive && isCurrent()
    try {
        if (!current()) return
        success = kotlinx.coroutines.withTimeoutOrNull(timeoutMillis) {
            acquire(::current) && current()
        } == true
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { /* The false receipt is the caller's transfer failure. */ }
    finally { done.complete(success) }
}
