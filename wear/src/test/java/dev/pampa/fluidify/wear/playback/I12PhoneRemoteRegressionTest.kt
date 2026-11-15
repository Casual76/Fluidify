package dev.pampa.fluidify.wear.playback

import android.app.Application
import dev.pampa.fluidify.wear.link.*
import dev.pampa.fluidify.wear.protocol.*
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
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
class I12PhoneRemoteRegressionTest {
    private fun state(playing: Boolean = true): WatchState =
        WatchState(File.createTempFile("i12-remote-", ".json").apply { delete() }).apply {
            val now = System.currentTimeMillis()
            accept(PlaybackSnapshot(1, now, track = TrackInfo("spotify:track:a", "A", durationMs = 3_600_000),
                sampledAtEpochMs = now, isPlaying = playing, playWhenReady = playing, liked = false))
        }

    @Test fun stalePhoneStateCannotReverseThePauseOrPlayRepresentedOnWatch() = runTest {
        for (shownPlaying in listOf(true, false)) {
            val h = I12CommandHarness()
            var actualPhonePlaying = !shownPlaying // headset already changed it, snapshot has not arrived
            h.afterSend = { envelope ->
                actualPhonePlaying = when (envelope.command) {
                    Command.Play -> true
                    Command.Pause -> false
                    Command.TogglePlay -> !actualPhonePlaying // negative control: old code inverts incorrectly
                    else -> error("Unexpected transport")
                }
                h.ack(envelope)
            }
            val remote = PhoneRemote(backgroundScope, state(shownPlaying), h)
            runCurrent()
            remote.togglePlay()
            runCurrent()
            assertEquals(!shownPlaying, actualPhonePlaying)
            assertEquals(if (shownPlaying) Command.Pause else Command.Play, h.sent.single().command)
        }
    }

    @Test fun absoluteIntentSurvivesSnapshotChangedAfterIconWasDrawn() = runTest {
        val h = I12CommandHarness().apply { afterSend = { ack(it) } }
        val watch = state(true)
        val remote = PhoneRemote(backgroundScope, watch, h)
        runCurrent()
        val pauseFromDrawnIcon = { remote.setPlaying(false) }
        watch.accept(watch.current.value!!.snapshot.copy(seq = 2, isPlaying = false, playWhenReady = false))
        runCurrent()
        pauseFromDrawnIcon()
        runCurrent()
        assertEquals(Command.Pause, h.sent.single().command)
        remote.setPlaying(true)
        runCurrent()
        assertEquals(Command.Play, h.sent.last().command)
    }

    @Test fun nullAckSendsEachSlowCommandExactlyOnceIncludingLikeAndQueue() = runTest {
        val cases: List<(PhoneRemote) -> Unit> = listOf(
            { it.setPlaying(true) }, { it.setPlaying(false) },
            { it.playContext("spotify:playlist:p") }, { it.addToQueue("spotify:track:a") },
            { it.startRadio() }, { it.playQueueIndex(0, "spotify:track:a") }, { it.setLiked(true) },
        )
        for (gesture in cases) {
            val h = I12CommandHarness()
            val remote = PhoneRemote(backgroundScope, state(), h)
            runCurrent()
            gesture(remote)
            runCurrent()
            val original = h.sent.single()
            advanceTimeBy(h.timeouts.single())
            runCurrent()
            assertFalse(remote.nowPlaying.value.busy)
            advanceTimeBy(20_000)
            runCurrent()
            assertEquals(listOf(original), h.sent)
            h.ack(original) // no late acknowledgement can revive a completed request
            runCurrent()
            assertEquals(listOf(original), h.sent)
        }
    }

    @Test fun accountChangeDuringLikeOrQueueAckNeverCreatesAnEnvelopeForB() = runTest {
        for (like in listOf(true, false)) {
            val h = I12CommandHarness()
            val remote = PhoneRemote(backgroundScope, state(), h)
            runCurrent()
            if (like) remote.setLiked(true) else remote.addToQueue("spotify:track:a")
            runCurrent()
            h.ack()
            h.account("b", "b-1", 2) // ACK completed, dispatch coroutine has not returned yet
            runCurrent()
            advanceTimeBy(20_000)
            runCurrent()
            assertEquals(1, h.sent.size)
            assertEquals("a-1", h.sent.single().accountSession)
            assertFalse(remote.nowPlaying.value.busy)
            assertEquals(false, remote.nowPlaying.value.snapshot?.liked)
        }
    }

    @Test fun intentCapturedBeforeDispatchCannotUseTheNextAccount() = runTest {
        val h = I12CommandHarness()
        val remote = PhoneRemote(backgroundScope, state(), h)
        runCurrent()
        remote.addToQueue("spotify:track:a")
        h.account("b", "b-1", 2)
        runCurrent()
        assertTrue(h.sent.isEmpty())
        assertFalse(remote.nowPlaying.value.busy)
    }

    @Test fun playlistNullOrNegativeAckIsOneWriteWithTenSecondBudget() = runTest {
        for (negative in listOf(true, false)) {
            val h = I12CommandHarness()
            val remote = PhoneRemote(backgroundScope, state(), h)
            val result = async { remote.addToPlaylist("spotify:playlist:p", "spotify:track:a") }
            runCurrent()
            assertEquals(10_000L, h.timeouts.single())
            if (negative) h.ack(ok = false) else advanceTimeBy(10_000)
            runCurrent()
            assertFalse(result.await())
            assertEquals(1, h.sent.size)
        }
    }

    @Test fun allTransferEntryPointsWaitLongerThanPhoneBudgetWithoutResending() = runTest {
        val h = I12CommandHarness()
        val remote = PhoneRemote(backgroundScope, state(), h)
        remote.transfer("phone")
        runCurrent()
        assertEquals(11_000L, h.timeouts.single())
        advanceTimeBy(8_100)
        runCurrent()
        assertTrue(remote.nowPlaying.value.busy)
        h.ack()
        runCurrent()
        val direct = async { h.send(Command.Transfer("speaker")) }
        val waited = async { remote.transferAndWait("phone") }
        runCurrent()
        assertEquals(listOf(11_000L, 11_000L, 11_000L), h.timeouts)
        val count = h.sent.size
        advanceTimeBy(11_000)
        runCurrent()
        assertNull(direct.await())
        assertFalse(waited.await())
        assertEquals(count, h.sent.size)
    }

    @Test fun ordinaryCommandsHaveMarginOverEightSecondsAndLikeKeepsTen() = runTest {
        val h = I12CommandHarness()
        val remote = PhoneRemote(backgroundScope, state(), h)
        runCurrent()
        remote.next()
        remote.previous()
        remote.seekTo(1_000)
        remote.setLiked(true)
        runCurrent()
        assertTrue(h.timeouts.all { it > 8_000 })
        assertEquals(10_000L, h.timeouts.last())
    }
}
