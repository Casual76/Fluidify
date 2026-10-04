package dev.pampa.fluidify.wear.playback

import dev.pampa.fluidify.wear.link.LinkStatus
import dev.pampa.fluidify.wear.link.ReceivedSnapshot
import dev.pampa.fluidify.wear.screenshots.FakeControls
import dev.pampa.fluidify.wear.screenshots.sampleSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VolumeControlTest {
    private class Controls : PlaybackControls by FakeControls(null) {
        val snapshot = sampleSnapshot()
        override val nowPlaying = MutableStateFlow(NowPlaying(
            ReceivedSnapshot(snapshot.copy(device = null), System.currentTimeMillis()), LinkStatus.CONNECTED))
        val sent = mutableListOf<Float>()
        override fun setVolume(level: Float, deviceId: String?) { sent += level }
    }

    @Test fun unknownLevelAndLostConnectionDoNotSendOrShowOverlay() = runTest {
        val controls = Controls()
        val volume = VolumeControl(backgroundScope, controls)
        runCurrent()
        volume.turn(1)
        runCurrent()
        assertFalse(volume.visible.value)
        assertTrue(controls.sent.isEmpty())
        volume.sync(0.4f)
        controls.nowPlaying.value = controls.nowPlaying.value.copy(link = LinkStatus.UNREACHABLE)
        runCurrent()
        volume.turn(1)
        assertFalse(volume.visible.value)
        assertTrue(controls.sent.isEmpty())
    }

    @Test fun resettingForAnotherDeviceCancelsPendingSend() = runTest {
        val controls = Controls()
        val volume = VolumeControl(backgroundScope, controls)
        runCurrent()
        volume.sync(0.4f)
        volume.turn(1)
        volume.reset(0.8f)
        runCurrent()
        assertTrue(controls.sent.isEmpty())
        assertEquals(0.8f, volume.level.value, 0.001f)
        assertFalse(volume.visible.value)
    }
}
