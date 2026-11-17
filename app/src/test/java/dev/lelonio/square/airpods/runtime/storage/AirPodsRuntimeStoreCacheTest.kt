package dev.lelonio.square.airpods.runtime.storage

import org.junit.Assert.*
import org.junit.Test

class AirPodsRuntimeStoreCacheTest {
    private class Disk : RuntimePersistence {
        var bytes: ByteArray? = null
        var version = 0
        var unavailable = false
        var failCommit = false
        var reads = 0
        override fun cacheToken(): Any { check(!unavailable); return version }
        override fun read(): ByteArray? { check(!unavailable); reads++; return bytes?.clone() }
        override fun commit(bytes: ByteArray): Boolean {
            if (failCommit) { unavailable = true; return false }
            this.bytes = bytes.clone(); version++; return true
        }
    }
    @Test fun samplesReuseDecodeButSettingsStopAndRemovalInvalidateImmediately() {
        val disk = Disk(); val store = AirPodsRuntimeStore(disk)
        val pair = PairAddress("AA:BB:CC:DD:EE:01")
        store.select(pair).getOrThrow()
        val before = store.snapshot().getOrThrow()
        val reads = disk.reads
        repeat(1000) { assertSame(before, store.snapshot().getOrThrow()) }
        assertEquals(reads, disk.reads)
        store.configure(pair) { it.copy(host = it.host.copy(headGestures = true)) }.getOrThrow()
        assertTrue(store.snapshot().getOrThrow().pairs.getValue(pair).host.headGestures)
        store.transaction { it.copy(start = it.start.copy(suppressUntilExplicitResume = true,
            suppressReason = SuppressReason.USER_STOP, consentGeneration = 9)) to Unit }.getOrThrow()
        assertEquals(9L, store.snapshot().getOrThrow().start.consentGeneration)
        store.transaction { it.copy(selectedPair = null, pairs = emptyMap()) to Unit }.getOrThrow()
        assertTrue(store.snapshot().getOrThrow().pairs.isEmpty())
    }
    @Test fun warmCacheCannotHideUnavailablePersistenceOrFailedCommit() {
        val disk = Disk(); val store = AirPodsRuntimeStore(disk)
        store.snapshot().getOrThrow()
        disk.unavailable = true
        assertTrue(store.snapshot().isFailure)
        disk.unavailable = false; disk.failCommit = true
        assertTrue(store.select(PairAddress("AA:BB:CC:DD:EE:01")).isFailure)
        assertTrue(store.snapshot().isFailure)
    }
    @Test fun tokenChangeCannotHideCorruption() {
        val disk = Disk(); val store = AirPodsRuntimeStore(disk)
        store.snapshot().getOrThrow()
        disk.bytes = byteArrayOf(1, 2); disk.version++
        assertTrue(store.snapshot().isFailure)
    }
    @Test fun snapshotCannotBeMutatedToBypassTransactions() {
        val disk = Disk(); val store = AirPodsRuntimeStore(disk)
        val pair = PairAddress("AA:BB:CC:DD:EE:01")
        store.select(pair).getOrThrow()
        val snapshot = store.snapshot().getOrThrow()
        assertTrue(runCatching { (snapshot.pairs as MutableMap<PairAddress, PairSettings>).clear() }.isFailure)
        assertTrue(store.snapshot().getOrThrow().pairs.containsKey(pair))
    }
}
