package dev.pampa.fluidify.wear.protocol.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VolumeCoalescerTest {

    @Test
    fun firstTurnIsSentAtOnceThenThrottled() {
        val v = VolumeCoalescer(intervalMs = 100, step = 0.1f)
        v.syncFromRemote(0.5f)
        v.turn(1f)
        assertEquals(0.6f, v.take(0)!!, 1e-4f)
        v.turn(1f)
        assertNull(v.take(50))
        v.turn(1f)
        assertEquals(0.8f, v.take(100)!!, 1e-4f)
        assertNull(v.take(500))
    }

    @Test
    fun remoteLevelIsIgnoredWhileTheHandTurns() {
        val v = VolumeCoalescer(intervalMs = 100, step = 0.1f)
        v.syncFromRemote(0.2f)
        v.turn(1f)
        v.syncFromRemote(0.9f)
        assertEquals(0.3f, v.target, 1e-4f)
        v.take(0)
        v.syncFromRemote(0.9f)
        assertEquals(0.9f, v.target, 1e-4f)
    }

    @Test
    fun clampedToTheRange() {
        val v = VolumeCoalescer(step = 0.5f)
        v.turn(-10f)
        assertEquals(0f, v.target, 0f)
        v.turn(10f)
        assertEquals(1f, v.target, 0f)
    }

    @Test
    fun turningBackToTheSentLevelSendsNothing() {
        val v = VolumeCoalescer(intervalMs = 100, step = 0.1f)
        v.syncFromRemote(0.5f)
        v.turn(1f)
        v.take(0)
        v.turn(1f)
        v.turn(-1f)
        assertNull(v.take(100))
    }
}
