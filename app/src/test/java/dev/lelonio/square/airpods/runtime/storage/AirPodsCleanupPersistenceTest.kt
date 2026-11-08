// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.storage

import org.junit.Assert.*
import org.junit.Test

/** Corrective I2 regressions PREPARED, NOT EXECUTED. */
class AirPodsCleanupPersistenceTest {
    private class Disk : RuntimePersistence {
        var bytes: ByteArray? = null
        var fail = false
        override fun read() = bytes
        override fun commit(bytes: ByteArray): Boolean { if (fail) return false; this.bytes = bytes; return true }
    }
    private val pair = PairAddress("AA:BB:CC:DD:EE:01")
    private fun target() = PendingCompanionCleanup("origin", pair, 4, "own", "a".repeat(64))
    @Test fun forgetAndCleanupAuthoritySurviveNewStoreWithoutImportingConsentOrSetup() {
        val disk = Disk(); val store = AirPodsRuntimeStore(disk)
        store.select(pair).getOrThrow(); store.recordSetup(pair, PairVerification("model", "build", 1)).getOrThrow()
        store.forgetWithCleanup(pair, target()).getOrThrow()
        val recreated = AirPodsRuntimeStore(disk); val data = recreated.snapshot().getOrThrow()
        assertNull(data.selectedPair); assertTrue(data.pairs.isEmpty()); assertFalse(data.start.enabled)
        assertTrue(data.start.suppressUntilExplicitResume); assertEquals(listOf(target()), data.pendingCleanup)
        assertFalse(data.toString().contains(pair.value))
        recreated.completeCleanup(target()).getOrThrow()
        assertTrue(AirPodsRuntimeStore(disk).snapshot().getOrThrow().pendingCleanup.isEmpty())
    }
    @Test fun failedPrepareCommitNeverDropsOriginalDestination() {
        val disk = Disk(); val store = AirPodsRuntimeStore(disk); store.select(pair).getOrThrow()
        val before = disk.bytes!!.clone(); disk.fail = true
        assertTrue(store.forgetWithCleanup(pair, target()).isFailure)
        assertArrayEquals(before, disk.bytes); assertEquals(pair, store.snapshot().getOrThrow().selectedPair)
    }
    @Test fun legacyVersionsMigrateWithoutInventingCleanupIdentityOrConsent() {
        // Empty v4 cleanup and cancellation trailers total eight bytes; preceding bytes retain the v1/v2 format.
        for (version in listOf(1, 2)) {
            val encoded = AirPodsRuntimeCodec.encode(AirPodsRuntimeData())
            val old = encoded.copyOf(encoded.size - 8).also { it[7] = version.toByte() }
            val data = AirPodsRuntimeCodec.decode(old)
            assertEquals(4, data.schemaVersion); assertTrue(data.pendingCleanup.isEmpty())
            assertFalse(data.start.enabled); assertNull(data.selectedPair); assertTrue(data.pairs.isEmpty())
            assertEquals(data, AirPodsRuntimeCodec.decode(AirPodsRuntimeCodec.encode(data)))
        }
    }
    @Test fun unresolvedAndKnownAbsentMarkersRemainDistinctAcrossCodec() {
        val unresolved = target().copy(associationId = null, identity = null, handleCaptured = false)
        for (marker in listOf(unresolved, unresolved.copy(knownAbsent = true))) {
            val data = AirPodsRuntimeData(pendingCleanup = listOf(marker))
            assertEquals(data, AirPodsRuntimeCodec.decode(AirPodsRuntimeCodec.encode(data)))
        }
    }
    @Test fun completingOriginalMarkerPreservesNewPairAndOtherCleanup() {
        val disk = Disk(); val store = AirPodsRuntimeStore(disk); store.select(pair).getOrThrow()
        store.forgetWithCleanup(pair, target()).getOrThrow()
        store.select(pair).getOrThrow(); store.recordSetup(pair, PairVerification("new", "new-build", 2)).getOrThrow()
        val before = store.snapshot().getOrThrow()
        store.completeCleanup(target()).getOrThrow()
        assertEquals(before.copy(pendingCleanup = emptyList()), store.snapshot().getOrThrow())
    }
    @Test fun newSetupCancelsOlderRemovalDurablyEvenAfterSelectingAnotherPair() {
        val disk = Disk(); val store = AirPodsRuntimeStore(disk)
        store.select(pair).getOrThrow(); store.forgetWithCleanup(pair, target()).getOrThrow()
        store.select(pair).getOrThrow()
        store.select(PairAddress("AA:BB:CC:DD:EE:02")).getOrThrow()
        val restored = AirPodsRuntimeStore(disk).snapshot().getOrThrow()
        assertTrue(restored.pendingCleanup.isEmpty())
        assertEquals(listOf(target()), restored.cancelledCleanup)
    }
    @Test fun failedNewSetupCommitRetainsRemovalAndDoesNotRecordCancellation() {
        val disk = Disk(); val store = AirPodsRuntimeStore(disk)
        store.select(pair).getOrThrow(); store.forgetWithCleanup(pair, target()).getOrThrow()
        disk.fail = true; assertTrue(store.select(pair).isFailure)
        val restored = AirPodsRuntimeStore(disk).snapshot().getOrThrow()
        assertNull(restored.selectedPair); assertEquals(listOf(target()), restored.pendingCleanup)
        assertTrue(restored.cancelledCleanup.isEmpty())
    }

    @Test fun frozenV3CleanupMigratesPublicHandleWithoutTrustingFingerprint() {
        // Independent v3 wire fixture: empty operational data + one own ID cleanup.
        val bytes = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bytes).use { out ->
            out.writeInt(0x41505231); out.writeInt(3)
            out.writeBoolean(false); out.writeBoolean(false); out.writeInt(-1); out.writeLong(0)
            out.writeBoolean(false); out.writeInt(0); out.writeInt(0); out.writeBoolean(false); out.writeBoolean(false)
            out.writeBoolean(false); out.writeInt(0); out.writeBoolean(false); out.writeInt(1)
            out.writeUTF("old"); out.writeUTF(pair.value); out.writeInt(4); out.writeUTF("own")
            out.writeBoolean(true); out.writeUTF("a".repeat(64)); out.writeBoolean(false)
        }
        val data = AirPodsRuntimeCodec.decode(bytes.toByteArray())
        assertTrue(data.pendingCleanup.single().handleCaptured)
        assertTrue(data.cancelledCleanup.isEmpty())
        assertEquals(data, AirPodsRuntimeCodec.decode(AirPodsRuntimeCodec.encode(data)))
    }

}
