package dev.lelonio.square.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueCycleAnchorTest {
    private fun track(n: Int) = PlayQueue.Track("spotify:track:$n", "Track $n", "Artist",
        durationMs = 1_000, artworkUri = null)
    private fun queue() = PlayQueue().apply { replace((0..4).map(::track), 2) }

    @Test fun anchorIsTheStartedOccurrenceAndSurvivesShuffleAndMoves() {
        val queue = queue()
        queue.currentIndex = 3
        queue.setShuffled(true)
        assertEquals("spotify:track:2", queue.items[queue.cycleAnchorIndex].uri)
        queue.move(queue.cycleAnchorIndex, queue.cycleAnchorIndex + 1, 4)
        assertEquals(4, queue.cycleAnchorIndex)
        queue.setShuffled(false)
        assertEquals(2, queue.cycleAnchorIndex)
    }

    @Test fun duplicateUrisNeverReplaceTheStartedOccurrence() {
        val queue = PlayQueue().apply { replace(listOf(track(1), track(2), track(1)), 2) }
        queue.move(0, 1, 2)
        assertEquals(1, queue.cycleAnchorIndex)
    }

    @Test fun removingAnchorUsesNextSurvivorAndTailWraps() {
        val queue = queue()
        queue.remove(1, 3)
        assertEquals("spotify:track:3", queue.items[queue.cycleAnchorIndex].uri)
        queue.remove(1, 3)
        assertEquals(0, queue.cycleAnchorIndex)
    }

    @Test fun albumBlockFollowsEarlierManualSongsBeforeThePlaylist() {
        val queue = queue()
        queue.insertNext(listOf(track(10)))
        queue.insertNext(listOf(track(20), track(21), track(22)))
        queue.insertNext(listOf(track(30)))
        assertEquals(listOf(0, 1, 2, 10, 20, 21, 22, 30, 3, 4),
            queue.items.map { it.uri.substringAfterLast(':').toInt() })
        assertTrue(queue.items.subList(3, 8).all { it.queued })
        assertEquals(2, queue.cycleAnchorIndex)
    }

    @Test fun insertionBeforeAnchorAndSavedPermutationPreserveOccurrence() {
        val queue = queue()
        queue.add(0, listOf(track(99)))
        assertEquals(3, queue.cycleAnchorIndex)
        queue.applyShuffleOrder(listOf(5, 4, 3, 2, 1, 0))
        assertEquals(2, queue.cycleAnchorIndex)
    }
}
