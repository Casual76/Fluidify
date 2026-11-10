// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.android

import dev.lelonio.square.airpods.AacpTransport
import dev.lelonio.square.airpods.runtime.lifecycle.*
import dev.lelonio.square.airpods.runtime.storage.*
import org.junit.Assert.*
import org.junit.Test

class AndroidRuntimeContractsTest {
    private class Disk : RuntimePersistence {
        var bytes: ByteArray? = null
        override fun read() = bytes
        override fun commit(bytes: ByteArray): Boolean { this.bytes = bytes; return true }
    }
    private class F {
        val store = AirPodsRuntimeStore(Disk())
        val address = PairAddress("00:11:22:33:44:55")
        var starts = 0
        var exits: ExitHistory = ExitHistory.Available(emptyList())
        val gate = AirPodsStartGate(store, ExitHistoryProvider { exits }, RuntimeClock { 100 }, "new", 55, "own", 35,
            object : RuntimeOwnerFacade {
                override fun start(admission: ConnectionAdmission, generation: Long): Boolean { starts++; return true }
                override fun stop() {}
            })
        init { store.select(address).getOrThrow(); gate.explicitConsent(StartIngress.EXPLICIT_ENABLE) }
        fun admission() = ConnectionAdmission(address, "real-link-1", true, true, true, true, "build", store.snapshot().getOrThrow().start.consentGeneration,
            ConnectionPurpose.VERIFICATION_ONLY, explicitSetupVisible = true, productConformance = false)
    }
    @Test fun explicitVisibleVerificationCanOpenUnverifiedPairWithoutProductGate() {
        val f = F(); assertTrue(f.gate.attempt(StartIngress.EXPLICIT_ENABLE, f.admission()) is GateResult.Started)
        assertNull(f.store.snapshot().getOrThrow().pairs[f.address]?.verification)
    }
    @Test fun everyAutomaticIngressCannotBorrowVerificationPurpose() {
        StartIngress.entries.filter { it !in listOf(StartIngress.EXPLICIT_ENABLE, StartIngress.EXPLICIT_RESUME) }.forEach {
            val f = F(); assertEquals(GateResult.Denied(GateReason.SETUP_REQUIRED), f.gate.attempt(it, f.admission())); assertEquals(0, f.starts)
        }
    }
    @Test fun hiddenSetupCannotOpenVerification() {
        val f = F(); assertEquals(GateResult.Denied(GateReason.SETUP_REQUIRED), f.gate.attempt(StartIngress.EXPLICIT_ENABLE, f.admission().copy(explicitSetupVisible = false)))
    }
    @Test fun verificationDoesNotBypassSuppression() {
        val f = F(); f.gate.stop(); assertEquals(GateResult.Denied(GateReason.SUPPRESSED), f.gate.attempt(StartIngress.EXPLICIT_ENABLE, f.admission()))
    }
    @Test fun verificationDoesNotBypassNewSystemStop() {
        val f = F(); f.exits = ExitHistory.Available(listOf(OwnProcessExit(ExitKey(200,55,"own",10),ExitCause.USER_REQUESTED)))
        assertEquals(GateResult.Denied(GateReason.SUPPRESSED), f.gate.attempt(StartIngress.EXPLICIT_ENABLE, f.admission()))
    }
    @Test fun verificationDoesNotBypassPlatformOrHistory() {
        val f = F(); assertEquals(GateResult.Denied(GateReason.PERMISSION_DENIED), f.gate.attempt(StartIngress.EXPLICIT_ENABLE, f.admission().copy(bluetoothPermission = false)))
        f.exits = ExitHistory.QueryFailed
        assertEquals(GateResult.Denied(GateReason.HISTORY_FAILED), f.gate.attempt(StartIngress.EXPLICIT_ENABLE, f.admission()))
    }
    @Test fun completedSetupStillCannotOpenClosedProductGate() {
        val f = F(); f.store.recordSetup(f.address,PairVerification("A3063","build",1)).getOrThrow()
        assertEquals(GateResult.Denied(GateReason.SETUP_REQUIRED), f.gate.attempt(StartIngress.COMPANION, f.admission().copy(purpose = ConnectionPurpose.PRODUCT)))
    }
    @Test fun stableLifetimeDeduplicatesDifferentCallbacksButReconnectChangesIt() {
        val l = ConnectionLifetimes(); val a = PairAddress("00:11:22:33:44:55")
        val first = l.observe(a,true); repeat(5) { assertEquals(first,l.observe(a,true)) }
        assertNull(l.observe(a,false)); assertNotEquals(first,l.observe(a,true))
    }
    @Test fun staleGenerationCannotStopNewConnection() {
        val f = F(); val a = f.admission(); f.gate.attempt(StartIngress.EXPLICIT_ENABLE,a)
        assertEquals(GateResult.Denied(GateReason.STALE_GENERATION),f.gate.disconnected(a.connectionId,a.expectedConsentGeneration - 1))
        assertEquals(1,f.starts)
    }
    @Test fun v1MigrationPreservesConsentAndAlternateWithoutEnablingNewActions() {
        val address = PairAddress("00:11:22:33:44:55")
        // Frozen legacy wire fixture, independent of the current encoder and schema tails.
        val bytes = java.util.Base64.getDecoder().decode(LEGACY_V1)
        for (enabled in listOf(false, true)) {
            val v1 = bytes.clone().also { it[8] = if (enabled) 1 else 0 }
            val read = AirPodsRuntimeCodec.decode(v1)
            assertEquals(enabled, read.start.enabled); assertEquals(7L, read.start.consentGeneration)
            assertEquals(address, read.selectedPair); assertTrue(read.pendingCleanup.isEmpty())
            val pair = read.pairs.getValue(address)
            assertFalse(pair.headPacketAlternate); assertTrue(pair.host.earPause)
            assertFalse(pair.host.headGestures); assertFalse(pair.host.otherAppsEnabled)
            assertEquals(LongPressAction.CYCLE_NOISE_CONTROL_MODES, pair.leftLongPress)
            assertEquals(LongPressAction.CYCLE_NOISE_CONTROL_MODES, pair.rightLongPress)
            assertEquals(HeadAction.NONE, pair.nodAction); assertEquals(HeadAction.NONE, pair.shakeAction)
            assertNull(pair.verification)
            assertEquals(read, AirPodsRuntimeCodec.decode(AirPodsRuntimeCodec.encode(read)))
        }
    }
    @Test fun frozenV2MigratesActionsWithoutInventingCleanupOrConsent() {
        val read = AirPodsRuntimeCodec.decode(java.util.Base64.getDecoder().decode(LEGACY_V2))
        val pair = read.pairs.getValue(PairAddress("00:11:22:33:44:55"))
        assertFalse(read.start.enabled); assertEquals(7L, read.start.consentGeneration)
        assertTrue(read.pendingCleanup.isEmpty()); assertFalse(pair.headPacketAlternate)
        assertEquals(LongPressAction.DIGITAL_ASSISTANT, pair.leftLongPress)
        assertEquals(LongPressAction.CYCLE_NOISE_CONTROL_MODES, pair.rightLongPress)
        assertEquals(HeadAction.NEXT, pair.nodAction); assertEquals(HeadAction.PREVIOUS, pair.shakeAction)
        assertEquals(read, AirPodsRuntimeCodec.decode(AirPodsRuntimeCodec.encode(read)))
    }
    private companion object {
        // APR1, schema, StartState(gen=7), selected + one pair, migration=false. No v3 tail.
        const val LEGACY_V1 = "QVBSMQAAAAEAAP////8AAAAAAAAABwAAAAAAAAAAAAAAAQARMDA6MTE6MjI6MzM6NDQ6NTUAAAABABEwMDoxMToyMjozMzo0NDo1NQD/////AAAAAAEAAAABAAAAKAAAAA=="
        const val LEGACY_V2 = "QVBSMQAAAAIAAP////8AAAAAAAAABwAAAAAAAAAAAAAAAQARMDA6MTE6MjI6MzM6NDQ6NTUAAAABABEwMDoxMToyMjozMzo0NDo1NQD/////AAAAAAEAAAABAAAAKAAAAAAAAQAAAAAAAAACAAAAAwA="
    }
    @Test fun v2RoundTripKeepsLeftRightAndHeadActionsDistinct() {
        val a = PairAddress("00:11:22:33:44:55")
        val d = AirPodsRuntimeData(selectedPair = a,pairs = mapOf(a to PairSettings(leftLongPress = LongPressAction.DIGITAL_ASSISTANT,nodAction = HeadAction.NEXT,shakeAction = HeadAction.PREVIOUS)))
        assertEquals(d,AirPodsRuntimeCodec.decode(AirPodsRuntimeCodec.encode(d)))
    }
    @Test fun calibrationOrStationarySamplesNeverCreateGesture() {
        val detector = PinnedHeadGestureDetector { 100 }
        repeat(120) { detector.processHeadOrientation(7000, -7000); assertNull(detector.detect()) }
        repeat(120) { detector.processHeadOrientation(0,0); assertNull(detector.detect()) }
    }
    @Test fun closeInterruptsTransportAndRejectsQueuedWrites() {
        var closes = 0; var writes = 0
        val transport = GuardedTransport(object : AacpTransport {
            override fun connect() {}
            override fun readSdu(): ByteArray? = null
            override fun write(packet: ByteArray) { writes++ }
            override fun close() { closes++ }
        })
        transport.connect(); assertTrue(transport.connected); transport.close(); assertFalse(transport.connected)
        assertTrue(runCatching { transport.write(byteArrayOf(1)) }.isFailure)
        assertEquals(0,writes); assertEquals(1,closes)
    }
    @Test fun sanitizedLogsAreBoundedExportableAndIndividuallyDeletable() {
        val log = AirPodsSanitizedDiagnostics { 123L }
        log.startCollection()
        repeat(130) { log.record(DiagnosticKind.STAGE,dev.lelonio.square.airpods.AacpStage.SUBSCRIBED) }
        assertEquals(100,log.records.value.size)
        val id = log.records.value.first().id
        assertEquals("$id 123 STAGE SUBSCRIBED",log.export(id))
        log.delete(id); assertEquals("",log.export(id)); assertEquals(99,log.records.value.size)
        log.clear(); assertTrue(log.records.value.isEmpty())
    }
    @Test fun failedPlatformCloseIsReported() {
        val transport = GuardedTransport(object : AacpTransport {
            override fun connect() {}
            override fun readSdu(): ByteArray? = null
            override fun write(packet: ByteArray) {}
            override fun close() { error("platform") }
        })
        assertTrue(runCatching { transport.close() }.isFailure); assertTrue(transport.closeFailed)
    }
}
