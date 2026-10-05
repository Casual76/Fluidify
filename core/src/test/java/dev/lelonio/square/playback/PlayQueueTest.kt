package dev.lelonio.square.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The queue's bookkeeping: shuffle, moves and removals keep the playing track and the way back
 * to the original order. Each of these has broken before — a drag that reshuffled the whole queue
 * under the finger, a duplicate track that sent the current index to the other copy.
 */
class PlayQueueTest {

    private fun track(n: Int) = PlayQueue.Track(
        uri = "spotify:track:$n",
        title = "Track $n",
        artist = "Artist",
        durationMs = 1_000,
        artworkUri = null,
    )

    private fun queue(size: Int, at: Int = 0) = PlayQueue().apply {
        replace((0 until size).map(::track), at)
    }

    private val PlayQueue.uris get() = items.map { it.uri.removePrefix("spotify:track:").toInt() }

    @Test fun shufflePutsTheCurrentTrackFirstAndUnshuffleRestoresTheOrder() {
        val queue = queue(20, at = 7)
        queue.setShuffled(true)
        assertTrue(queue.isShuffled)
        assertEquals(0, queue.currentIndex)
        assertEquals(7, queue.uris.first())
        assertEquals((0 until 20).toSet(), queue.uris.toSet())

        // Move on two tracks in the shuffled order, then go back to the playlist's order.
        queue.currentIndex = 2
        val playing = queue.items[2]
        queue.setShuffled(false)
        assertFalse(queue.isShuffled)
        assertEquals((0 until 20).toList(), queue.uris)
        assertEquals(playing, queue.items[queue.currentIndex])
    }

    @Test fun movingARowKeepsTheShuffleAndThePlayingTrack() {
        val queue = queue(10, at = 0)
        queue.setShuffled(true)
        val before = queue.uris
        val playing = queue.items[queue.currentIndex]

        queue.move(3, 4, 6)

        assertTrue("a move must not reshuffle", queue.isShuffled)
        val expected = before.toMutableList().apply { add(6, removeAt(3)) }
        assertEquals(expected, queue.uris)
        assertEquals(playing, queue.items[queue.currentIndex])

        queue.setShuffled(false)
        assertEquals((0 until 10).toList(), queue.uris)
    }

    @Test fun movingThePlayingTrackCarriesTheIndexWithIt() {
        val queue = queue(6, at = 1)
        queue.move(1, 2, 4)
        assertEquals(4, queue.currentIndex)
        assertEquals(1, queue.uris[queue.currentIndex])
    }

    @Test fun duplicatesDoNotConfuseTheCurrentIndex() {
        val queue = PlayQueue().apply { replace(listOf(track(1), track(2), track(1), track(3)), 2) }
        queue.move(0, 1, 3)
        // The copy that was playing (the second "1") moved one place up; the first copy went last.
        assertEquals(listOf(2, 1, 3, 1), queue.uris)
        assertEquals(1, queue.currentIndex)
    }

    @Test fun removingBeforeTheCurrentTrackShiftsIt() {
        val queue = queue(8, at = 5)
        queue.remove(1, 3)
        assertEquals(3, queue.currentIndex)
        assertEquals(5, queue.uris[queue.currentIndex])
    }

    @Test fun insertNextGoesAfterTheCurrentTrackAndAfterEarlierQueuedOnes() {
        val queue = queue(5, at = 1)
        queue.insertNext(listOf(track(100)))
        queue.insertNext(listOf(track(101)))
        assertEquals(listOf(0, 1, 100, 101, 2, 3, 4), queue.uris)
        assertTrue(queue.items[2].queued)
    }

    @Test fun aSavedShuffleIsRestoredExactly() {
        val queue = queue(6)
        queue.setShuffled(true)
        val order = queue.shuffleOrder!!
        val shuffled = queue.uris

        val restored = queue(6)
        restored.applyShuffleOrder(order)
        assertEquals(shuffled, restored.uris)
        assertTrue(restored.isShuffled)

        val refused = queue(6)
        refused.applyShuffleOrder(listOf(0, 0, 1, 2, 3, 4))
        assertFalse("a permutation that is not one is ignored", refused.isShuffled)
    }
}
