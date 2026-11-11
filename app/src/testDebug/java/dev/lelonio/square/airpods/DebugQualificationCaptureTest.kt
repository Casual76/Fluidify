// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods

import android.app.Application
import android.os.Looper
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Prepared synthetic host regressions only. No Bluetooth owner, account, device or hardware proof. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
class DebugQualificationCaptureTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun evidence(limit: Int = 4096) = DebugQualificationEvidence(limit, { 123L }, { 456L })
    private fun config(value: Int, generation: Long = 1) = QualificationEvent(
        QualificationStep.RX, QualificationPhase.OBSERVED, generation,
        property = CommandProperty.EAR_DETECTION_CONFIG, value = value, flags = 9)
    private fun ear(value: Int = 4, generation: Long = 1, raw: List<Int> = listOf(2, 2)) = QualificationEvent(
        QualificationStep.RX, QualificationPhase.OBSERVED, generation, value = value, flags = 6, values = raw)
    private fun directory(parent: File, receipt: DebugCaptureReceipt) =
        File(File(parent, DebugQualificationCapture.DIRECTORY), receipt.captureId)

    @Test fun savesEveryRowBeyondTheUiLimitAndBindsBothFilesWithHashes() = runBlocking<Unit> {
        val evidence = evidence()
        repeat(2200) { evidence.record(QualificationEvent(QualificationStep.MEDIA_OBSERVE,
            QualificationPhase.OBSERVED, generation = 1, value = it)) }
        evidence.record(config(1)); evidence.record(ear())
        val expected = evidence.export().toByteArray(Charsets.UTF_8)
        assertTrue(expected.size > 100_000)
        val parent = temporary.newFolder()
        val receipt = DebugQualificationCapture({ parent }).save(evidence)
        val directory = directory(parent, receipt)
        val journal = File(directory, DebugQualificationCapture.JOURNAL).readBytes()
        val projection = File(directory, DebugQualificationCapture.EAR).readBytes()
        val committed = JSONObject(File(directory, DebugQualificationCapture.RECEIPT).readText())
        assertArrayEquals(expected, journal)
        assertEquals(2202, receipt.rows)
        assertEquals(receipt.rows, journal.toString(Charsets.UTF_8).lineSequence().drop(1).count())
        assertEquals(journal.size, committed.getInt("journalBytes"))
        assertEquals(DebugQualificationCapture.sha256(journal), committed.getString("journalSha256"))
        assertEquals(projection.size, committed.getInt("earBytes"))
        assertEquals(DebugQualificationCapture.sha256(projection), committed.getString("earSha256"))
        assertEquals(receipt.snapshotSequence, JSONObject(projection.toString(Charsets.UTF_8)).getLong("snapshotSequence"))
        assertFalse(receipt.summary().contains("id=1;"))
        assertTrue(committed.getString("physical") == "NV" && !committed.getBoolean("productConformance"))
    }

    @Test fun oneSnapshotCannotMixLaterRxOrClearIntoTheProjection() = runBlocking<Unit> {
        val evidence = evidence()
        evidence.record(config(1)); evidence.record(ear(0, raw = listOf(0, 0)))
        val parent = temporary.newFolder()
        val writer = DebugCaptureFileWriter { file, bytes ->
            DebugAtomicCaptureWriter.write(file, bytes)
            if (file.name == DebugQualificationCapture.JOURNAL) {
                evidence.record(config(2)); evidence.record(ear()); evidence.clear()
            }
        }
        val receipt = DebugQualificationCapture({ parent }, writer).save(evidence)
        val directory = directory(parent, receipt)
        val projection = JSONObject(File(directory, DebugQualificationCapture.EAR).readText())
        assertEquals(2L, receipt.snapshotSequence)
        assertEquals(2, receipt.rows)
        assertEquals(1, projection.getJSONObject("earConfig").getInt("currentValue"))
        assertEquals(0, projection.getJSONObject("ear").getJSONObject("lastRx").getInt("normalized"))
        assertEquals(receipt.snapshotSequence, projection.getLong("snapshotSequence"))
        assertTrue(evidence.export().contains("deleted=4"))
        assertFalse(JSONObject(evidence.snapshot().earProjection).getJSONObject("earConfig").getBoolean("sourceRx"))
    }

    @Test fun unknownCandidateAndMissingRawNeverBecomeAnObservedConfiguration() {
        val evidence = evidence()
        evidence.record(config(1).copy(phase = QualificationPhase.UNCONFIRMED))
        evidence.record(config(1).copy(flags = 0)) // Unknown main observer has no trusted control payload.
        evidence.record(config(3)) // Outside the only accepted device configuration values.
        evidence.record(ear())
        var projection = JSONObject(evidence.snapshot().earProjection)
        assertTrue(projection.getJSONObject("earConfig").isNull("currentValue"))
        assertTrue(projection.getJSONObject("earConfig").isNull("lastRx"))
        assertFalse(projection.getJSONObject("earConfig").getBoolean("sourceRx"))
        val observed = projection.getJSONObject("ear").getJSONObject("lastRx")
        assertEquals(4, observed.getInt("normalized"))
        assertEquals(2, observed.getInt("firstRaw")); assertEquals(2, observed.getInt("secondRaw"))
        assertEquals(123L, observed.getLong("recordedElapsedMs"))
        assertEquals(456L, observed.getLong("recordedUtcMs"))
        assertEquals(1L, observed.getLong("generation"))
        evidence.record(ear(raw = emptyList()))
        projection = JSONObject(evidence.snapshot().earProjection)
        assertTrue(projection.getJSONObject("ear").getJSONObject("lastRx").isNull("firstRaw"))
        assertTrue(projection.getJSONObject("ear").getJSONObject("lastRx").isNull("secondRaw"))
    }

    @Test fun afterStopIncludesCleanupAndKeepsOldRxExplicitlyHistorical() = runBlocking<Unit> {
        val evidence = evidence()
        evidence.record(config(1)); evidence.record(ear())
        evidence.record(QualificationEvent(QualificationStep.CLOSE, QualificationPhase.REQUESTED, generation = 2))
        evidence.record(QualificationEvent(QualificationStep.CLOSE, QualificationPhase.COMPLETED, generation = 2))
        evidence.record(QualificationEvent(QualificationStep.MEDIA_CLOSE, QualificationPhase.COMPLETED, generation = 1))
        val parent = temporary.newFolder()
        val receipt = DebugQualificationCapture({ parent }).save(evidence)
        val directory = directory(parent, receipt)
        val journal = File(directory, DebugQualificationCapture.JOURNAL).readText()
        val projection = JSONObject(File(directory, DebugQualificationCapture.EAR).readText())
        assertTrue(journal.contains("step=CLOSE;phase=COMPLETED"))
        assertTrue(journal.contains("step=MEDIA_CLOSE;phase=COMPLETED"))
        assertTrue(journal.contains("inFlight=0"))
        assertTrue(projection.getBoolean("transportClosed"))
        assertEquals(2L, projection.getLong("currentGeneration"))
        assertTrue(projection.getJSONObject("earConfig").isNull("currentValue"))
        assertEquals(1, projection.getJSONObject("earConfig").getJSONObject("lastRx").getInt("value"))
        assertEquals(1L, projection.getJSONObject("ear").getJSONObject("lastRx").getLong("generation"))
        assertFalse(projection.getJSONObject("ear").getBoolean("current"))
    }

    @Test fun generationChangeAndClearCannotPromoteStaleConfiguration() {
        val evidence = evidence()
        evidence.record(config(1))
        evidence.record(QualificationEvent(QualificationStep.CONSTRUCT, QualificationPhase.REQUESTED, generation = 2))
        evidence.record(config(2, generation = 1))
        var configuration = JSONObject(evidence.snapshot().earProjection).getJSONObject("earConfig")
        assertTrue(configuration.isNull("currentValue"))
        assertEquals(1, configuration.getJSONObject("lastRx").getInt("value"))
        evidence.record(config(2, generation = 2))
        configuration = JSONObject(evidence.snapshot().earProjection).getJSONObject("earConfig")
        assertEquals(2, configuration.getInt("currentValue"))
        evidence.clear()
        configuration = JSONObject(evidence.snapshot().earProjection).getJSONObject("earConfig")
        assertTrue(configuration.isNull("currentValue")); assertTrue(configuration.isNull("lastRx"))
    }

    @Test fun failedSavePublishesNoReceiptAndPreservesThePreviousCapture() = runBlocking<Unit> {
        val evidence = evidence(); evidence.record(ear())
        val parent = temporary.newFolder()
        val previous = DebugQualificationCapture({ parent }).save(evidence)
        val previousDirectory = directory(parent, previous)
        val previousJournal = File(previousDirectory, DebugQualificationCapture.JOURNAL).readBytes()
        val writer = DebugCaptureFileWriter { file, bytes ->
            if (file.name == DebugQualificationCapture.EAR) throw IOException("synthetic write failure")
            DebugAtomicCaptureWriter.write(file, bytes)
        }
        try {
            DebugQualificationCapture({ parent }, writer).save(evidence)
            fail("A partial save must not return a receipt")
        } catch (_: IOException) { }
        assertArrayEquals(previousJournal, File(previousDirectory, DebugQualificationCapture.JOURNAL).readBytes())
        assertEquals(listOf(previous.captureId), File(parent, DebugQualificationCapture.DIRECTORY).listFiles()!!.map { it.name })
    }

    @Test fun privateDirectoryResolutionAndAllFileWritesRunAwayFromMain() = runBlocking<Unit> {
        val evidence = evidence(); evidence.record(ear())
        var writes = 0
        val writer = DebugCaptureFileWriter { file, bytes ->
            assertNotSame(Looper.getMainLooper(), Looper.myLooper())
            writes++
            DebugAtomicCaptureWriter.write(file, bytes)
        }
        DebugQualificationCapture({
            assertNotSame(Looper.getMainLooper(), Looper.myLooper())
            temporary.newFolder()
        }, writer).save(evidence)
        assertEquals(3, writes)
    }

    @Test fun aTruncatedFileCannotProduceASuccessReceipt() = runBlocking<Unit> {
        val evidence = evidence(); evidence.record(ear())
        val parent = temporary.newFolder()
        val writer = DebugCaptureFileWriter { file, bytes ->
            DebugAtomicCaptureWriter.write(file, bytes.copyOf(bytes.size / 2))
        }
        try {
            DebugQualificationCapture({ parent }, writer).save(evidence)
            fail("Readback must reject truncated bytes")
        } catch (_: IllegalStateException) { }
        assertTrue(File(parent, DebugQualificationCapture.DIRECTORY).listFiles().orEmpty().isEmpty())
    }
}
