// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.lifecycle

import dev.lelonio.square.airpods.runtime.storage.*
import org.junit.Assert.*
import org.junit.Test

/** PREPARED, NOT COMPILED OR EXECUTED. Actual gate/codec/store, synthetic owner/history/disk.
 * Tests intent, admission identity and cleanup confirmations; no radio or hardware claims. */
class AirPodsI01ControlRecoveryGateTest {
    private class Disk : RuntimePersistence {
        var bytes: ByteArray? = null
        var failCommit = false
        override fun read() = bytes?.copyOf()
        override fun commit(bytes: ByteArray): Boolean {
            if (failCommit) return false
            this.bytes = bytes.copyOf(); return true
        }
    }
    private class Fixture(val disk: Disk = Disk(), epoch: String = "i01-first") {
        val pair = PairAddress("AA:BB:CC:DD:EE:51")
        val store = AirPodsRuntimeStore(disk)
        var starts = 0
        var cleanup = OwnerCleanup.COMPLETE
        var schedule = true
        var exits: ExitHistory = ExitHistory.Available(emptyList())
        val gate = AirPodsStartGate(store, ExitHistoryProvider { exits }, RuntimeClock { 100 },
            epoch, 51, "i01.process", 35, object : RuntimeOwnerFacade {
                override fun start(admission: ConnectionAdmission, generation: Long): Boolean {
                    starts++; return schedule
                }
                override fun stop() = Unit
                override fun cleanup() = cleanup
            })
        fun state() = store.snapshot().getOrThrow().start
        fun prepare() {
            store.select(pair).getOrThrow()
            store.recordSetup(pair, PairVerification("A3063", "i01-build", 1)).getOrThrow()
            assertTrue(gate.explicitConsent(StartIngress.EXPLICIT_ENABLE) is GateResult.Resumed)
        }
        fun admission(link: Long = 1) = ConnectionAdmission(pair, "link-$link", true, true, true, true,
            "i01-build", state().consentGeneration, productConformance = true, connectionOrdinal = link)
        fun start(a: ConnectionAdmission = admission()): ConnectionAdmission {
            assertTrue(gate.attempt(StartIngress.RECEIVER, a) is GateResult.Started)
            return a
        }
        fun open(a: ConnectionAdmission = admission(), epoch: Long = 1) =
            gate.attempt(StartIngress.APP_OPEN, a, recoveryOpening = epoch)
    }

    @Test fun channelFailureRetainsConsentAndDeduplicatesCallbacksUntilARealReconnect() {
        val f = Fixture(); f.prepare(); val a = f.start(); val consent = f.state()
        assertTrue(f.gate.connectionFailed(a) is GateResult.Stopped)
        assertTrue(f.state().enabled); assertFalse(f.state().suppressUntilExplicitResume)
        assertNull(f.state().suppressReason); assertTrue(f.state().marker!!.clean)
        assertEquals(consent.consentGeneration, f.state().consentGeneration)
        assertEquals(consent.explicitlyAcknowledged, f.state().explicitlyAcknowledged)
        listOf(StartIngress.RECEIVER, StartIngress.COMPANION, StartIngress.RESTORE,
            StartIngress.APPLICATION, StartIngress.APP_OPEN, StartIngress.TILE_USER_ACTION).forEach {
            assertEquals(GateResult.Denied(GateReason.DUPLICATE_CONNECTION), f.gate.attempt(it, f.admission()))
        }
        assertEquals(1, f.starts)
        f.start(f.admission(2)); assertEquals(2, f.starts)
        assertEquals(consent.consentGeneration, f.state().consentGeneration)
    }

    @Test fun genuineOpeningRetriesTheFailedLinkOnceAndLateOldFailureCannotRetireIt() {
        val f = Fixture(); f.prepare(); val old = f.start()
        f.gate.connectionFailed(old)
        val replacement = f.admission()
        assertTrue(f.open(replacement) is GateResult.Started)
        assertEquals(GateResult.Denied(GateReason.STALE_GENERATION), f.gate.connectionFailed(old))
        assertEquals(GateResult.Denied(GateReason.ALREADY_ACTIVE), f.open())
        f.gate.connectionFailed(replacement)
        assertEquals(GateResult.Denied(GateReason.DUPLICATE_CONNECTION), f.open())
        assertEquals(2, f.starts)
        assertTrue(f.open(epoch = 2) is GateResult.Started)
        assertEquals(3, f.starts); assertFalse(f.state().suppressUntilExplicitResume)
    }

    @Test fun receiversCannotClaimAnOpeningAndOrdinaryDisconnectIsNotAChannelFailure() {
        val f = Fixture(); f.prepare(); val a = f.start()
        f.gate.connectionFailed(a)
        assertEquals(GateResult.Denied(GateReason.DUPLICATE_CONNECTION),
            f.gate.attempt(StartIngress.COMPANION, f.admission(), recoveryOpening = 99))
        val recovered = f.admission(); assertTrue(f.open(recovered) is GateResult.Started)
        f.gate.disconnected(recovered.connectionId, recovered.expectedConsentGeneration)
        assertEquals(GateResult.Denied(GateReason.DUPLICATE_CONNECTION), f.open(epoch = 2))
        assertEquals(2, f.starts)
    }

