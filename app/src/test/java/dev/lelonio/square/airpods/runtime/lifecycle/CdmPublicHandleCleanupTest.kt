// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.lifecycle

import dev.lelonio.square.airpods.runtime.storage.*
import org.junit.Assert.*
import org.junit.Test

/** Prepared, NOT EXECUTED. Exercises actual policy + durable store, no radio. */
class CdmPublicHandleCleanupTest {
    private val pair = PairAddress("AA:BB:CC:DD:EE:01")
    private val other = PairAddress("AA:BB:CC:DD:EE:02")
    private inner class Registry(legacy: Boolean = false) : CompanionRegistryFacade {
        var now = listOf(OwnedAssociation(if (legacy) null else 4, pair, "own", "a".repeat(64)))
        var afterStop: (() -> Unit)? = null
        var startResult = true
        var stopResult = true
        var queriesFail = false
        var starts = 0; var stops = 0; var removals = 0
        override fun hasFeature() = true
        override fun hasObservePermission() = true
        override fun isBonded(address: PairAddress) = true
        override fun ownAssociations(): List<OwnedAssociation> { check(!queriesFail); return now }
        override fun start(registration: ObservationRegistration): Boolean {
            starts++; now = now.map { it.copy(identity = "b".repeat(64)) }; return startResult
        }
        override fun stop(registration: ObservationRegistration): Boolean {
            stops++; now = now.map { it.copy(identity = "c".repeat(64)) }
            afterStop?.invoke(); return stopResult
        }
        override fun disassociate(association: OwnedAssociation): Boolean {
            check(now.any { it.sameEndpoint(association) })
            removals++; now = now.filterNot { it.sameEndpoint(association) }; return true
        }
    }
    private class Disk : RuntimePersistence {
        var bytes: ByteArray? = null
        override fun read() = bytes
        override fun commit(bytes: ByteArray): Boolean { this.bytes = bytes; return true }
    }
    @Test fun metadataChangingRegistrationIsIdempotentAndStopUsesOriginalHandle() {
        for (api in listOf(31, 33, 35, 36)) {
            val r = Registry(api < 33); val p = CompanionObservationPolicy(api, "own", r)
            val admission = CompanionAdmission(pair, 7, true)
            val registration = p.register(admission)
            assertEquals(registration, p.register(admission)); assertEquals(1, r.starts)
            assertEquals(CompanionResult.Stopped, p.stop()); assertEquals(1, r.stops)
        }
    }
    @Test fun legacyAndModernHandlesRecoverAcrossStoreAndPolicyRecreationWithoutNewAcquisition() {
        for (api in listOf(26, 31, 32, 33, 35, 36)) {
            val r = Registry(api < 33); val p = CompanionObservationPolicy(api, "own", r)
            p.register(CompanionAdmission(pair, 7, true))
            val disk = Disk(); val store = AirPodsRuntimeStore(disk)
            store.select(pair).getOrThrow()
            store.forgetWithCleanup(pair, p.captureCleanup(pair)).getOrThrow()
            p.stop()
            val recreated = AirPodsRuntimeStore(disk)
            val target = recreated.snapshot().getOrThrow().pendingCleanup.single()
            val starts = r.starts
            assertEquals(CompanionCleanupResult.Removed, CompanionObservationPolicy(api, "own", r).cleanupPending(target))
            recreated.completeCleanup(target).getOrThrow()
            assertTrue(recreated.snapshot().getOrThrow().pendingCleanup.isEmpty())
            assertEquals(starts, r.starts); assertEquals(1, r.removals)
        }
    }
    @Test fun failedStartAfterEffectRetainsActualStopHandleAndRejectsCallbacks() {
        val r = Registry(); r.startResult = false
        val p = CompanionObservationPolicy(36, "own", r)
        assertTrue(p.register(CompanionAdmission(pair, 7, true)) is CompanionResult.Denied)
        assertTrue(p.validateCallback(1, pair, 4) is CompanionResult.Denied)
        assertEquals(CompanionResult.Stopped, p.stop()); assertEquals(1, r.stops)
    }
    @Test fun finalRequeryPreservesChangedIdAddressAndForeignPackageAfterStop() {
        for (replacement in listOf(OwnedAssociation(44, pair, "own"), OwnedAssociation(4, other, "own"), OwnedAssociation(4, pair, "foreign"))) {
            val r = Registry(); val p = CompanionObservationPolicy(35, "own", r)
            val target = p.captureCleanup(pair)
            r.afterStop = { r.now = listOf(replacement) }
            assertEquals(CompanionCleanupResult.AlreadyAbsent, p.cleanupPending(target))
            assertEquals(listOf(replacement), r.now); assertEquals(0, r.removals)
        }
    }
    @Test fun rememberedHandleDoesNotFollowDifferentIdAtCapture() {
        val r = Registry(); val p = CompanionObservationPolicy(36, "own", r)
        p.register(CompanionAdmission(pair, 7, true))
        r.now = listOf(OwnedAssociation(44, pair, "own"))
        val target = p.captureCleanup(pair)
        assertEquals(4, target.associationId)
        assertEquals(CompanionCleanupResult.AlreadyAbsent, p.cleanupPending(target))
        assertEquals(0, r.removals)
    }
    @Test fun setupCancellationObservedAfterStopPreventsDeletion() {
        val disk = Disk(); val store = AirPodsRuntimeStore(disk)
        val r = Registry(); val p = CompanionObservationPolicy(36, "own", r)
        store.select(pair).getOrThrow()
        val target = p.captureCleanup(pair); store.forgetWithCleanup(pair, target).getOrThrow()
        r.afterStop = { store.select(pair).getOrThrow(); store.select(other).getOrThrow() }
        assertEquals(CompanionCleanupResult.IdentityUnresolved, p.cleanupPending(target) {
            target in store.snapshot().getOrThrow().pendingCleanup
        })
        assertEquals(0, r.removals)
        assertEquals(listOf(target), AirPodsRuntimeStore(disk).snapshot().getOrThrow().cancelledCleanup)
    }
    @Test fun queryFailureIsNeverAbsenceAndUnresolvedLegacyCaptureNeverAcquiresAuthority() {
        val r = Registry(true); val p = CompanionObservationPolicy(31, "own", r)
        r.queriesFail = true
        val target = p.captureCleanup(pair)
        assertFalse(target.handleCaptured)
        assertEquals(CompanionCleanupResult.PlatformFailed, p.cleanupPending(target))
        r.queriesFail = false
        assertEquals(CompanionCleanupResult.IdentityUnresolved, p.cleanupPending(target))
        assertEquals(0, r.removals); assertEquals(0, r.starts)
    }
}
