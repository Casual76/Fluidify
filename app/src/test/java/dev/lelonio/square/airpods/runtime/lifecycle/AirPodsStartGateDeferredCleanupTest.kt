// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.lifecycle

import dev.lelonio.square.airpods.runtime.storage.*
import org.junit.Assert.*
import org.junit.Test

/**
 * PREPARED, NOT EXECUTED AND NOT COMPILED. Real gate and store, plain JVM. The owner is a fake
 * that reports what an owner with a queued media teardown reports: stop() returns normally and
 * cleanup() says a confirmation is OUTSTANDING, then COMPLETE or FAILED.
 */
class AirPodsStartGateDeferredCleanupTest {
    private class Disk : RuntimePersistence {
        var bytes: ByteArray? = null
        var failCommit = false
        override fun read(): ByteArray? = bytes?.clone()
        override fun commit(bytes: ByteArray): Boolean { if (failCommit) return false; this.bytes = bytes.clone(); return true }
    }
    private class Fixture {
        val disk = Disk()
        val pair = PairAddress("AA:BB:CC:DD:EE:01")
        val store = AirPodsRuntimeStore(disk)
        var starts = 0
        var stops = 0
        var stopThrows = false
        /** What the owner answers after the synchronous part of its stop. */
        var ownerState = OwnerCleanup.COMPLETE
        /** Durable suppression as the owner saw it when its cleanup began. */
        var suppressedWhenCleanupBegan: Boolean? = null
        val gate = AirPodsStartGate(store, ExitHistoryProvider { ExitHistory.Available(emptyList()) }, RuntimeClock { 100L },
            "epoch-A", 7, "own.process", 35, object : RuntimeOwnerFacade {
                override fun start(admission: ConnectionAdmission, generation: Long): Boolean { starts++; return true }
                override fun stop() {
                    stops++
                    suppressedWhenCleanupBegan = store.snapshot().getOrThrow().start.suppressUntilExplicitResume
                    check(!stopThrows)
                }
                override fun cleanup(): OwnerCleanup = ownerState
            })
        fun state() = store.snapshot().getOrThrow().start
        fun resume() = gate.explicitConsent(StartIngress.EXPLICIT_RESUME)
        fun admission(id: String) = ConnectionAdmission(pair, id, true, true, true, true, "test-build",
            state().consentGeneration, productConformance = true)
        fun attempt(id: String = "real-connection-1") = gate.attempt(StartIngress.COMPANION, admission(id))
        fun disconnect(id: String = "real-connection-1") = gate.disconnected(id, state().consentGeneration)
        /** A live owner admitted through the real gate. */
        fun started(): Fixture {
            store.select(pair).getOrThrow()
            store.recordSetup(pair, PairVerification("A3063", "test-build", 1)).getOrThrow()
            assertTrue(resume() is GateResult.Resumed)
            assertTrue(attempt() is GateResult.Started)
            assertFalse(state().marker!!.clean)
            return this
        }
    }
    private fun denied(reason: GateReason, result: GateResult) { assertEquals(GateResult.Denied(reason), result) }

    @Test fun control_synchronousOwnerKeepsItsAnswers() {
        val f = Fixture().started()
        val stopped = f.gate.stop()
        assertTrue(stopped is GateResult.Stopped)
        assertTrue(f.state().marker!!.clean)
        assertEquals(stopped, f.gate.settleCleanup()) // nothing outstanding: no owner call, same answer
        assertEquals(1, f.stops)
        f.resume(); f.attempt("connection-2"); f.stopThrows = true
        denied(GateReason.CLEANUP_FAILED, f.gate.stop())
        val stops = f.stops
        denied(GateReason.CLEANUP_FAILED, f.gate.settleCleanup()) // a synchronous failure is not settled by asking again
        assertEquals(stops, f.stops)
        f.stopThrows = false
        assertTrue(f.gate.stop() is GateResult.Stopped)
    }

    @Test fun outstandingStopHasDurableSuppressionFirstAndIsNeitherAStartNorAConsent() {
        val f = Fixture().started()
        f.ownerState = OwnerCleanup.OUTSTANDING
        denied(GateReason.CLEANUP_PENDING, f.gate.stop())
        assertEquals("suppression must be durable BEFORE the owner cleanup begins", true, f.suppressedWhenCleanupBegan)
        val stopped = f.state()
        assertTrue(stopped.suppressUntilExplicitResume)
        assertEquals(SuppressReason.USER_STOP, stopped.suppressReason)
        assertFalse("marker concluded before the cleanup was confirmed", stopped.marker!!.clean)
        assertTrue(f.gate.cleanupUnconfirmed)
        assertEquals(GateReason.SUPPRESSED, f.gate.platformAuthorization(f.pair, stopped.consentGeneration))
        // An explicit resume cannot bypass the unconfirmed cleanup; it writes no consent and no second stop.
        val stops = f.stops
        denied(GateReason.CLEANUP_PENDING, f.resume())
        assertEquals(stopped, f.state())
        assertEquals(stops, f.stops)
        denied(GateReason.CLEANUP_PENDING, f.gate.settleCleanup())
        assertEquals(1, f.starts)
    }

    @Test fun confirmationConcludesTheMarkerAndOnlyAnExplicitResumeStartsAgain() {
        val f = Fixture().started()
        f.ownerState = OwnerCleanup.OUTSTANDING
        denied(GateReason.CLEANUP_PENDING, f.gate.stop())
        f.ownerState = OwnerCleanup.COMPLETE
        val settled = f.gate.settleCleanup()
        assertEquals(GateResult.Stopped(f.state().consentGeneration), settled)
        assertTrue(f.state().marker!!.clean)
        assertFalse(f.gate.cleanupUnconfirmed)
        assertEquals(settled, f.gate.settleCleanup()) // idempotent, same answer for a waiter that arrives late
        assertEquals(1, f.stops)
        assertTrue("settling is not a resume", f.state().suppressUntilExplicitResume)
        denied(GateReason.SUPPRESSED, f.attempt("connection-2"))
        assertTrue(f.resume() is GateResult.Resumed)
        assertTrue(f.attempt("connection-2") is GateResult.Started)
        assertEquals(2, f.starts)
    }