    @Test fun cleanupConfirmationMustArriveBeforeOpeningCanConsumeTheRetry() {
        val f = Fixture(); f.prepare(); val a = f.start()
        f.cleanup = OwnerCleanup.OUTSTANDING
        assertEquals(GateResult.Denied(GateReason.CLEANUP_PENDING), f.gate.connectionFailed(a))
        assertEquals(GateResult.Denied(GateReason.CLEANUP_PENDING), f.open())
        assertFalse(f.state().marker!!.clean); assertEquals(1, f.starts)
        f.cleanup = OwnerCleanup.COMPLETE
        assertTrue(f.gate.settleCleanup() is GateResult.Stopped)
        assertTrue(f.state().marker!!.clean)
        assertTrue(f.open() is GateResult.Started); assertEquals(2, f.starts)
    }

    @Test fun explicitStopDuringDeferredFailureOwnsTheDurableOutcome() {
        val f = Fixture(); f.prepare(); val a = f.start()
        f.cleanup = OwnerCleanup.OUTSTANDING; f.gate.connectionFailed(a)
        f.gate.stop(SuppressReason.DISABLED)
        f.cleanup = OwnerCleanup.COMPLETE; f.gate.settleCleanup()
        assertEquals(SuppressReason.DISABLED, f.state().suppressReason)
        assertFalse(f.state().enabled)
        assertEquals(GateResult.Denied(GateReason.SUPPRESSED), f.open(epoch = 20))
        assertEquals(GateResult.Denied(GateReason.STALE_GENERATION), f.gate.connectionFailed(a))
        assertEquals(1, f.starts)
    }

    @Test fun cleanupFailureStillSuppressesInsteadOfAutomaticallyAcquiringAgain() {
        listOf(false, true).forEach { deferred ->
            val f = Fixture(); f.prepare(); val a = f.start()
            f.cleanup = if (deferred) OwnerCleanup.OUTSTANDING else OwnerCleanup.FAILED
            f.gate.connectionFailed(a)
            if (deferred) { f.cleanup = OwnerCleanup.FAILED; f.gate.settleCleanup() }
            assertTrue(f.state().suppressUntilExplicitResume)
            assertEquals(SuppressReason.START_FAILED, f.state().suppressReason)
            assertEquals(GateResult.Denied(GateReason.SUPPRESSED), f.open(epoch = 20))
            assertEquals(1, f.starts)
        }
    }

    @Test fun schedulingFailureIsRecoverableButNotARetryLoopOnTheSameOpening() {
        val f = Fixture(); f.prepare(); f.schedule = false
        assertEquals(GateResult.Denied(GateReason.START_FAILED), f.open(epoch = 3))
        assertFalse(f.state().suppressUntilExplicitResume); assertTrue(f.state().marker!!.clean)
        assertEquals(GateResult.Denied(GateReason.DUPLICATE_CONNECTION), f.open(epoch = 3))
        f.schedule = true
        assertTrue(f.open(epoch = 4) is GateResult.Started); assertEquals(2, f.starts)
    }

    @Test fun retryStillRequiresCurrentPermissionHistoryConsentAndForegroundEligibility() {
        val f = Fixture(); f.prepare(); f.gate.connectionFailed(f.start())
        listOf(
            f.admission().copy(bluetoothPermission = false) to GateReason.PERMISSION_DENIED,
            f.admission().copy(foregroundStartAllowed = false) to GateReason.FGS_DENIED,
            f.admission().copy(expectedConsentGeneration = 0) to GateReason.STALE_GENERATION,
        ).forEach { (a, reason) -> assertEquals(GateResult.Denied(reason), f.open(a)) }
        f.exits = ExitHistory.QueryFailed
        assertEquals(GateResult.Denied(GateReason.HISTORY_FAILED), f.open())
        f.exits = ExitHistory.Available(emptyList())
        assertTrue(f.open() is GateResult.Started); assertEquals(2, f.starts)
    }

    @Test fun cleanFailureMarkerSurvivesProcessRecreationWithoutRequestingNewConsent() {
        val first = Fixture(); first.prepare(); first.gate.connectionFailed(first.start())
        val next = Fixture(first.disk, "i01-second")
        next.start(); assertEquals(1, next.starts)
        assertFalse(next.state().suppressUntilExplicitResume)
        assertEquals(first.state().consentGeneration, next.state().consentGeneration)
    }

    @Test fun failedMarkerConclusionDoesNotBecomePermissionToRecover() {
        val first = Fixture(); first.prepare(); val a = first.start(); first.disk.failCommit = true
        assertEquals(GateResult.Denied(GateReason.PERSISTENCE_FAILED), first.gate.connectionFailed(a))
        first.disk.failCommit = false
        assertEquals(GateResult.Denied(GateReason.SUPPRESSED), first.open())
        val next = Fixture(first.disk, "i01-second")
        assertEquals(GateResult.Denied(GateReason.SUPPRESSED), next.open())
        assertEquals(SuppressReason.UNCLEAN_PROCESS, next.state().suppressReason)
    }
}
