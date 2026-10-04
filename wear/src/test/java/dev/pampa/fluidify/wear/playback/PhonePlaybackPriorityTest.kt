package dev.pampa.fluidify.wear.playback

import dev.pampa.fluidify.wear.link.LinkStatus
import dev.pampa.fluidify.wear.link.ReceivedSnapshot
import dev.pampa.fluidify.wear.protocol.*
import org.junit.Assert.*
import org.junit.Test

class PhonePlaybackPriorityTest {
    private fun phone(seq: Long = 11, sent: Long = 100_000, source: PlaybackSource = PlaybackSource.PHONE,
        id: String = "phone", playing: Boolean = true, offset: Long = 0, link: LinkStatus = LinkStatus.CONNECTED) =
        NowPlaying(ReceivedSnapshot(PlaybackSnapshot(seq, sent, source = source,
            track = TrackInfo("spotify:track:a", "A"), isPlaying = playing, playWhenReady = playing,
            device = DeviceInfo(id, id)), receivedAtMs = 100_000, clockOffsetMs = offset), link)

    @Test fun phoneStartingWinsEvenIfTheWatchHasNotRested() {
        assertTrue(PhonePlaybackPriority.shouldFollow(phone(), "watch", 10, 100_001))
    }
    @Test fun anotherConnectDeviceWinsButTheWatchsOwnMirrorDoesNot() {
        assertTrue(PhonePlaybackPriority.shouldFollow(phone(source = PlaybackSource.CONNECT_REMOTE, id = "car"), "watch", 10, 100_001))
        assertFalse(PhonePlaybackPriority.shouldFollow(phone(source = PlaybackSource.CONNECT_REMOTE, id = "watch"), "watch", 10, 100_001))
    }
    @Test fun oldCachedOrPausedSnapshotsDoNotUndoAnExplicitWatchSelection() {
        assertFalse(PhonePlaybackPriority.shouldFollow(phone(seq = 10), "watch", 10, 100_001))
        assertFalse(PhonePlaybackPriority.shouldFollow(phone(sent = 10_000), "watch", 10, 100_001))
        assertFalse(PhonePlaybackPriority.shouldFollow(phone(playing = false), "watch", 10, 100_001))
        assertFalse(PhonePlaybackPriority.shouldFollow(phone(link = LinkStatus.UNREACHABLE), "watch", 10, 100_001))
    }
    @Test fun clockCalibrationPreventsRejectingARealPhoneStart() {
        assertTrue(PhonePlaybackPriority.shouldFollow(phone(sent = 10_000, offset = 90_000), "watch", 10, 100_001))
    }
}
