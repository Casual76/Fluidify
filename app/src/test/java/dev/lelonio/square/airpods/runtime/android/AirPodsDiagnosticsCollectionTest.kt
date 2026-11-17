package dev.lelonio.square.airpods.runtime.android

import dev.lelonio.square.airpods.AacpStage
import org.junit.Assert.*
import org.junit.Test

class AirPodsDiagnosticsCollectionTest {
    @Test fun collectionRequiresExplicitStartAndStopPreservesHistory() {
        val log = AirPodsSanitizedDiagnostics { 1L }
        log.record(DiagnosticKind.ERROR, AacpStage.ERROR)
        assertFalse(log.collecting.value)
        assertTrue(log.records.value.isEmpty())
        assertTrue(log.startCollection())
        assertFalse(log.startCollection())
        log.record(DiagnosticKind.SNAPSHOT, AacpStage.SUBSCRIBED)
        val before = log.records.value
        log.stopCollection()
        log.record(DiagnosticKind.STOP, AacpStage.CLOSED)
        assertFalse(log.collecting.value)
        assertEquals(before, log.records.value)
        assertTrue(log.export().contains("SNAPSHOT SUBSCRIBED"))
    }

    @Test fun clearWhileStoppedDoesNotRestartOrReuseIdentifiers() {
        val log = AirPodsSanitizedDiagnostics { 2L }
        log.startCollection()
        log.record(DiagnosticKind.STAGE, AacpStage.CONNECTED)
        val firstId = log.records.value.single().id
        log.stopCollection()
        log.clear()
        assertTrue(log.records.value.isEmpty())
        assertFalse(log.collecting.value)
        log.startCollection()
        log.record(DiagnosticKind.STAGE, AacpStage.SUBSCRIBED)
        assertTrue(log.records.value.single().id > firstId)
        log.delete(firstId)
        assertEquals(1, log.records.value.size)
    }
    @Test fun repeatedStatesDoNotPublishStagesButRealTransitionsAndExplicitSnapshotsDo() {
        val log = AirPodsSanitizedDiagnostics { 3L }
        log.startCollection()
        repeat(1000) { log.record(DiagnosticKind.STAGE, AacpStage.CONNECTED) }
        assertEquals(1, log.records.value.size)
        log.record(DiagnosticKind.SNAPSHOT, AacpStage.CONNECTED)
        log.record(DiagnosticKind.STAGE, AacpStage.SUBSCRIBED)
        log.record(DiagnosticKind.STAGE, AacpStage.CONNECTED)
        assertEquals(4, log.records.value.size)
        log.clear()
        log.record(DiagnosticKind.STAGE, AacpStage.CONNECTED)
        assertTrue(log.records.value.isEmpty())
        log.stopCollection(); log.startCollection()
        log.record(DiagnosticKind.STAGE, AacpStage.CONNECTED)
        assertEquals(1, log.records.value.size)
    }
}
