package dev.pampa.fluidify.wear.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A transfer reports every few percent, not every one. */
class ProgressThrottleTest {

    @Test
    fun theFirstProgressIsAlwaysReported() {
        assertTrue(ProgressThrottle().accept(0, 0L))
    }

    @Test
    fun aHundredPercentTransferReportsAboutTwentyTimes() {
        val throttle = ProgressThrottle()
        val reported = (0..100).filter { throttle.accept(it, it * 100L) }
        assertEquals(listOf(0, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 75, 80, 85, 90, 95, 100), reported)
    }

    @Test
    fun theLastOneIsReportedEvenIfItIsCloseToTheLast() {
        val throttle = ProgressThrottle()
        assertTrue(throttle.accept(97, 0L))
        assertFalse(throttle.accept(98, 100L))
        assertTrue(throttle.accept(100, 200L))
    }

    @Test
    fun aTransferThatCreepsStillShowsLifeEveryFewSeconds() {
        val throttle = ProgressThrottle()
        assertTrue(throttle.accept(10, 0L))
        assertFalse(throttle.accept(11, 1_000L))
        assertTrue(throttle.accept(11, 5_000L))
        assertFalse("nothing moved", throttle.accept(11, 20_000L))
    }

    @Test
    fun checksumsAreSixtyFourHexDigits() {
        assertTrue("a".repeat(64).isSha256())
        assertTrue("0123456789abcdefABCDEF".repeat(3).take(64).isSha256())
        assertFalse("a".repeat(63).isSha256())
        assertFalse("g".repeat(64).isSha256())
        assertFalse("".isSha256())
    }
}
