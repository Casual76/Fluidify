// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.android

import dev.lelonio.square.airpods.runtime.lifecycle.*
import dev.lelonio.square.airpods.runtime.storage.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** PREPARED, not executed by the implementer. No hardware claims. */
class AirPodsFunctionalAdmissionTest {
    private class Fixture(val entitled: Boolean) {
        private var bytes: ByteArray? = null
        val store = AirPodsRuntimeStore(object : RuntimePersistence {
            override fun read() = bytes
            override fun commit(bytes: ByteArray): Boolean { this@Fixture.bytes = bytes; return true }
        })
        val pair = PairAddress("11:22:33:44:55:66")
        var history: ExitHistory = ExitHistory.Available(emptyList())
        var starts = 0
        val gate = AirPodsStartGate(store, { history }, { 100L }, "functional-process", 41010, "own", 35,
            object : RuntimeOwnerFacade {
                override fun start(admission: ConnectionAdmission, generation: Long): Boolean { starts++; return true }
                override fun stop() {}
            }, { entitled })
        init { store.select(pair).getOrThrow(); gate.explicitConsent(StartIngress.EXPLICIT_ENABLE) }
        fun admission() = ConnectionAdmission(pair, "real-profile-lifetime", true, true, true, true, "build", store.snapshot().getOrThrow().start.consentGeneration,
            ConnectionPurpose.FUNCTIONAL_TEST, explicitSetupVisible = true, productConformance = false)
    }
    @Test fun defaultProductionGateNeverAdmitsTestPurpose() {
        val f = Fixture(false)
        assertEquals(GateResult.Denied(GateReason.SETUP_REQUIRED), f.gate.attempt(StartIngress.EXPLICIT_ENABLE, f.admission()))
        assertEquals(0, f.starts)
    }
    @Test fun explicitTestPermitDoesNotNeedOrCreateProductVerification() {
        val f = Fixture(true)
        assertTrue(f.gate.attempt(StartIngress.EXPLICIT_ENABLE, f.admission()) is GateResult.Started)
        assertNull(f.store.snapshot().getOrThrow().pairs.getValue(f.pair).verification)
        assertFalse(f.admission().productConformance)
        assertEquals(1, f.starts)
    }
    @Test fun testPermitCannotBeUsedByAutomaticIngress() {
        val f = Fixture(true)
        listOf(StartIngress.APPLICATION, StartIngress.RESTORE, StartIngress.COMPANION, StartIngress.RECEIVER, StartIngress.NLS_CALLBACK).forEach {
            assertEquals(GateResult.Denied(GateReason.SETUP_REQUIRED), f.gate.attempt(it, f.admission()))
        }
        assertEquals(0, f.starts)
    }
    @Test fun testStillRequiresVisibleChoicePermissionsBondAndConnection() {
        val f = Fixture(true); val a = f.admission()
        listOf(a.copy(explicitSetupVisible = false) to GateReason.SETUP_REQUIRED,
            a.copy(bluetoothPermission = false) to GateReason.PERMISSION_DENIED,
            a.copy(bonded = false) to GateReason.NOT_BONDED,
            a.copy(actuallyConnected = false) to GateReason.NOT_CONNECTED,
            a.copy(productConformance = true) to GateReason.SETUP_REQUIRED).forEach { (request, reason) ->
            assertEquals(GateResult.Denied(reason), f.gate.attempt(StartIngress.EXPLICIT_ENABLE, request))
        }
        assertEquals(0, f.starts)
    }
    @Test fun newUserRequestedExitBlocksFunctionalPermitBeforeAcquisition() {
        val f = Fixture(true)
        f.history = ExitHistory.Available(listOf(OwnProcessExit(ExitKey(101, 41010, "own", 10), ExitCause.USER_REQUESTED)))
        assertEquals(GateResult.Denied(GateReason.SUPPRESSED), f.gate.attempt(StartIngress.EXPLICIT_ENABLE, f.admission()))
        assertEquals(0, f.starts)
    }
    @Test fun concurrentTestEntrancesAcquireOnceWithC4() {
        val f = Fixture(true); val ready = CountDownLatch(3); val go = CountDownLatch(1)
        val workers = (1..3).map { thread { ready.countDown(); check(go.await(2, TimeUnit.SECONDS)); f.gate.attempt(StartIngress.EXPLICIT_ENABLE, f.admission()) } }
        assertTrue(ready.await(2, TimeUnit.SECONDS)); go.countDown(); workers.forEach { it.join(2000); assertFalse(it.isAlive) }
        assertEquals(1, f.starts)
    }
}
