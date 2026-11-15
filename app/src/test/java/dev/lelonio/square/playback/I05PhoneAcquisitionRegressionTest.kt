package dev.lelonio.square.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class I05PhoneAcquisitionRegressionTest {
    @Test fun wrongContentRemoteProjectionBufferingAndInactiveSinkAreNeverAcquisition() {
        val acquired = I05PhonePlaybackFacts(true, true, false, true, "spotify:track:A", true)
        assertTrue(acquired.acquiredFor("spotify:track:A"))
        for (unacquired in listOf(
            acquired.copy(nativeLocal = false), acquired.copy(spotifySource = false),
            acquired.copy(remoteProjection = true), acquired.copy(playing = false),
            acquired.copy(mediaId = "spotify:track:B"), acquired.copy(rendererActive = false),
        )) assertFalse(unacquired.acquiredFor("spotify:track:A"))
    }

    @Test fun queuedResumeAndBufferingNeverCompleteSuccessBeforeLocalAudioAcquisition() = runTest {
        val done = CompletableDeferred<Boolean>()
        var requested = false
        var actualAudio = false
        val consumer = launch {
            i05CompletePhoneAcquisition(done, { true }, 4_000) { current ->
                requested = true
                i05AwaitPhoneAcquisition(current, { actualAudio })
            }
        }
        runCurrent()
        assertTrue(requested)
        advanceTimeBy(500); runCurrent()
        assertFalse(done.isCompleted)
        actualAudio = true
        advanceTimeBy(100); runCurrent()
        assertTrue(done.await())
        consumer.join()
    }

    @Test fun errorMissingStateRevocationAndTimeoutAlwaysCloseFalse() = runTest {
        for (scenario in 0..4) {
            val done = CompletableDeferred<Boolean>()
            var account = scenario != 0
            val consumer = launch {
                i05CompletePhoneAcquisition(done, { account }, 400) { current ->
                    when (scenario) {
                        1 -> false // no remote playback state / unavailable service
                        2 -> throw IllegalStateException("resume rejected")
                        else -> i05AwaitPhoneAcquisition(current, { false })
                    }
                }
            }
            runCurrent()
            if (scenario == 3) account = false
            advanceTimeBy(500); runCurrent()
            consumer.join()
            assertEquals("scenario=$scenario", false, done.await())
        }
    }

    @Test fun consumerCancellationClosesFalseAndCallerCancellationPreventsEffects() = runTest {
        val done = CompletableDeferred<Boolean>()
        val consumer = launch {
            i05CompletePhoneAcquisition(done, { true }, 4_000) { awaitCancellation() }
        }
        runCurrent()
        consumer.cancelAndJoin()
        assertFalse(done.await())
        val expired = CompletableDeferred<Boolean>().apply { cancel() }
        var effects = 0
        i05CompletePhoneAcquisition(expired, { true }, 4_000) { effects++; true }
        assertEquals(0, effects)
    }

    @Test fun accountOrCallerExpiredDuringTheLastAwaitCannotReceiveSuccess() = runTest {
        for (cancelReceipt in listOf(false, true)) {
            var current = true
            val done = CompletableDeferred<Boolean>()
            val barrier = CompletableDeferred<Unit>()
            val consumer = launch {
                i05CompletePhoneAcquisition(done, { current }, 4_000) { barrier.await(); true }
            }
            runCurrent()
            if (cancelReceipt) done.cancel() else current = false
            barrier.complete(Unit)
            consumer.join()
            if (cancelReceipt) assertTrue(done.isCancelled) else assertFalse(done.await())
        }
    }

    @Test fun timeoutStopsTheOldOwnerInsteadOfIssuingLateEffects() = runTest {
        val done = CompletableDeferred<Boolean>()
        var effects = 0
        i05CompletePhoneAcquisition(done, { true }, 50) {
            delay(100)
            effects++
            true
        }
        advanceTimeBy(500); runCurrent()
        assertFalse(done.await())
        assertEquals(0, effects)
    }
}
