package dev.pampa.fluidify.wear.downloads

import dev.lelonio.square.io.ReadWatchdog
import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ReadWatchdogTest {
    @Test fun progressDoesNotExtendTheOverallDeadline() {
        val closed = CountDownLatch(1)
        val stream = object : InputStream() { override fun read(): Int = 1 }
        ReadWatchdog(stream, 5_000, deadlineMs = 100) { closed.countDown() }.use {
            repeat(10) { _ -> it.read() }
            assertTrue(closed.await(1, TimeUnit.SECONDS))
        }
    }
    @Test fun blockedReadIsReleasedByChannelClosure() {
        val closed = CountDownLatch(1)
        val stream = object : InputStream() {
            override fun read(): Int { check(closed.await(2, TimeUnit.SECONDS)); return -1 }
        }
        ReadWatchdog(stream, 100) { closed.countDown() }.use {
            assertEquals(-1, it.read())
            assertEquals(0L, closed.count)
        }
    }
    @Test fun normalCompletionCancelsTheGuard() {
        val closed = CountDownLatch(1)
        ReadWatchdog(byteArrayOf(1).inputStream(), 100) { closed.countDown() }.use { assertEquals(1, it.read()) }
        assertFalse(closed.await(250, TimeUnit.MILLISECONDS))
    }
}
