// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.lifecycle

import dev.lelonio.square.airpods.runtime.storage.*
import org.junit.Assert.*
import org.junit.Test

/** PREPARED ONLY. Synthetic policy inputs; no hardware or platform capability evidence. */
class AirPodsQualificationGateTest {
    private class Bytes : RuntimePersistence {
        var bytes: ByteArray? = null
        override fun read() = bytes?.clone()
        override fun commit(bytes: ByteArray): Boolean { this.bytes = bytes.clone(); return true }
    }
    private class Owner : RuntimeOwnerFacade {
        var starts = 0; var stops = 0
        override fun start(admission: ConnectionAdmission, generation: Long): Boolean { starts++; return true }
        override fun stop() { stops++ }
    }
    private val pair = PairAddress("12:34:56:78:9A:BC")
    private fun admission(consent: Long) = ConnectionAdmission(pair, "real-connection-1", true, true, true,
        true, "build", consent, ConnectionPurpose.QUALIFICATION_TEST)
    private fun store() = AirPodsRuntimeStore(Bytes()).also { it.select(pair).getOrThrow() }
    private fun gate(store: AirPodsRuntimeStore, owner: Owner, epoch: String = "epoch-1",
        history: ExitHistory = ExitHistory.Available(emptyList()), entitled: Boolean = true) = AirPodsStartGate(
        store, ExitHistoryProvider { history }, RuntimeClock { 100 }, epoch, 123, "app", 35, owner,
        qualificationEntitled = { entitled })
    @Test fun qualificationIsSeparateFromConformanceAndVerification() {
        val store = store(); val owner = Owner(); val gate = gate(store, owner)
        val consent = (gate.explicitConsent(StartIngress.EXPLICIT_ENABLE) as GateResult.Resumed).generation
        assertTrue(gate.attempt(StartIngress.EXPLICIT_ENABLE, admission(consent)) is GateResult.Started)
        assertNull(store.snapshot().getOrThrow().pairs.getValue(pair).verification)
        assertEquals(1, owner.starts)
    }
    @Test fun defaultGateAndExpiredAuthorityNeverAcquire() {
        val store = store(); val owner = Owner(); val gate = gate(store, owner, entitled = false)
        val consent = (gate.explicitConsent(StartIngress.EXPLICIT_ENABLE) as GateResult.Resumed).generation
        assertEquals(GateResult.Denied(GateReason.SETUP_REQUIRED), gate.attempt(StartIngress.RECEIVER, admission(consent)))
        assertEquals(0, owner.starts)
        val productionGate = AirPodsStartGate(store, ExitHistoryProvider { ExitHistory.Available(emptyList()) },
            RuntimeClock { 100 }, "release", 123, "app", 35, owner)
        assertEquals(GateResult.Denied(GateReason.SETUP_REQUIRED), productionGate.attempt(StartIngress.COMPANION, admission(consent)))
    }
    @Test fun installationRestoreBindingAndListeningCannotAcquire() {
        val store = store(); val owner = Owner(); val gate = gate(store, owner)
        val consent = (gate.explicitConsent(StartIngress.EXPLICIT_ENABLE) as GateResult.Resumed).generation
        for (ingress in listOf(StartIngress.APPLICATION, StartIngress.APP_OPEN, StartIngress.RESTORE,
            StartIngress.TILE_LISTENING, StartIngress.NLS_CALLBACK, StartIngress.TILE_USER_ACTION)) {
            assertEquals(GateResult.Denied(GateReason.SETUP_REQUIRED), gate.attempt(ingress, admission(consent)))
        }
        assertEquals(0, owner.starts)
    }
    @Test fun reliableKillMayUseNewAuthorityButUserRequestedAndUnknownMarkerCannot() {
        for (cause in ExitCause.entries) {
            val store = store(); val owner = Owner(); val initial = gate(store, owner)
            val consent = (initial.explicitConsent(StartIngress.EXPLICIT_ENABLE) as GateResult.Resumed).generation
            assertTrue(initial.attempt(StartIngress.EXPLICIT_ENABLE, admission(consent)) is GateResult.Started)
            val history = ExitHistory.Available(listOf(OwnProcessExit(ExitKey(200, 123, "app", 10), cause)))
            val replacement = Owner(); val recreated = gate(store, replacement, "epoch-2", history)
            val result = recreated.attempt(StartIngress.COMPANION, admission(consent).copy(connectionId = "connection-2"))
            assertEquals(cause == ExitCause.RELIABLE_OTHER, result is GateResult.Started)
            assertEquals(if(cause == ExitCause.RELIABLE_OTHER) 1 else 0, replacement.starts)
            if (cause != ExitCause.RELIABLE_OTHER) {
                repeat(3) { recreated.attempt(StartIngress.RECEIVER, admission(consent)) }
                assertTrue(store.snapshot().getOrThrow().start.suppressUntilExplicitResume)
                assertEquals(0, replacement.starts)
            }
        }
    }
    @Test fun stopDominatesOldConsentEvenWhenEntitlementStillSaysTrue() {
        val store = store(); val owner = Owner(); val gate = gate(store, owner)
        val consent = (gate.explicitConsent(StartIngress.EXPLICIT_ENABLE) as GateResult.Resumed).generation
        gate.stop(SuppressReason.USER_STOP)
        assertEquals(GateResult.Denied(GateReason.SUPPRESSED), gate.attempt(StartIngress.COMPANION, admission(consent)))
        assertEquals(0, owner.starts)
    }
}
