// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.lifecycle

import dev.lelonio.square.airpods.runtime.storage.PairAddress
import org.junit.Assert.*
import org.junit.Test

class CompanionObservationPolicyTest {
    private val pair = PairAddress("AA:BB:CC:DD:EE:01")
    private val other = PairAddress("AA:BB:CC:DD:EE:02")
    private inner class Registry : CompanionRegistryFacade {
        var feature = true; var permission = true; var bonded = true
        var allowStart = true; var allowStop = true
        var associations = listOf(OwnedAssociation(42, pair, "own"))
        val starts = mutableListOf<ObservationRegistration>()
        val stops = mutableListOf<ObservationRegistration>()
        val removed = mutableListOf<OwnedAssociation>()
        override fun hasFeature() = feature
        override fun hasObservePermission() = permission
        override fun isBonded(address: PairAddress) = bonded && address == pair
        override fun ownAssociations() = associations
        override fun start(registration: ObservationRegistration): Boolean { starts += registration; return allowStart }
        override fun stop(registration: ObservationRegistration): Boolean { stops += registration; return allowStop }
        override fun disassociate(association: OwnedAssociation): Boolean { removed += association; associations = associations - association; return true }
    }
    @Test fun versionBranchesEmitOnlySupportedObservationAndConfirmAfterFacade() {
        for (api in listOf(26, 30, 31, 32, 33, 35, 36, 37)) {
            val registry = Registry(); val policy = CompanionObservationPolicy(api, "own", registry)
            val result = policy.register(CompanionAdmission(pair, 1, true))
            if (api < 31) { assertEquals(CompanionResult.LegacyManual, result); assertTrue(registry.starts.isEmpty()) }
            else {
                val expected = when { api >= 36 -> ObservationKind.REQUEST_ID_36; api >= 33 -> ObservationKind.ASSOCIATION_ADDRESS_33; else -> ObservationKind.ADDRESS_31 }
                assertEquals(expected, (result as CompanionResult.Registered).registration.kind)
                assertEquals(listOf(result.registration), registry.starts)
                assertEquals(result, policy.register(CompanionAdmission(pair, 1, true)))
                assertEquals(1, registry.starts.size)
            }
            policy.stop(); assertEquals(if (api < 31) 0 else 1, registry.stops.size)
        }
    }
    @Test fun denyRevokeWrongAssociationAndMissingFeatureNeverRegister() {
        for (scenario in 0..4) {
            val r = Registry()
            when (scenario) { 0 -> r.feature = false; 1 -> r.permission = false; 2 -> r.bonded = false; 3 -> r.associations = listOf(OwnedAssociation(42, other, "own")); 4 -> r.associations = listOf(OwnedAssociation(42, pair, "foreign")) }
            val p = CompanionObservationPolicy(36, "own", r)
            assertTrue(p.register(CompanionAdmission(pair, 1, true)) is CompanionResult.Denied)
            assertTrue(r.starts.isEmpty())
        }
        val r = Registry(); val p = CompanionObservationPolicy(35, "own", r)
        assertEquals(CompanionResult.Denied(CompanionFailure.NO_CONSENT), p.register(CompanionAdmission(pair, 1, false)))
        r.allowStart = false
        assertEquals(CompanionResult.Denied(CompanionFailure.REGISTRATION_DENIED), p.register(CompanionAdmission(pair, 1, true)))
        assertEquals(CompanionResult.Denied(CompanionFailure.STALE_CALLBACK), p.appeared(1, pair, 42, true))
    }
    @Test fun callbacksNeedRightDeviceAssociationAndActualConnection() {
        val r = Registry(); val p = CompanionObservationPolicy(36, "own", r)
        val registration = (p.register(CompanionAdmission(pair, 9, true)) as CompanionResult.Registered).registration
        val g = registration.callbackGeneration
        assertEquals(CompanionResult.Denied(CompanionFailure.WRONG_DEVICE), p.appeared(g, other, 42, true))
        assertEquals(CompanionResult.Denied(CompanionFailure.WRONG_DEVICE), p.appeared(g, pair, 43, true))
        assertEquals(CompanionResult.Denied(CompanionFailure.NOT_CONNECTED), p.appeared(g, pair, 42, false))
        assertEquals(CompanionResult.Trigger(pair, 9), p.appeared(g, pair, 42, true))
        r.permission = false
        assertEquals(CompanionResult.Denied(CompanionFailure.PERMISSION_DENIED), p.appeared(g, pair, 42, true))
        p.stop()
        assertEquals(CompanionResult.Denied(CompanionFailure.STALE_CALLBACK), p.appeared(g, pair, 42, true))
    }
    @Test fun unpairRemovesOnlyExactOwnAssociationAndIsIdempotent() {
        val r = Registry(); val foreign = OwnedAssociation(99, pair, "foreign")
        r.associations = r.associations + foreign
        val p = CompanionObservationPolicy(36, "own", r); p.register(CompanionAdmission(pair, 1, true))
        p.unpair(pair); p.unpair(pair)
        assertEquals(listOf(OwnedAssociation(42, pair, "own")), r.removed)
        assertEquals(listOf(foreign), r.associations); assertEquals(1, r.stops.size)
    }
    @Test fun failedStopInvalidatesCallbackAndRequiresExplicitCleanup() {
        val r = Registry(); val p = CompanionObservationPolicy(31, "own", r)
        val g = (p.register(CompanionAdmission(pair, 1, true)) as CompanionResult.Registered).registration.callbackGeneration
        r.allowStop = false
        assertEquals(CompanionResult.Denied(CompanionFailure.STOP_FAILED), p.stop())
        assertEquals(CompanionResult.Denied(CompanionFailure.STALE_CALLBACK), p.appeared(g, pair, null, true))
        r.allowStop = true; assertEquals(CompanionResult.Stopped, p.stop())
    }
    @Test fun refusedConsentInvalidatesPreviouslyRegisteredCallbacks() {
        val r = Registry(); val p = CompanionObservationPolicy(36, "own", r)
        val g = (p.register(CompanionAdmission(pair, 1, true)) as CompanionResult.Registered).registration.callbackGeneration
        p.register(CompanionAdmission(pair, 1, false))
        assertEquals(CompanionResult.Denied(CompanionFailure.STALE_CALLBACK), p.appeared(g, pair, 42, true))
        assertEquals(1, r.stops.size)
    }
    @Test fun unpairBeforeObservationStillRemovesOnlyOwnSelectedPair() {
        val r = Registry(); val p = CompanionObservationPolicy(36, "own", r)
        p.unpair(pair)
        assertEquals(listOf(OwnedAssociation(42, pair, "own")), r.removed)
        assertTrue(r.starts.isEmpty())
    }
}
