package dev.lelonio.square.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueDurationTest {
    private fun duration(
        order: List<Int> = listOf(0, 1, 2, 3), current: Int = 1, anchor: Int = 1,
        boundary: QueueDurationBoundary = QueueDurationBoundary.END,
        speed: Float = 1f, lengths: List<Long?> = listOf(1_000, 2_000, 3_000, 4_000),
    ) = remainingQueueDuration(order, current, anchor, 500, speed, boundary) { lengths[it] }

    @Test fun endDoesNotIncludeEarlierTracks() {
        assertEquals(8_500L, duration()!!.milliseconds)
    }

    @Test fun repeatCycleWrapsUntilTheOccurrenceThatStartedTheRound() {
        assertEquals(9_500L, duration(boundary = QueueDurationBoundary.REPEAT_CYCLE)!!.milliseconds)
        assertEquals(7_500L, duration(current = 2, boundary = QueueDurationBoundary.REPEAT_CYCLE)!!.milliseconds)
    }

    @Test fun shuffledCycleFollowsActualOrderAndTheAnchorCanSitInTheMiddle() {
        assertEquals(5_500L, duration(order = listOf(2, 0, 3, 1), current = 1, anchor = 3,
            boundary = QueueDurationBoundary.REPEAT_CYCLE)!!.milliseconds)
    }

    @Test fun repeatOneOnlyCountsThePlayingOccurrenceAndSpeed() {
        assertEquals(750L, duration(boundary = QueueDurationBoundary.REPEAT_TRACK, speed = 2f)!!.milliseconds)
    }

    @Test fun missingLengthsAreAnExplicitLowerBound() {
        val result = duration(lengths = listOf(1_000, 2_000, null, 4_000))!!
        assertTrue(result.incomplete)
        assertEquals(5_500L, result.milliseconds)
        assertFalse(duration()!!.incomplete)
    }

    @Test fun staleCurrentOrAnchorCannotInventAnExactDuration() {
        assertNull(duration(current = 9))
        assertNull(duration(anchor = 9, boundary = QueueDurationBoundary.REPEAT_CYCLE))
    }

    @Test fun positionPastEndClampsInsteadOfSubtractingFromLaterTracks() {
        assertEquals(3_000L, remainingQueueDuration(listOf(0, 1), 0, 0, 9_000, 1f,
            QueueDurationBoundary.END) { listOf(1_000L, 3_000L)[it] }!!.milliseconds)
    }
}
