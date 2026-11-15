package dev.pampa.fluidify.wear.system

import android.app.Application
import dev.pampa.fluidify.wear.link.I12CommandHarness
import dev.pampa.fluidify.wear.link.WatchState
import dev.pampa.fluidify.wear.playback.PhoneRemote
import dev.pampa.fluidify.wear.protocol.Command
import dev.pampa.fluidify.wear.protocol.PlaybackSnapshot
import dev.pampa.fluidify.wear.protocol.TrackInfo
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class I12MirrorTransportRegressionTest {
    @Test fun mirrorForwardsAbsoluteRequestsEvenWhenItsSnapshotAlreadyClaimsThatState() = runTest {
        for (shown in listOf(true, false)) for (requested in listOf(true, false)) {
            val h = I12CommandHarness().apply { afterSend = { ack(it) } }
            val now = System.currentTimeMillis()
            val state = WatchState(File.createTempFile("i12-mirror-", ".json").apply { delete() })
            state.accept(PlaybackSnapshot(1, now, track = TrackInfo("spotify:track:a", "A", durationMs = 3_600_000),
                sampledAtEpochMs = now, isPlaying = shown, playWhenReady = shown))
            val remote = PhoneRemote(backgroundScope, state, h)
            runCurrent()
            val mirror = PhoneMirrorPlayer(remote) { remote.nowPlaying.value }
            try {
                // Exercise the Media3 adapter boundary directly, including an absolute request
                // equal to the stale displayed value (SimpleBasePlayer may otherwise coalesce it).
                PhoneMirrorPlayer::class.java.getDeclaredMethod("handleSetPlayWhenReady", java.lang.Boolean.TYPE)
                    .apply { isAccessible = true }.invoke(mirror, requested)
                runCurrent()
                assertEquals(if (requested) Command.Play else Command.Pause, h.sent.single().command)
            } finally { mirror.release() }
        }
    }
}
