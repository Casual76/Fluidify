package dev.pampa.fluidify.wear.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** The ring looks for a frame less and less often while none comes. */
class AudioLightPollingTest {

    @Test
    fun noFrameMeansLookingLessAndLessOften() {
        assertEquals(100L, AudioLightPolling.quietMs(0))
        assertEquals(100L, AudioLightPolling.quietMs(2))
        assertEquals(250L, AudioLightPolling.quietMs(3))
        assertEquals(250L, AudioLightPolling.quietMs(9))
        assertEquals(500L, AudioLightPolling.quietMs(10))
        assertEquals(500L, AudioLightPolling.quietMs(10_000))
    }
}
