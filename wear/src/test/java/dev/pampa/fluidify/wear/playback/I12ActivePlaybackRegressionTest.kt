package dev.pampa.fluidify.wear.playback

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.pampa.fluidify.wear.link.*
import dev.pampa.fluidify.wear.protocol.*
import dev.pampa.fluidify.wear.standalone.LocalControls
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual ActivePlayback/LocalControls; preseeded local state avoids starting service/JNI/network. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class I12ActivePlaybackRegressionTest {
    private class Fixture(scope: TestScope) {
        val h = I12CommandHarness()
        val state = WatchState(File.createTempFile("i12-active-", ".json").apply { delete() })
        val remote = PhoneRemote(scope.backgroundScope, state, h)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val local = LocalControls(context, scope.backgroundScope, ArtStore(context))
        val active = ActivePlayback(scope.backgroundScope, remote, { local },
            { error("Unconfirmed handback must not start or inspect the standalone engine") })
        val song = PlaybackSnapshot(1, System.currentTimeMillis(), source = PlaybackSource.WATCH,
            track = TrackInfo("spotify:track:a", "A", durationMs = 3_600_000),
            sampledAtEpochMs = System.currentTimeMillis(), isPlaying = true, playWhenReady = true)

        init {
            // Seeding an already-playing local session is fixture setup only. Stop remains real:
            // disconnect clears its published state, making premature stops directly observable.
            localState().value = NowPlaying(ReceivedSnapshot(song, System.currentTimeMillis()), LinkStatus.CONNECTED)
            @Suppress("UNCHECKED_CAST")
            (ActivePlayback::class.java.getDeclaredField("_mode").apply { isAccessible = true }
                .get(active) as MutableStateFlow<PlaybackMode>).value = PlaybackMode.WATCH
        }

        @Suppress("UNCHECKED_CAST")
        fun localState() = LocalControls::class.java.getDeclaredField("_nowPlaying").apply { isAccessible = true }
            .get(local) as MutableStateFlow<NowPlaying>

        fun assertWatchPreserved() {
            assertEquals(PlaybackMode.WATCH, active.mode.value)
            assertEquals(song, local.nowPlaying.value.snapshot)
        }

        fun publishLatePhone(seq: Long = 20, buffering: Boolean = false) {
            state.accept(song.copy(seq = seq, source = PlaybackSource.PHONE,
                isPlaying = !buffering, playWhenReady = true, buffering = buffering))
        }
    }

    @Test fun emissionAndBufferingCannotStopWatchBeforePositiveConsumerConfirmation() = runTest {
        val f = Fixture(this)
        f.active.moveToPhone("phone")
        runCurrent()
        assertEquals(1, f.h.sent.size)
        f.publishLatePhone(buffering = true)
        runCurrent()
        f.assertWatchPreserved()
        assertTrue(f.active.moving.value)
        f.h.ack(ok = true)
        runCurrent()
        assertEquals(PlaybackMode.PHONE, f.active.mode.value)
        assertNull(f.local.nowPlaying.value.snapshot)
        assertFalse(f.active.moving.value)
    }

    @Test fun negativeAckPreservesPlaybackEvenWhenNativeSnapshotArrivesLater() = runTest {
        val f = Fixture(this)
        f.active.moveToPhone("phone")
        runCurrent()
        f.h.ack(ok = false)
        runCurrent()
        f.assertWatchPreserved()
        assertFalse(f.active.moving.value)
        f.publishLatePhone()
        runCurrent()
        f.assertWatchPreserved()
        assertEquals(1, f.h.sent.size)
    }

    @Test fun timeoutAndLatePositiveAckAndSnapshotPreserveWatchWithoutRollbackOrRerun() = runTest {
        val f = Fixture(this)
        f.active.moveToPhone("phone")
        runCurrent()
        advanceTimeBy(11_000)
        runCurrent()
        f.assertWatchPreserved()
        assertFalse(f.active.moving.value)
        f.h.ack()
        f.publishLatePhone()
        runCurrent()
        advanceTimeBy(20_000)
        runCurrent()
        f.assertWatchPreserved()
        assertEquals(1, f.h.sent.size)
    }

    @Test fun accountChangeBetweenAckAndReturnCannotStopPlayback() = runTest {
        val f = Fixture(this)
        f.active.moveToPhone("phone")
        runCurrent()
        f.h.ack()
        f.h.account("b", "b-1", 2)
        runCurrent()
        f.assertWatchPreserved()
        assertEquals("a-1", f.h.sent.single().accountSession)
    }

    @Test fun newerExplicitTransferOwnsTheOnlyAcknowledgementThatMayStopWatch() = runTest {
        val f = Fixture(this)
        f.active.moveToPhone("phone")
        runCurrent()
        val first = f.h.sent.single()
        f.active.moveToPhone("speaker")
        runCurrent()
        val second = f.h.sent.last()
        assertNotEquals(first.id, second.id)
        f.h.ack(first)
        runCurrent()
        f.assertWatchPreserved()
        f.h.ack(second, ok = false)
        runCurrent()
        f.assertWatchPreserved()
        // A fresh user gesture, after failure, may succeed. It is not an automatic retry.
        f.active.moveToPhone("phone")
        runCurrent()
        f.h.ack()
        runCurrent()
        assertEquals(PlaybackMode.PHONE, f.active.mode.value)
        assertNull(f.local.nowPlaying.value.snapshot)
        assertEquals(3, f.h.sent.size)
    }
}
