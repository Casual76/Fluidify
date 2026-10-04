package dev.pampa.fluidify.wear.playback

import dev.pampa.fluidify.wear.link.LinkStatus
import dev.pampa.fluidify.wear.protocol.PlaybackSource

/** New phone playback wins; a cached snapshot or a mirror of this watch never does. */
internal object PhonePlaybackPriority {
    fun shouldFollow(phone: NowPlaying, watchId: String, selectionSeq: Long, nowMs: Long): Boolean {
        val received = phone.received ?: return false
        val snapshot = received.snapshot
        val device = snapshot.device
        val age = nowMs - (snapshot.sentAtEpochMs + (received.clockOffsetMs ?: 0))
        return phone.link == LinkStatus.CONNECTED && snapshot.seq > selectionSeq &&
            age in -5_000..60_000 && snapshot.track != null &&
            (snapshot.isPlaying || snapshot.playWhenReady) &&
            (snapshot.source == PlaybackSource.PHONE ||
                snapshot.source == PlaybackSource.CONNECT_REMOTE &&
                device != null && device.id != watchId)
    }
}
