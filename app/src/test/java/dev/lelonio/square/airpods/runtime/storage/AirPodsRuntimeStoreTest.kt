// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.storage

import org.junit.Assert.*
import org.junit.Test

class AirPodsRuntimeStoreTest {
    private class Disk : RuntimePersistence {
        var bytes: ByteArray? = null; var fail = false
        override fun read() = bytes
        override fun commit(bytes: ByteArray): Boolean { if (fail) return false; this.bytes = bytes; return true }
    }
    private val a = PairAddress("AA:BB:CC:DD:EE:01")
    private val b = PairAddress("AA:BB:CC:DD:EE:02")
    @Test fun defaultsRemainLocalOffAndAlternateTrueAcrossRoundTrip() {
        val disk = Disk(); val store = AirPodsRuntimeStore(disk)
        store.select(a).getOrThrow()
        val data = AirPodsRuntimeCodec.decode(disk.bytes!!)
        assertFalse(data.start.enabled); assertNull(data.pairs[a]!!.verification)
        assertTrue(data.pairs[a]!!.headPacketAlternate)
        assertEquals(HostSettings(), data.pairs[a]!!.host)
        assertFalse(data.pairs[a]!!.host.automationsEnabled)
        assertEquals(data, AirPodsRuntimeCodec.decode(AirPodsRuntimeCodec.encode(data)))
    }
    @Test fun pairSettingsAreIndependentAndDoNotAcquireHardwareState() {
        val store = AirPodsRuntimeStore(Disk()); store.select(a).getOrThrow()
        store.configure(a) { it.copy(secondMode = QuickMode.ADAPTIVE, headPacketAlternate = false, host = it.host.copy(conversationVolumePercent = 85)) }.getOrThrow()
        store.recordSetup(a, PairVerification("A3063", "build-A", 10)).getOrThrow()
        store.select(b).getOrThrow()
        val data = store.snapshot().getOrThrow()
        assertEquals(QuickMode.ADAPTIVE, data.pairs[a]!!.secondMode)
        assertFalse(data.pairs[a]!!.headPacketAlternate)
        assertTrue(data.pairs[b]!!.headPacketAlternate)
        assertNull(data.pairs[b]!!.secondMode); assertNull(data.pairs[b]!!.verification)
        assertFalse(data.start.enabled)
    }
    @Test fun migrationNeverImportsEnabledSelectionVerificationOrConformanceAndIsOneShot() {
        val store = AirPodsRuntimeStore(Disk())
        store.migratePoc(LegacyPocSnapshot(a.value, "ADAPTIVE")).getOrThrow()
        store.migratePoc(LegacyPocSnapshot(b.value, "OFF")).getOrThrow()
        val data = store.snapshot().getOrThrow()
        assertNull(data.selectedPair); assertFalse(data.start.enabled); assertNull(data.pairs[a]!!.verification)
        assertEquals(QuickMode.ADAPTIVE, data.pairs[a]!!.secondMode); assertFalse(data.pairs.containsKey(b))
    }
    @Test fun invalidMigrationCacheDoesNotBecomeSetup() {
        val store = AirPodsRuntimeStore(Disk()); store.migratePoc(LegacyPocSnapshot("invalid", "ANC")).getOrThrow()
        assertTrue(store.snapshot().getOrThrow().pairs.isEmpty())
    }
    @Test fun rangesAndCommitFailurePreservePreviousDurableData() {
        val disk = Disk(); val store = AirPodsRuntimeStore(disk); store.select(a).getOrThrow()
        val before = disk.bytes!!.clone()
        for (value in listOf(9, 86, Int.MAX_VALUE)) assertTrue(store.configure(a) { it.copy(host = it.host.copy(conversationVolumePercent = value)) }.isFailure)
        assertArrayEquals(before, disk.bytes)
        disk.fail = true
        assertTrue(store.configure(a) { it.copy(headPacketAlternate = false) }.isFailure)
        assertArrayEquals(before, disk.bytes)
    }
    @Test fun unknownSchemaCorruptionAndTruncationFailClosed() {
        val encoded = AirPodsRuntimeCodec.encode(AirPodsRuntimeData())
        val badVersion = encoded.clone().also { it[7] = 127 }
        for (bytes in listOf(byteArrayOf(), encoded.copyOf(10), badVersion, encoded + byteArrayOf(0))) {
            val disk = Disk(); disk.bytes = bytes
            assertTrue(AirPodsRuntimeStore(disk).snapshot().isFailure)
        }
    }
    @Test fun configTransactionCannotOverwriteConcurrentStopState() {
        val store = AirPodsRuntimeStore(Disk()); store.select(a).getOrThrow()
        store.transaction { it.copy(start = it.start.copy(suppressUntilExplicitResume = true, suppressReason = SuppressReason.USER_STOP, consentGeneration = 9)) to Unit }.getOrThrow()
        store.configure(a) { it.copy(headPacketAlternate = false) }.getOrThrow()
        assertTrue(store.snapshot().getOrThrow().start.suppressUntilExplicitResume)
        assertEquals(9L, store.snapshot().getOrThrow().start.consentGeneration)
    }
    @Test fun privateAddressIsRedactedFromNestedDiagnostics() {
        val data = AirPodsRuntimeData(pairs = mapOf(a to PairSettings()), selectedPair = a)
        assertFalse(data.toString().contains(a.value))
    }
    @Test fun markerExitIdentityConsentAndEveryHostSettingSurvivePersistence() {
        val key = ExitKey(100, 7, "own", 10)
        val marker = ProcessMarker("epoch", 7, "own", 99)
        val host = HostSettings(true, true, true, true, true, true, true, false, 85, true, true)
        val data = AirPodsRuntimeData(start = StartState(true, true, SuppressReason.USER_REQUESTED_EXIT, 3, marker, listOf(key), listOf(key), key, true), selectedPair = a,
            pairs = mapOf(a to PairSettings(PairVerification("A3063", "build", 50), QuickMode.OFF, false, host)), pocMigrationCompleted = true)
        assertEquals(data, AirPodsRuntimeCodec.decode(AirPodsRuntimeCodec.encode(data)))
    }
}
