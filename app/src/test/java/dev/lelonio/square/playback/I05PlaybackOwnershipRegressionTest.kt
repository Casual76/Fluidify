package dev.lelonio.square.playback

import dev.lelonio.square.backend.spotify.SpotifyVideoMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class I05PlaybackOwnershipRegressionTest {
    @After fun resetMode() { SpotifyVideoMode.reset() }

    @Test fun oldVideoCannotInstallAfterAnyAwaitWhenSelectionPauseOccurrenceAccountOrRequestChanged() = runTest {
        for (stage in 0..3) for (intervention in 0..6) {
            var selection = SpotifyVideoMode.Selection(Any(), "spotify:track:A", 0, Any(), "occurrence-A", 0, true)
            var account = true
            val binding = SpotifyVideoMode.bindSelection { selection }
            try {
                val request = SpotifyVideoMode.Request.Watch("video-A", 15_000)
                val lease = I05PlaybackRequestLease {
                    account && SpotifyVideoMode.isCurrent(request) && request.selection == selection
                }
                val waits = List(4) { CompletableDeferred<Unit>() }
                val installed = mutableListOf<String>()
                var stagesStarted = 0
                val pending = launch {
                    // Token, manifest, manifest write, then admission through the account mutex.
                    repeat(3) { i -> lease.await { stagesStarted++; waits[i].await() } }
                    waits[3].await()
                    lease.requireCurrent()
                    installed += request.selection!!.mediaId
                }
                runCurrent()
                repeat(stage) { waits[it].complete(Unit); runCurrent() }
                when (intervention) {
                    0 -> selection = selection.copy(mediaId = "spotify:track:B", index = 1, windowUid = Any())
                    1 -> selection = selection.copy(occurrenceId = "second-copy-of-A")
                    2 -> selection = selection.copy(revision = 2) // pause then play: same final playWhenReady
                    3 -> selection = selection.copy(owner = Any()) // same URI on a different player
                    4 -> SpotifyVideoMode.Request.Watch("new-video", 0)
                    5 -> SpotifyVideoMode.reset()
                    6 -> account = false
                }
                waits.forEach { it.complete(Unit) }
                pending.join()
                assertTrue("stage=$stage intervention=$intervention", installed.isEmpty())
                assertEquals(minOf(stage + 1, 3), stagesStarted)
            } finally { binding.close() }
        }
    }

    @Test fun requestCapturesItsOriginalOccurrenceBeforeCollectorAndBindingIsServiceOwned() {
        val a = SpotifyVideoMode.Selection(Any(), "spotify:track:A", 0, Any(), "A-1", 7, false)
        val first = SpotifyVideoMode.bindSelection { a }
        val pending = SpotifyVideoMode.Request.Watch("video-A", 4_000)
        val b = a.copy(mediaId = "spotify:track:B", occurrenceId = "B-1", revision = 8)
        val second = SpotifyVideoMode.bindSelection { b }
        first.close()
        assertEquals(a, pending.selection)
        assertFalse(pending.selection!!.playWhenReady)
        assertEquals(b, SpotifyVideoMode.Request.Watch("video-B", 0).selection)
        second.close()
        assertNull(SpotifyVideoMode.Request.Watch("no-service", 0).selection)
    }

    @Test fun cancellingFadeRestoresBaselineWithoutPausingAndNewTimerStartsFromThatBaseline() = runTest {
        var level = .8f
        var pauses = 0
        val writes = mutableListOf<Float>()
        suspend fun fade() = i05FadeAndPause(4, 100, { true }, { level },
            { level = it; writes += it }, { pauses++ })
        val first = launch { fade() }
        runCurrent()
        advanceTimeBy(110)
        first.cancelAndJoin()
        assertEquals(.8f, level, 0f)
        assertEquals(0, pauses)
        val next = launch { fade() }
        runCurrent()
        assertEquals(.6f, level, .0001f)
        next.cancelAndJoin()
        assertEquals(.8f, writes.last(), 0f)
    }

    @Test fun explicitVolumeEvenAtTheAttenuatedLevelVetoesFadeCleanup() = runTest {
        for (sameLevel in listOf(false, true)) {
            var level = 1f
            var volumeRevision = 0
            val ownedRevision = volumeRevision
            var pauses = 0
            val fading = launch {
                i05FadeAndPause(10, 100, { volumeRevision == ownedRevision }, { level },
                    { level = it }, { pauses++ })
            }
            runCurrent()
            val requested = if (sameLevel) level else .3f
            volumeRevision++
            level = requested
            fading.cancelAndJoin()
            assertEquals(requested, level, 0f)
            assertEquals(0, pauses)
        }
    }

    @Test fun playerOrAccountReplacementAndForeignVolumeCannotBeOverwrittenByOldFinally() = runTest {
        for (replacement in 0..2) {
            var owner = true
            var oldLevel = 1f
            var writes = 0
            val fading = launch {
                i05FadeAndPause(10, 100, { owner }, { oldLevel },
                    { oldLevel = it; writes++ }, { fail("Revoked fade paused a player") })
            }
            runCurrent()
            val writtenBefore = writes
            if (replacement < 2) owner = false else oldLevel = .2f
            fading.cancelAndJoin()
            assertEquals(writtenBefore, writes)
        }
    }

    @Test fun ordinaryFadePausesAtSilenceAndRestoresTheOriginalVolume() = runTest {
        var level = .7f
        var pausedAt: Float? = null
        i05FadeAndPause(3, 100, { true }, { level }, { level = it }, { pausedAt = level })
        assertEquals(0f, pausedAt!!, 0f)
        assertEquals(.7f, level, 0f)
    }

    @Test fun transportPauseDuringFadeDoesNotForfeitItsIndependentVolumeRestore() = runTest {
        var level = 1f
        var manuallyPaused = false
        val fade = launch {
            i05FadeAndPause(10, 100, { true }, { level }, { level = it }, { manuallyPaused = true })
        }
        runCurrent()
        manuallyPaused = true
        fade.cancelAndJoin()
        assertTrue(manuallyPaused)
        assertEquals(1f, level, 0f)
    }
}
