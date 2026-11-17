// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.android

import dev.lelonio.square.airpods.runtime.AirPodsRxCapture
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class AirPodsRxCaptureSessionTest {
    @get:Rule val temporary = TemporaryFolder()
    private suspend fun await(test: () -> Boolean) = withTimeout(3_000) { while (!test()) delay(5) }
    @Test fun firstTxIsCapturedBeforeTheIoExporterStartsAndCancelledLaunchCannotLeakSink() = runTest {
        val directory = temporary.newFolder(); File(directory, "airpods-rx.enabled").writeText("")
        val worker = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + worker)
        val job = requireNotNull(AirPodsRxCaptureSession.open(directory, scope, "debug", 10, worker))
        try {
            assertTrue(AirPodsRxCapture.enabled)
            assertFalse(File(directory, "airpods-rx.txt").exists())
            AirPodsRxCapture.recordTx(9, 0x28, byteArrayOf(1, 0, 0, 0))
            runCurrent()
            assertTrue(File(directory, "airpods-rx.txt").readText().contains("TX op=9 id=28 len=4 bytes=01000000"))
            job.cancel(); runCurrent()
            assertFalse(AirPodsRxCapture.enabled)
            scope.cancel()
            requireNotNull(AirPodsRxCaptureSession.open(directory, scope, "debug", 10, worker))
            runCurrent()
            assertFalse(AirPodsRxCapture.enabled)
        } finally { scope.cancel(); runCurrent() }
    }
    @Test fun onlyDevAndDebugWithAnExistingFlagCanCapture() = runBlocking {
        val directory = temporary.newFolder(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            assertNull(AirPodsRxCaptureSession.open(directory, scope, "debug"))
            File(directory, "airpods-rx.enabled").writeText("")
            assertNull(AirPodsRxCaptureSession.open(directory, scope, "release"))
            assertNull(AirPodsRxCaptureSession.open(null, scope, "debug"))
            for (type in listOf("dev", "debug")) {
                val job = requireNotNull(AirPodsRxCaptureSession.open(directory, scope, type, 10))
                await { AirPodsRxCapture.enabled }
                AirPodsRxCapture.record(9, 0x0d, byteArrayOf(2, 0, 0, 0))
                job.cancelAndJoin()
                assertFalse(AirPodsRxCapture.enabled)
                assertTrue(File(directory, "airpods-rx.txt").readText().contains("RX op=9"))
                assertFalse(File(directory, "airpods-rx.tmp").exists())
            }
        } finally { scope.cancel() }
    }
    @Test fun reconnectBeforeFirstIoTurnPreservesThePreviousMemoryCapture() = runTest {
        val directory = temporary.newFolder(); File(directory, "airpods-rx.enabled").writeText("")
        val worker = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + worker)
        try {
            requireNotNull(AirPodsRxCaptureSession.open(directory, scope, "debug", 10, worker))
            AirPodsRxCapture.recordTx(9, 0x0d, byteArrayOf(2, 0, 0, 0))
            requireNotNull(AirPodsRxCaptureSession.open(directory, scope, "debug", 10, worker))
            AirPodsRxCapture.recordTx(9, 0x0d, byteArrayOf(4, 0, 0, 0))
            runCurrent()
            assertTrue(File(directory, "airpods-rx.prev.txt").readText().contains("bytes=02000000"))
            val current = File(directory, "airpods-rx.txt").readText()
            assertTrue(current.contains("bytes=04000000"))
            assertFalse(current.contains("bytes=02000000"))
        } finally { scope.cancel(); runCurrent() }
        assertFalse(AirPodsRxCapture.enabled)
    }
    @Test fun reconnectFlushesAndRotatesPreviousSessionAndOldFinallyCannotOverwriteNewFile() = runBlocking {
        val directory = temporary.newFolder(); File(directory, "airpods-rx.enabled").writeText("")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val output = File(directory, "airpods-rx.txt"); val previous = File(directory, "airpods-rx.prev.txt")
        try {
            val first = requireNotNull(AirPodsRxCaptureSession.open(directory, scope, "debug", 10))
            await { AirPodsRxCapture.enabled }
            AirPodsRxCapture.record(9, 0x0d, byteArrayOf(2, 0, 0, 0))
            val second = requireNotNull(AirPodsRxCaptureSession.open(directory, scope, "debug", 10))
            await { previous.isFile && previous.readText().contains("bytes=02000000") }
            // Rotation is followed by installation under the same lock. Wait for the new export.
            await { output.isFile && !output.readText().contains("bytes=02000000") && AirPodsRxCapture.enabled }
            AirPodsRxCapture.recordTx(9, 0x0d, byteArrayOf(4, 0, 0, 0))
            first.cancelAndJoin()
            assertTrue(AirPodsRxCapture.enabled)
            second.cancelAndJoin()
            assertTrue(previous.readText().contains("bytes=02000000"))
            assertTrue(output.readText().contains("TX op=9 id=d len=4 bytes=04000000"))
            assertFalse(output.readText().contains("bytes=02000000"))
            assertFalse(AirPodsRxCapture.enabled)
        } finally { scope.cancel() }
    }
    @Test fun deletingFlagStopsCaptureAndFlushesFinalFile() = runBlocking {
        val directory = temporary.newFolder(); val flag = File(directory, "airpods-rx.enabled"); flag.writeText("")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val job = requireNotNull(AirPodsRxCaptureSession.open(directory, scope, "debug", 10))
            await { AirPodsRxCapture.enabled }
            AirPodsRxCapture.record(6, null, byteArrayOf(1, 0))
            assertTrue(flag.delete())
            withTimeout(3_000) { job.join() }
            val output = File(directory, "airpods-rx.txt").readText()
            assertFalse(AirPodsRxCapture.enabled)
            AirPodsRxCapture.record(6, null, byteArrayOf(0, 0))
            assertTrue(output.contains("bytes=0100"))
            assertEquals(output, File(directory, "airpods-rx.txt").readText())
        } finally { scope.cancel() }
    }
    @Test fun fileFailureDisablesOnlyOptionalCaptureWithoutFailingItsOwner() = runBlocking {
        val directory = temporary.newFolder(); File(directory, "airpods-rx.enabled").writeText("")
        assertTrue(File(directory, "airpods-rx.txt").mkdir())
        var failure: Throwable? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error -> failure = error })
        try {
            val job = requireNotNull(AirPodsRxCaptureSession.open(directory, scope, "debug", 10))
            withTimeout(3_000) { job.join() }
            assertNull(failure)
            assertFalse(AirPodsRxCapture.enabled)
            assertTrue(scope.isActive)
        } finally { scope.cancel() }
    }
}
