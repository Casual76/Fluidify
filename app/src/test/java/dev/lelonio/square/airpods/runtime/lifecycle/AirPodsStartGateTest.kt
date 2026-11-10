// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.lifecycle

import dev.lelonio.square.airpods.runtime.storage.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AirPodsStartGateTest {
    private class Disk : RuntimePersistence {
        var bytes: ByteArray? = null
        var failRead = false
        var failCommit = false
        override fun read(): ByteArray? { check(!failRead); return bytes?.clone() }
        override fun commit(bytes: ByteArray): Boolean { if (failCommit) return false; this.bytes = bytes.clone(); return true }
    }
    private class Fixture(val disk: Disk = Disk(), val epoch: String = "epoch-A", val api: Int = 35) {
        val pair = PairAddress("AA:BB:CC:DD:EE:01")
        val store = AirPodsRuntimeStore(disk)
        var exits: ExitHistory = ExitHistory.Available(emptyList())
        var now = 100L
        var starts = 0
        var stops = 0
        var startAllowed = true
        var stopThrows = false
        val gate = AirPodsStartGate(store, ExitHistoryProvider { exits }, RuntimeClock { now }, epoch, 7, "own.process", api, object : RuntimeOwnerFacade {
            override fun start(admission: ConnectionAdmission, generation: Long): Boolean {
                val marker = store.snapshot().getOrThrow().start.marker!!
                assertFalse(marker.clean); assertEquals(epoch, marker.epoch)
                starts++; return startAllowed
            }
            override fun stop() { stops++; check(!stopThrows) }
        })
        fun prepare() {
            store.select(pair).getOrThrow()
            store.recordSetup(pair, PairVerification("A3063", "test-build", 1)).getOrThrow()
        }
        fun resume() = gate.explicitConsent(StartIngress.EXPLICIT_RESUME)
        fun admission(id: String = "real-connection-1") = ConnectionAdmission(pair, id, true, true, true, true, "test-build", store.snapshot().getOrThrow().start.consentGeneration, productConformance = true)
        fun attempt(id: String = "real-connection-1") = gate.attempt(StartIngress.COMPANION, admission(id))
        fun disconnect(id: String = "real-connection-1") = gate.disconnected(id, store.snapshot().getOrThrow().start.consentGeneration)
        fun userExit(time: Long = 200, pid: Int = 7, process: String = "own.process") = OwnProcessExit(ExitKey(time, pid, process, 10), ExitCause.USER_REQUESTED)
    }
    private fun denied(reason: GateReason, result: GateResult) { assertEquals(GateResult.Denied(reason), result) }

    @Test fun defaultsOffAndOpeningOrListeningIsNotConsent() {
        val f = Fixture(); f.prepare()
        listOf(StartIngress.APP_OPEN, StartIngress.NLS_CALLBACK, StartIngress.TILE_LISTENING, StartIngress.APPLICATION, StartIngress.RESTORE).forEach {
            denied(GateReason.DISABLED, f.gate.attempt(it, f.admission()))
        }
        assertEquals(0, f.starts)
    }
    @Test fun taskManagerExitBlocksThreeConcurrentIngressesBeforeAnyAcquire() {
        val f = Fixture(); f.prepare(); f.resume()
        f.exits = ExitHistory.Available(listOf(f.userExit()))
        concurrent(f)
        assertEquals(0, f.starts)
        val state = f.store.snapshot().getOrThrow().start
        assertTrue(state.suppressUntilExplicitResume)
        assertEquals(listOf(f.userExit().key), state.consumedExits)
    }
    @Test fun threeConcurrentIngressesAcquireExactlyOnce() {
        val f = Fixture(); f.prepare(); f.resume(); concurrent(f); assertEquals(1, f.starts)
    }
    private fun concurrent(f: Fixture) {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(3)
        try {
            val jobs = listOf(StartIngress.RECEIVER, StartIngress.COMPANION, StartIngress.RESTORE).map { ingress ->
                pool.submit<GateResult> { start.await(); f.gate.attempt(ingress, f.admission()) }
            }
            start.countDown(); jobs.forEach { it.get(3, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
    }
    @Test fun sameAcknowledgedEventDoesNotResuppressButNewEventWithReusedPidDoes() {
        val f = Fixture(); f.prepare(); f.exits = ExitHistory.Available(listOf(f.userExit()))
        f.resume(); assertTrue(f.attempt() is GateResult.Started)
        f.disconnect()
        f.exits = ExitHistory.Available(listOf(f.userExit(), f.userExit(time = 201)))
        denied(GateReason.SUPPRESSED, f.attempt("real-connection-2"))
        assertEquals(1, f.starts)
    }
    @Test fun processRestartKeepsSuppressionEvenWhenEventWasConsumed() {
        val first = Fixture(); first.prepare(); first.resume()
        first.exits = ExitHistory.Available(listOf(first.userExit())); first.attempt()
        val next = Fixture(first.disk, "epoch-B")
        next.exits = first.exits; denied(GateReason.SUPPRESSED, next.attempt())
        next.resume(); assertTrue(next.attempt() is GateResult.Started)
    }
    @Test fun emptyHistoryAndApi29UncleanMarkerRequireExplicitResume() {
        for (api in listOf(29, 35)) {
            val first = Fixture(api = api); first.prepare(); first.resume(); first.attempt()
            val next = Fixture(first.disk, "epoch-B", api)
            denied(GateReason.SUPPRESSED, next.attempt())
            assertEquals(SuppressReason.UNCLEAN_PROCESS, next.store.snapshot().getOrThrow().start.suppressReason)
            next.resume(); assertTrue(next.attempt() is GateResult.Started)
        }
    }
    @Test fun historyErrorWithOldMarkerPersistsConservativeSuppression() {
        val first = Fixture(); first.prepare(); first.resume(); first.attempt()
        val next = Fixture(first.disk, "epoch-B"); next.exits = ExitHistory.QueryFailed
        denied(GateReason.HISTORY_FAILED, next.attempt())
        assertTrue(next.store.snapshot().getOrThrow().start.suppressUntilExplicitResume)
        next.exits = ExitHistory.Available(emptyList()); denied(GateReason.SUPPRESSED, next.attempt())
    }
    @Test fun reliableOwnExitExplainsMarkerButOldPidRecordAndOtherProcessDoNot() {
        for (valid in listOf(true, false)) {
            val first = Fixture(); first.prepare(); first.resume(); first.attempt()
            val next = Fixture(first.disk, "epoch-B")
            next.exits = ExitHistory.Available(listOf(OwnProcessExit(ExitKey(if (valid) 101 else 99, 7, "own.process", 4), ExitCause.RELIABLE_OTHER)))
            if (valid) assertTrue(next.attempt() is GateResult.Started) else denied(GateReason.SUPPRESSED, next.attempt())
        }
        val f = Fixture(); f.prepare(); f.resume(); f.exits = ExitHistory.Available(listOf(f.userExit(process = "foreign")))
        assertTrue(f.attempt() is GateResult.Started)
    }
    @Test fun explicitStopAndStaleCallbacksCannotRestartUntilNewConsent() {
        val f = Fixture(); f.prepare(); f.resume(); val old = f.admission(); f.attempt()
        f.gate.stop(); val generation = f.store.snapshot().getOrThrow().start.consentGeneration
        f.gate.stop(); assertEquals(generation, f.store.snapshot().getOrThrow().start.consentGeneration)
        denied(GateReason.SUPPRESSED, f.gate.attempt(StartIngress.NLS_CALLBACK, old))
        f.resume(); denied(GateReason.STALE_GENERATION, f.gate.attempt(StartIngress.COMPANION, old))
        assertTrue(f.attempt("real-connection-2") is GateResult.Started)
    }
    @Test fun commitFailureBeforeMarkerNeverAcquiresAndStopStillCleansUp() {
        val f = Fixture(); f.prepare(); f.resume(); f.disk.failCommit = true
        denied(GateReason.PERSISTENCE_FAILED, f.attempt()); assertEquals(0, f.starts)
        denied(GateReason.PERSISTENCE_FAILED, f.gate.stop()); assertEquals(1, f.stops)
        f.disk.failCommit = false; denied(GateReason.SUPPRESSED, f.attempt())
    }
    @Test fun failedResumeDoesNotClearSuppression() {
        val f = Fixture(); f.prepare(); f.resume(); f.gate.stop(); f.disk.failCommit = true
        denied(GateReason.PERSISTENCE_FAILED, f.resume())
        f.disk.failCommit = false; denied(GateReason.SUPPRESSED, f.attempt())
    }
    @Test fun queryAndReadFailureFailClosed() {
        val f = Fixture(); f.prepare(); f.resume(); f.exits = ExitHistory.QueryFailed
        denied(GateReason.HISTORY_FAILED, f.attempt()); denied(GateReason.HISTORY_FAILED, f.resume())
        f.exits = ExitHistory.Available(emptyList()); f.disk.failRead = true
        denied(GateReason.PERSISTENCE_FAILED, f.gate.attempt(StartIngress.RESTORE, ConnectionAdmission(f.pair, "c", true, true, true, true, "test-build", 1)))
        assertEquals(0, f.starts)
    }
    @Test fun disconnectDeduplicatesSameConnectionAndStartFailureDoesNotAutoRetry() {
        val f = Fixture(); f.prepare(); f.resume(); f.attempt(); f.disconnect()
        denied(GateReason.DUPLICATE_CONNECTION, f.attempt()); assertTrue(f.attempt("connection-2") is GateResult.Started)
        f.disconnect("connection-2"); f.startAllowed = false
        denied(GateReason.START_FAILED, f.attempt("connection-3")); denied(GateReason.SUPPRESSED, f.attempt("connection-4"))
        assertEquals(3, f.starts)
    }
    @Test fun revokeAndUnpairPersistBeforeCallbackAdmission() {
        val f = Fixture(); f.prepare(); f.resume(); f.attempt(); f.gate.stop(SuppressReason.REVOKED)
        denied(GateReason.SUPPRESSED, f.attempt("c2")); f.gate.stop(SuppressReason.UNPAIRED)
        assertNull(f.store.snapshot().getOrThrow().selectedPair)
        assertTrue(f.store.snapshot().getOrThrow().pairs.isEmpty())
        assertFalse(f.store.snapshot().getOrThrow().start.enabled)
    }
    @Test fun ledgerIsBoundedAndSameTimestampDifferentIdentityIsNewStop() {
        val f = Fixture(); f.prepare()
        f.exits = ExitHistory.Available((1..100).map { f.userExit(it.toLong()) }); f.resume()
        assertEquals(64, f.store.snapshot().getOrThrow().start.consumedExits.size)
        assertTrue(f.store.snapshot().getOrThrow().start.ledgerTruncated)
        // Drop old history, then add a different PID at the SAME timestamp.
        f.exits = ExitHistory.Available(listOf(f.userExit(100), f.userExit(100, pid = 9)))
        denied(GateReason.SUPPRESSED, f.attempt())
    }
    @Test fun admissionRequiresSetupBondPermissionRealConnectionAndFgsEligibility() {
        val f = Fixture(); f.prepare(); f.resume(); val a = f.admission()
        listOf(
            a.copy(bluetoothPermission = false) to GateReason.PERMISSION_DENIED,
            a.copy(bonded = false) to GateReason.NOT_BONDED,
            a.copy(actuallyConnected = false) to GateReason.NOT_CONNECTED,
            a.copy(foregroundStartAllowed = false) to GateReason.FGS_DENIED,
            a.copy(conformanceBuild = "different-build") to GateReason.SETUP_REQUIRED,
        ).forEach { (admission, reason) -> denied(reason, f.gate.attempt(StartIngress.COMPANION, admission)) }
        assertEquals(0, f.starts)
    }
    @Test fun staleDisconnectCannotStopNewConnectionAndResumeAllowsManualRetry() {
        val f = Fixture(); f.prepare(); f.resume(); f.attempt(); val oldGeneration = f.admission().expectedConsentGeneration
        f.disconnect(); f.attempt("connection-2")
        denied(GateReason.STALE_GENERATION, f.gate.disconnected("real-connection-1", oldGeneration))
        assertEquals(1, f.stops)
        f.gate.stop(); f.resume(); assertTrue(f.attempt("connection-2") is GateResult.Started)
    }
    @Test fun cleanupFailureCannotBeBypassedByExplicitResume() {
        val f = Fixture(); f.prepare(); f.resume(); f.attempt(); f.stopThrows = true
        denied(GateReason.CLEANUP_FAILED, f.gate.stop())
        denied(GateReason.CLEANUP_FAILED, f.resume())
        assertTrue(f.store.snapshot().getOrThrow().start.suppressUntilExplicitResume)
        f.stopThrows = false; f.resume(); assertTrue(f.attempt() is GateResult.Started)
    }
}
