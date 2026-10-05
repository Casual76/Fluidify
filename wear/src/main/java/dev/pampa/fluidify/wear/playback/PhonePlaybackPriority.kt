package dev.pampa.fluidify.wear.playback

import dev.pampa.fluidify.wear.link.LinkStatus
import dev.pampa.fluidify.wear.protocol.PlaybackSource

/** New phone playback wins; a cached snapshot or a mirror of this watch never does. */
internal object PhonePlaybackPriority {
    /** A snapshot may seem this far from the future: the two clocks are only estimates of each other. */
    private const val FUTURE_SKEW_MS = 5_000L

    /** Older than this, a snapshot is the last word of a phone that went away, not news. */
    private const val FRESH_MS = 60_000L

    fun shouldFollow(phone: NowPlaying, watchId: String, selectionSeq: Long, nowMs: Long): Boolean {
        val received = phone.received ?: return false
        val snapshot = received.snapshot
        val device = snapshot.device
        val age = nowMs - (snapshot.sentAtEpochMs + (received.clockOffsetMs ?: 0))
        return phone.link == LinkStatus.CONNECTED && snapshot.seq > selectionSeq &&
            age in -FUTURE_SKEW_MS..FRESH_MS && snapshot.track != null &&
            (snapshot.isPlaying || snapshot.playWhenReady) &&
            (snapshot.source == PlaybackSource.PHONE ||
                snapshot.source == PlaybackSource.CONNECT_REMOTE &&
                device != null && device.id != watchId)
    }
}
