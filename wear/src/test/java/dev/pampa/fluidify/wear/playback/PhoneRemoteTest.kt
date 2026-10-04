package dev.pampa.fluidify.wear.playback

import dev.pampa.fluidify.wear.link.CommandChannel
import dev.pampa.fluidify.wear.link.LinkStatus
import dev.pampa.fluidify.wear.link.WatchState
import dev.pampa.fluidify.wear.protocol.Command
import dev.pampa.fluidify.wear.protocol.CommandAck
import dev.pampa.fluidify.wear.protocol.PlaybackSnapshot
import dev.pampa.fluidify.wear.protocol.TrackInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneRemoteTest {

    private class FakeChannel : CommandChannel {
        override val status: StateFlow<LinkStatus> = MutableStateFlow(LinkStatus.CONNECTED)
        val sent = mutableListOf<Command>()
        var reply = CompletableDeferred<CommandAck?>()
        override suspend fun send(command: Command): CommandAck? {
            sent += command
            return reply.await()
        }
    }

    private fun snapshot(seq: Long, playing: Boolean, liked: Boolean? = false) = PlaybackSnapshot(
        seq = seq,
        sentAtEpochMs = 1_000,
        track = TrackInfo("spotify:track:a", "A", durationMs = 100_000),
        positionMs = 10_000,
        sampledAtEpochMs = 1_000,
        isPlaying = playing,
        playWhenReady = playing,
        liked = liked,
    )

    private fun state(): WatchState = WatchState(File.createTempFile("snapshot", ".json").apply { delete() })

    @Test
    fun toggleShowsTheGuessAtOnce() = runTest(UnconfinedTestDispatcher()) {
        val watch = state().apply { accept(snapshot(1, playing = false)) }
        val channel = FakeChannel()
        val remote = PhoneRemote(backgroundScope, watch, channel)

        remote.togglePlay()

        assertTrue(remote.nowPlaying.value.snapshot!!.isPlaying)
        assertTrue(remote.nowPlaying.value.busy)
        assertEquals(listOf<Command>(Command.TogglePlay), channel.sent)
    }

    @Test
    fun aFailedCommandTakesTheGuessBack() = runTest(UnconfinedTestDispatcher()) {
        val watch = state().apply { accept(snapshot(1, playing = false)) }
        val channel = FakeChannel()
        val remote = PhoneRemote(backgroundScope, watch, channel)

        remote.togglePlay()
        channel.reply.complete(CommandAck(id = 1, ok = false, error = "phone-unavailable"))
        advanceUntilIdle()

        assertFalse(remote.nowPlaying.value.snapshot!!.isPlaying)
        assertFalse(remote.nowPlaying.value.busy)
    }

    @Test
    fun noAnswerTakesTheGuessBack() = runTest(UnconfinedTestDispatcher()) {
        val watch = state().apply { accept(snapshot(1, playing = true, liked = false)) }
        val channel = FakeChannel()
        val remote = PhoneRemote(backgroundScope, watch, channel)

        remote.setLiked(true)
        assertEquals(true, remote.nowPlaying.value.snapshot!!.liked)
        channel.reply.complete(null)
        advanceUntilIdle()

        assertEquals(false, remote.nowPlaying.value.snapshot!!.liked)
    }

    @Test
    fun aSnapshotThatDoesNotShowTheGuessWaitsForTheHold() = runTest(UnconfinedTestDispatcher()) {
        val watch = state().apply { accept(snapshot(1, playing = false)) }
        val channel = FakeChannel()
        val remote = PhoneRemote(backgroundScope, watch, channel)

        remote.togglePlay()
        // The snapshot sent with the ack, taken before the player moved: the button must not
        // flick back to "play" for it.
        watch.accept(snapshot(2, playing = false))
        assertTrue(remote.nowPlaying.value.snapshot!!.isPlaying)

        // The phone said yes, and still shows paused after a while (a headset paused it): then
        // that is what is true.
        channel.reply.complete(CommandAck(id = 1, ok = true, appliedSeq = 2))
        // The hold runs in the remote's (background) scope, which advanceUntilIdle leaves alone.
        advanceTimeBy(2_100)
        runCurrent()
        assertFalse(remote.nowPlaying.value.snapshot!!.isPlaying)
        assertEquals(2, remote.nowPlaying.value.snapshot!!.seq)
    }

    @Test
    fun aPlayingSnapshotFromLongAgoShowsPaused() = runTest(UnconfinedTestDispatcher()) {
        val now = System.currentTimeMillis()
        // Sent ten minutes ago, at 10 s into a 100 s song, on the same clock: the song ended long ago.
        val old = snapshot(1, playing = true).copy(sentAtEpochMs = now - 600_000, sampledAtEpochMs = now - 600_000)
        val watch = state().apply { accept(old, receivedAtMs = now - 600_000) }
        val remote = PhoneRemote(backgroundScope, watch, FakeChannel())

        val shown = remote.nowPlaying.value.snapshot!!
        assertFalse(shown.isPlaying)
        assertFalse(shown.playWhenReady)
        assertEquals(100_000, shown.positionMs)
    }

    @Test
    fun aConfirmedCommandKeepsShowingTheResult() = runTest(UnconfinedTestDispatcher()) {
        val watch = state().apply { accept(snapshot(1, playing = false)) }
        val channel = FakeChannel()
        val remote = PhoneRemote(backgroundScope, watch, channel)

        remote.togglePlay()
        watch.accept(snapshot(2, playing = true))
        channel.reply.complete(CommandAck(id = 1, ok = true, appliedSeq = 2))
        advanceUntilIdle()

        assertTrue(remote.nowPlaying.value.snapshot!!.isPlaying)
        assertFalse(remote.nowPlaying.value.busy)
    }

    @Test
    fun staleSnapshotsAreIgnored() {
        val watch = state()
        assertTrue(watch.accept(snapshot(5, playing = true)))
        assertFalse(watch.accept(snapshot(4, playing = false)))
        assertTrue(watch.current.value!!.snapshot.isPlaying)
    }
}
