package dev.pampa.fluidify.wear.standalone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How often the watch looks at something that is not ready yet, and how long it leaves a failure alone. */
class PollingTest {

    @Test
    fun lookingBacksOffFromAQuarterOfASecondToOne() {
        assertEquals(listOf(250L, 500L, 1_000L, 1_000L, 1_000L), (0..4).map(PollBackoff::delayMs))
    }

    @Test
    fun aNegativeCountIsTheFirstLook() {
        assertEquals(250L, PollBackoff.delayMs(-3))
    }

    @Test
    fun aFailedCoverWaitsHalfAMinuteThenDoublesUpToTenMinutes() {
        assertEquals(30_000L, ArtRetry.waitMs(1))
        assertEquals(60_000L, ArtRetry.waitMs(2))
        assertEquals(120_000L, ArtRetry.waitMs(3))
        assertEquals(240_000L, ArtRetry.waitMs(4))
        assertEquals(480_000L, ArtRetry.waitMs(5))
        assertEquals(ArtRetry.MAX_MS, ArtRetry.waitMs(6))
    }

    @Test
    fun theWaitNeverOverflowsHoweverManyTimesItFailed() {
        assertEquals(ArtRetry.MAX_MS, ArtRetry.waitMs(1_000))
        assertTrue(ArtRetry.waitMs(0) >= ArtRetry.MIN_MS)
    }
}
