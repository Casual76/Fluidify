package dev.pampa.fluidify.wear.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class TransferRetryTest {

    @Test
    fun aDroppedChannelIsAskedForAgain() {
        assertTrue(TransferRetry.shouldRetry(IOException("Channel closed unexpectedly before stream was finished"), 0))
        assertTrue(TransferRetry.shouldRetry(IllegalStateException("cut off at 10 of 20"), 2))
    }

    @Test
    fun aRefusalIsNot() {
        assertFalse(TransferRetry.shouldRetry(TransferRefused("not on the phone"), 0))
        assertFalse(TransferRetry.shouldRetry(IllegalArgumentException("not a track"), 0))
        assertFalse(TransferRetry.shouldRetry(null, 0))
    }

    @Test
    fun itGivesUpAfterAFewTries() {
        assertFalse(TransferRetry.shouldRetry(IOException("gone"), TransferRetry.MAX_RETRIES))
    }

    @Test
    fun theWaitsGrow() {
        assertEquals(2_000L, TransferRetry.backoffMs(0))
        assertTrue(TransferRetry.backoffMs(1) > TransferRetry.backoffMs(0))
        assertTrue(TransferRetry.backoffMs(2) > TransferRetry.backoffMs(1))
    }
}
