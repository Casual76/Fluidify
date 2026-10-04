package dev.lelonio.square.wear

import dev.lelonio.square.io.WriteWatchdog
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class WriteWatchdogTest {
    @Test fun stalledWriteClosesTheChannelAndUnblocksTheWriter() {
        val writing = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val output = object : OutputStream() {
            override fun write(value: Int) {
                writing.countDown()
                if (!disconnected.await(3, TimeUnit.SECONDS)) error("channel was never closed")
                throw IOException("channel closed")
            }
        }
        WriteWatchdog(output, timeoutMs = 100, deadlineMs = 2_000) { disconnected.countDown() }.use { guarded ->
            Thread {
                try { guarded.write(1) } catch (_: IOException) { exited.countDown() }
            }.start()
            assertTrue(writing.await(1, TimeUnit.SECONDS))
            assertTrue(exited.await(2, TimeUnit.SECONDS))
        }
    }
}