    @Test fun ordinaryDisconnectKeepsTheOptInButAdmitsNoSecondOwnerUntilConfirmed() {
        val f = Fixture().started()
        f.ownerState = OwnerCleanup.OUTSTANDING
        denied(GateReason.CLEANUP_PENDING, f.disconnect())
        val pending = f.state()
        assertTrue(pending.enabled)
        assertFalse(pending.suppressUntilExplicitResume)
        assertFalse(pending.marker!!.clean)
        // The device reconnects while the previous owner is revoked but unconfirmed.
        denied(GateReason.CLEANUP_PENDING, f.attempt("connection-2"))
        denied(GateReason.CLEANUP_PENDING, f.attempt("connection-2"))
        assertEquals(1, f.starts)
        assertEquals(pending, f.state())
        f.ownerState = OwnerCleanup.COMPLETE
        assertTrue(f.gate.settleCleanup() is GateResult.Stopped)
        assertFalse(f.state().suppressUntilExplicitResume)
        assertTrue(f.state().marker!!.clean)
        // The refused connection was not consumed: the next ingress may start it, without any resume.
        assertTrue(f.attempt("connection-2") is GateResult.Started)
        assertEquals(2, f.starts)
        denied(GateReason.STALE_GENERATION, f.gate.disconnected("real-connection-1", f.state().consentGeneration))
    }

    @Test fun lateFailureAfterDisconnectEscalatesOnceLikeASynchronousFailure() {
        val f = Fixture().started()
        f.ownerState = OwnerCleanup.OUTSTANDING
        denied(GateReason.CLEANUP_PENDING, f.disconnect())
        f.ownerState = OwnerCleanup.FAILED
        val stops = f.stops
        denied(GateReason.CLEANUP_FAILED, f.gate.settleCleanup())
        assertEquals("the START_FAILED stop retries the owner exactly once", stops + 1, f.stops)
        assertTrue(f.state().suppressUntilExplicitResume)
        assertEquals(SuppressReason.START_FAILED, f.state().suppressReason)
        denied(GateReason.CLEANUP_FAILED, f.gate.settleCleanup())
        denied(GateReason.CLEANUP_FAILED, f.gate.settleCleanup())
        assertEquals("no automatic retry loop", stops + 1, f.stops)
        denied(GateReason.CLEANUP_FAILED, f.resume()) // an explicit resume retries, and is refused while it still fails
        assertEquals(stops + 2, f.stops)
        assertTrue(f.state().suppressUntilExplicitResume)
        f.ownerState = OwnerCleanup.COMPLETE
        assertTrue(f.resume() is GateResult.Resumed)
        assertTrue(f.attempt("connection-2") is GateResult.Started)
    }

    @Test fun lateFailureAfterExplicitStopStaysLatchedUntilAnExplicitRetry() {
        val f = Fixture().started()
        f.ownerState = OwnerCleanup.OUTSTANDING
        denied(GateReason.CLEANUP_PENDING, f.gate.stop())
        f.ownerState = OwnerCleanup.FAILED
        val stops = f.stops
        denied(GateReason.CLEANUP_FAILED, f.gate.settleCleanup())
        assertEquals(stops, f.stops)
        assertEquals(SuppressReason.USER_STOP, f.state().suppressReason)
        f.ownerState = OwnerCleanup.COMPLETE
        denied(GateReason.CLEANUP_FAILED, f.gate.settleCleanup()) // a failed confirmation is not repaired by polling
        assertTrue(f.gate.cleanupUnconfirmed)
        assertFalse(f.state().marker!!.clean)
        assertTrue(f.gate.stop() is GateResult.Stopped)
        assertTrue(f.state().marker!!.clean)
        assertFalse(f.gate.cleanupUnconfirmed)
    }

    @Test fun suppressionThatCouldNotBeWrittenIsNotRepairedByTheConfirmation() {
        val f = Fixture().started()
        f.ownerState = OwnerCleanup.OUTSTANDING
        f.disk.failCommit = true
        denied(GateReason.PERSISTENCE_FAILED, f.gate.stop())
        f.disk.failCommit = false
        f.ownerState = OwnerCleanup.COMPLETE
        denied(GateReason.PERSISTENCE_FAILED, f.gate.settleCleanup())
        assertFalse("the unconcluded marker must survive for the next process", f.state().marker!!.clean)
        assertFalse(f.state().suppressUntilExplicitResume)
        denied(GateReason.SUPPRESSED, f.attempt("connection-2")) // latched in this process
        assertEquals(1, f.starts)
    }

    @Test fun explicitStopDuringAnOutstandingDisconnectOwnsTheOutcome() {
        val f = Fixture().started()
        f.ownerState = OwnerCleanup.OUTSTANDING
        denied(GateReason.CLEANUP_PENDING, f.disconnect())
        denied(GateReason.CLEANUP_PENDING, f.gate.stop(SuppressReason.DISABLED))
        assertEquals(SuppressReason.DISABLED, f.state().suppressReason)
        assertFalse(f.state().enabled)
        f.ownerState = OwnerCleanup.FAILED
        val stops = f.stops
        denied(GateReason.CLEANUP_FAILED, f.gate.settleCleanup())
        assertEquals("the disconnect escalation no longer applies", stops, f.stops)
        assertEquals(SuppressReason.DISABLED, f.state().suppressReason)
    }
}
