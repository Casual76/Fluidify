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
import kotlinx.coroutines.test.advanceUntilIdle
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
    fun thePhonesNextWordReplacesTheGuess() = runTest(UnconfinedTestDispatcher()) {
        val watch = state().apply { accept(snapshot(1, playing = false)) }
        val channel = FakeChannel()
        val remote = PhoneRemote(backgroundScope, watch, channel)

        remote.togglePlay()
        // The phone says something else entirely (it was paused by a headset meanwhile).
        watch.accept(snapshot(2, playing = false))

        assertFalse(remote.nowPlaying.value.snapshot!!.isPlaying)
        assertEquals(2, remote.nowPlaying.value.snapshot!!.seq)
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
