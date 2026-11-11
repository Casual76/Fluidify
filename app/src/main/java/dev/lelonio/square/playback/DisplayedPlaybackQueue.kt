@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.lelonio.square.playback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import dev.lelonio.square.ui.player.QueueEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-local ownership of the Spotify queue parked behind a music video. Main looper only. */
internal object VideoPlaybackQueue {
    private val state = MutableStateFlow<VideoQueueOwner?>(null)
    val owner = state.asStateFlow()
    fun show(owner: VideoQueueOwner) { state.value = owner }
    fun clear() { state.value = null }
}

internal class VideoQueueOwner(
    val player: Player,
    val accountEpoch: Long,
    private val handOver: (DisplayedPlaybackQueue, (Player) -> Unit) -> Unit,
    val changed: () -> Unit,
) {
    fun select(snapshot: DisplayedPlaybackQueue, index: Int) {
        handOver(snapshot) { active ->
            if (active === player) {
                active.seekTo(index, 0L)
                active.play()
                changed()
            }
        }
    }
}

/** The rows and their command destination are captured together, including the account boundary. */
internal class DisplayedPlaybackQueue(
    private val sessionPlayer: Player?,
    private val videoOwner: VideoQueueOwner? = VideoPlaybackQueue.owner.value,
    private val epoch: Long = PlaybackService.mediaAccountEpoch,
    private val currentEpoch: () -> Long = { PlaybackService.mediaAccountEpoch },
    private val currentVideoOwner: () -> VideoQueueOwner? = { VideoPlaybackQueue.owner.value },
) {
    private val owner = videoOwner?.player ?: sessionPlayer
    private val timeline = owner?.currentTimeline ?: Timeline.EMPTY
    private val currentIndex = owner?.currentMediaItemIndex ?: 0
    val hasNext: Boolean = owner?.hasNextMediaItem() == true
    val hasPrevious: Boolean = owner?.hasPreviousMediaItem() == true
    val entries: List<QueueEntry> = owner?.let { p ->
        (currentIndex.coerceAtLeast(0) until p.mediaItemCount).map { index ->
            val item = p.getMediaItemAt(index)
            QueueEntry(index, item.mediaId, item.mediaMetadata.title?.toString().orEmpty(),
                item.mediaMetadata.artist?.toString().orEmpty(), item.mediaMetadata.artworkUri?.toString(),
                item.mediaMetadata.extras?.getBoolean(dev.lelonio.square.ui.EXTRA_PLAY_NEXT) == true,
                index == currentIndex)
        }
    }.orEmpty()

    fun isCurrent(): Boolean = owner != null && currentEpoch() == epoch &&
        (videoOwner == null || videoOwner.accountEpoch == epoch) &&
        currentVideoOwner() === videoOwner && owner.currentTimeline == timeline &&
        owner.currentMediaItemIndex == currentIndex

    fun select(index: Int) {
        if (!isCurrent() || entries.none { it.index == index }) return
        if (videoOwner != null) videoOwner.select(this, index) else owner?.seekTo(index, 0L)
    }

    fun remove(index: Int) {
        if (!isCurrent() || entries.none { it.index == index && !it.isCurrent }) return
        owner?.removeMediaItem(index)
        videoOwner?.changed?.invoke()
    }

    fun move(from: Int, to: Int) {
        if (!isCurrent() || entries.none { it.index == from } || entries.none { it.index == to }) return
        owner?.moveMediaItem(from, to)
        videoOwner?.changed?.invoke()
    }
}

/** Observe the same player the captured actions address, even while the session hosts a video. */
@Composable
internal fun rememberDisplayedPlaybackQueue(sessionPlayer: Player?): DisplayedPlaybackQueue {
    val videoOwner by VideoPlaybackQueue.owner.collectAsStateWithLifecycle()
    val source = videoOwner?.player ?: sessionPlayer
    val snapshot = remember(sessionPlayer, videoOwner) {
        mutableStateOf(DisplayedPlaybackQueue(sessionPlayer, videoOwner))
    }
    DisposableEffect(source, sessionPlayer, videoOwner) {
        fun refresh() { snapshot.value = DisplayedPlaybackQueue(sessionPlayer, videoOwner) }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) refresh()
            }
        }
        source?.addListener(listener)
        refresh()
        onDispose { source?.removeListener(listener) }
    }
    return snapshot.value
}
