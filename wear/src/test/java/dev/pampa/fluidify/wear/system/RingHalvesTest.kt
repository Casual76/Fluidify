package dev.pampa.fluidify.wear.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RingHalvesTest {

    @Test
    fun theRightArcFillsFirst() {
        assertEquals(0f to 0f, RingHalves.split(0f))
        assertEquals(0.6f, RingHalves.split(0.3f).first, 1e-4f)
        assertEquals(0f, RingHalves.split(0.3f).second, 0f)
        assertEquals(1f to 0f, RingHalves.split(0.5f))
        assertEquals(1f, RingHalves.split(0.8f).first, 0f)
        assertEquals(0.6f, RingHalves.split(0.8f).second, 1e-4f)
        assertEquals(1f to 1f, RingHalves.split(1f))
    }

    @Test
    fun outOfRangeProgressIsClamped() {
        assertEquals(0f to 0f, RingHalves.split(-0.2f))
        assertEquals(1f to 1f, RingHalves.split(1.4f))
    }

    @Test
    fun theBottomOpeningClearsTheEdgeButtonsCorners() {
        // 240 dp: the button is 48% of the width, its lower corners about 29.5° either side of
        // the bottom; with the margin the opening is about 71°.
        val large = RingHalves.bottomGapDegrees(240f)
        assertEquals(71f, large, 1f)
        // 192 dp: a wider button for the screen (24% margins), so a wider opening.
        val small = RingHalves.bottomGapDegrees(192f)
        assertTrue("$small", small > large)
        assertTrue("$small", small < 90f)
    }
}
