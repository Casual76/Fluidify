package dev.lelonio.square.airpods.runtime.android

import dev.lelonio.square.airpods.runtime.lifecycle.*
import dev.lelonio.square.airpods.runtime.storage.*
import org.junit.Assert.*
import org.junit.Test

/** PREPARED, NOT EXECUTED. Ordinary operations share C4 without acquiring AACP. */
class AirPodsPlatformGateTest {
    @Test fun platformAuthorizationReconcilesNewStopAndCannotClearIt() {
        var bytes: ByteArray? = null
        val store = AirPodsRuntimeStore(object : RuntimePersistence {
            override fun read() = bytes
            override fun commit(value: ByteArray): Boolean { bytes = value; return true }
        })
        var history: ExitHistory = ExitHistory.Available(emptyList())
        val gate = AirPodsStartGate(store, { history }, { 100L }, "platform", 41993, "own", 35,
            object : RuntimeOwnerFacade {
                override fun start(admission: ConnectionAdmission, generation: Long): Boolean = error("No acquisition")
                override fun stop() = error("No owner callback")
            })
        val pair = PairAddress("11:22:33:44:55:66")
        store.select(pair).getOrThrow(); gate.explicitConsent(StartIngress.EXPLICIT_ENABLE)
        val consent = store.snapshot().getOrThrow().start.consentGeneration
        assertNull(gate.platformAuthorization(pair, consent))
        assertNull(store.snapshot().getOrThrow().start.marker)
        history = ExitHistory.Available(listOf(OwnProcessExit(ExitKey(101, 41993, "own", 10), ExitCause.USER_REQUESTED)))
        assertEquals(GateReason.SUPPRESSED, gate.platformAuthorization(pair, consent))
        assertEquals(GateReason.SUPPRESSED, gate.platformAuthorization(pair, consent))
        assertTrue(store.snapshot().getOrThrow().start.suppressUntilExplicitResume)
        assertEquals(consent, store.snapshot().getOrThrow().start.consentGeneration)
    }
}
