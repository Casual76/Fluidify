package dev.pampa.fluidify.wear.playback

import android.app.Application
import android.os.SystemClock
import dev.pampa.fluidify.wear.link.I12CommandHarness
import dev.pampa.fluidify.wear.link.WatchState
import dev.pampa.fluidify.wear.protocol.*
import dev.pampa.fluidify.wear.ui.player.airPodsBatteryModel
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class I15AirPodsBatteryOptimisticTest {
    @Test fun actualI12PauseGuessPreservesBatteryReceiptAndDisconnectRevokesIt() = runTest {
        val file = File.createTempFile("i15-guess-", ".json").apply { delete() }
        try {
            val wall = System.currentTimeMillis()
            val elapsed = SystemClock.elapsedRealtime()
            val state = WatchState(file)
            state.beginAirPodsBatteryContact("phone", wall, elapsed)
            state.observeClock(wall, wall, elapsed)
            state.confirmAirPodsBatteryContact(wall, "phone", wall, elapsed)
            state.observeAirPodsBatteryAccount(AccountState(true, changedAtEpochMs = wall, localDataSession = "a-1"), wall, elapsed)
            val snapshot = PlaybackSnapshot(1, wall, source = PlaybackSource.PHONE,
                track = TrackInfo("spotify:track:a", "A", durationMs = 3_600_000),
                sampledAtEpochMs = wall, isPlaying = true, playWhenReady = true,
                airPodsBattery = AirPodsBatterySnapshot("a-1", "source", right = AirPodsBatteryLevel(42, wall - 10_000)))
            state.accept(snapshot, wall, elapsed)
            val originalReceipt = state.current.value!!.airPodsBatteryReceipt!!
            val link = I12CommandHarness() // holds ACK; production I12 request preparation/delivery is real
            val remote = PhoneRemote(backgroundScope, state, link)
            runCurrent()
            remote.setPlaying(false)
            runCurrent()
            assertEquals(Command.Pause, link.sent.single().command)
            assertSame(originalReceipt, remote.nowPlaying.value.received!!.airPodsBatteryReceipt)
            assertTrue(airPodsBatteryModel(remote.nowPlaying.value.received, link.status.value, "a-1", wall, elapsed).available)
            // A newer snapshot has not confirmed pause, so I12 keeps its old optimistic frame.
            state.accept(snapshot.copy(seq = 2, sentAtEpochMs = wall + 1, airPodsBattery = null), wall + 1, elapsed + 1)
            runCurrent()
            assertFalse(remote.nowPlaying.value.snapshot!!.isPlaying)
            assertFalse(airPodsBatteryModel(remote.nowPlaying.value.received, link.status.value, "a-1", wall + 1, elapsed + 1).available)
        } finally { file.delete() }
    }
}
