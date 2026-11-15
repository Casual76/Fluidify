package dev.lelonio.square.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Prepared only. Interleavings use the same production ingress as the Activity. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class I06PlaybackIntentsTest {
    private class RecordingController {
        val operations = mutableListOf<String>()
        val blocks = mutableListOf<List<String>>()
        fun play(uri: String) { operations += "play:$uri" }
        fun add(items: List<String>) { blocks += items; operations += "add:${items.joinToString()}" }
    }

    @Test fun firstTapSurvivesConnectionAndLaterAdditionsRemainWholeBlocksInFifoOrder() = runTest {
        val failures = mutableListOf<Throwable>()
        val ingress = I06PlaybackIntents<String, RecordingController>(this, { it == "A" }, { failures += it })
        val controller = RecordingController()
        ingress.submit("A", replacesQueue = true) { target, _ -> target.play("chosen") }
        runCurrent()
        ingress.submit("A") { target, _ -> target.add(listOf("manual")) }
        ingress.submit("A") { target, _ -> target.add(listOf("album-1", "album-2", "album-3")) }
        assertTrue(controller.operations.isEmpty())
        ingress.connected(controller)
        runCurrent()
        assertEquals(listOf("play:chosen", "add:manual", "add:album-1, album-2, album-3"), controller.operations)
        assertEquals(listOf(listOf("manual"), listOf("album-1", "album-2", "album-3")), controller.blocks)
        assertTrue(failures.isEmpty())
    }

    @Test fun latestSelectionReplacesOlderWaitingPlayAndItsPendingAdditions() = runTest {
        val controller = RecordingController()
        val ingress = I06PlaybackIntents<String, RecordingController>(this, { true }, { throw it })
        ingress.submit("A", true) { target, _ -> target.play("old") }
        runCurrent() // The old play has already left the pending list and is awaiting connection.
        ingress.submit("A") { target, _ -> target.add(listOf("old-addition")) }
        ingress.submit("A", true) { target, _ -> target.play("latest") }
        ingress.submit("A") { target, _ -> target.add(listOf("latest-addition")) }
        ingress.connected(controller); runCurrent()
        assertEquals(listOf("play:latest", "add:latest-addition"), controller.operations)
    }

    @Test fun stopRevokesOldTapAndAReconnectionDoesNotReplayIt() = runTest {
        val controller = RecordingController()
        val ingress = I06PlaybackIntents<String, RecordingController>(this, { true }, { throw it })
        ingress.submit("A", true) { target, _ -> target.play("old") }
        runCurrent(); ingress.stop(); runCurrent()
        ingress.start(); ingress.connected(controller); runCurrent()
        assertTrue(controller.operations.isEmpty())
        ingress.submit("A", true) { target, _ -> target.play("new") }
        runCurrent()
        assertEquals(listOf("play:new"), controller.operations)
    }

    @Test fun accountSwitchDoesNotMoveAnOldTapToTheNewOwner() = runTest {
        var account = "A"
        val controller = RecordingController()
        val ingress = I06PlaybackIntents<String, RecordingController>(this, { it == account }, { throw it })
        ingress.submit("A") { target, _ -> target.add(listOf("A-only")) }
        runCurrent(); account = "B"; ingress.connected(controller); runCurrent()
        assertTrue(controller.operations.isEmpty())
        ingress.submit("B", true) { target, _ -> target.play("B") }
        runCurrent()
        assertEquals(listOf("play:B"), controller.operations)
    }

    @Test fun timeoutGivesOneFailureWithoutLateExecutionOrRetry() = runTest {
        val failures = mutableListOf<Throwable>()
        val controller = RecordingController()
        val ingress = I06PlaybackIntents<String, RecordingController>(this, { true }, { failures += it }, 50)
        ingress.submit("A", true) { target, _ -> target.play("expired") }
        runCurrent(); advanceTimeBy(51); runCurrent()
        assertEquals(1, failures.size)
        ingress.connected(controller); runCurrent()
        assertTrue(controller.operations.isEmpty())
    }

    @Test fun slowVoiceLookupCannotOverrideANewerDirectSelection() = runTest {
        val controller = RecordingController()
        val barrier = CompletableDeferred<Unit>()
        val ingress = I06PlaybackIntents<String, RecordingController>(this, { true }, { throw it })
        ingress.connected(controller)
        ingress.submit("A", true) { target, current ->
            barrier.await()
            if (current()) target.play("voice-result")
        }
        runCurrent()
        ingress.submit("A", true) { target, _ -> target.play("latest-row") }
        barrier.complete(Unit); runCurrent()
        assertEquals(listOf("play:latest-row"), controller.operations)
    }

    @Test fun newUserAttemptCanReconnectAfterFailureWithoutReplayingTheExpiredRequest() = runTest {
        val failures = mutableListOf<Throwable>()
        val controller = RecordingController()
        val ingress = I06PlaybackIntents<String, RecordingController>(this, { true }, { failures += it })
        ingress.submit("A", true) { target, _ -> target.play("failed") }
        runCurrent(); ingress.connected(null); runCurrent()
        assertEquals(1, failures.size)
        ingress.connecting()
        ingress.submit("A", true) { target, _ -> target.play("retry-tap") }
        runCurrent(); assertTrue(controller.operations.isEmpty())
        ingress.connected(controller); runCurrent()
        assertEquals(listOf("play:retry-tap"), controller.operations)
        assertEquals(1, failures.size)
    }
}
