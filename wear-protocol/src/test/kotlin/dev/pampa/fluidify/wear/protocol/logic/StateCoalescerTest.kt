package dev.pampa.fluidify.wear.protocol.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StateCoalescerTest {

    @Test
    fun firstChangeWaitsForTheGatheringWindow() {
        val c = StateCoalescer(gatherMs = 150, minIntervalMs = 250)
        assertEquals(1_150, c.offer(1_000))
        assertFalse(c.isDue(1_149))
        assertTrue(c.isDue(1_150))
    }

    @Test
    fun laterChangesInTheWindowRideAlong() {
        val c = StateCoalescer(gatherMs = 150, minIntervalMs = 250)
        c.offer(1_000)
        assertEquals(1_150, c.offer(1_040))
        assertEquals(1_150, c.offer(1_149))
    }

    @Test
    fun consecutiveSendsAreSpacedAndTheTrailingEdgeIsKept() {
        val c = StateCoalescer(gatherMs = 150, minIntervalMs = 250)
        c.offer(1_000)
        c.markSent(1_150)
        // A change right after the send waits for the spacing, not just the window.
        assertEquals(1_400, c.offer(1_160))
        c.markSent(1_400)
        assertNull(c.dueAt())
    }

    @Test
    fun relaxedChangesWaitLongerButUrgentOnesPullThemForward() {
        val c = StateCoalescer(gatherMs = 150, minIntervalMs = 250, relaxedMs = 2_000)
        assertEquals(3_000, c.offer(1_000, urgent = false))
        assertEquals(1_150, c.offer(1_100, urgent = true))
    }

    @Test
    fun nothingPendingAfterSend() {
        val c = StateCoalescer()
        c.offer(0)
        c.markSent(150)
        assertFalse(c.hasPending)
        assertFalse(c.isDue(10_000))
    }
}
