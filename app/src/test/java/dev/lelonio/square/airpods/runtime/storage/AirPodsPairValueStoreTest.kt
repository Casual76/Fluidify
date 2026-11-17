// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.storage

import dev.lelonio.square.airpods.*
import dev.lelonio.square.airpods.protocol.*
import org.junit.Assert.*
import org.junit.Test

/** Prepared only: synthetic persistence, no Android preferences or radio assertions. */
class AirPodsPairValueStoreTest {
    private val pair = PairAddress("02:00:00:00:00:81")
    private val other = PairAddress("02:00:00:00:00:82")
    private class Persistence : PairValuePersistence {
        val data = mutableMapOf<PairAddress, ByteArray>()
        var fail = false
        override fun read(pair: PairAddress) = data[pair]?.clone()
        override fun write(pair: PairAddress, bytes: ByteArray): Boolean {
            if (fail) return false
            data[pair] = bytes.clone(); return true
        }
        override fun remove(pair: PairAddress): Boolean { if (fail) return false; data.remove(pair); return true }
    }
    @Test fun rxAndChoiceRemainSeparateAcrossStoreRecreationAndPairRemoval() {
        val persistence = Persistence(); val store = AirPodsPairValueStore(persistence)
        val values = AirPodsPairValues(
            observed = mapOf(CommandProperty.MODE to SavedProperty(PropertyValue.Number(3), 5),
                CommandProperty.LISTENING_MODE_CONFIGS to SavedProperty(PropertyValue.Number(0), 6)),
            choices = mapOf(CommandProperty.MODE to SavedChoice(PropertyValue.Number(4), 7, DesiredStatus.SENT),
                CommandProperty.EQ to SavedChoice(PropertyValue.Eq(true, 0, 50, 100), 8, DesiredStatus.UNCERTAIN),
                CommandProperty.NAME to SavedChoice(PropertyValue.Name("Caffè"), 9, DesiredStatus.FAILED)),
            casePercent = SavedCasePercent(0, 10))
        store.save(pair, values).getOrThrow()
        val restored = AirPodsPairValueStore(persistence)
        assertEquals(values, restored.load(pair).getOrThrow())
        assertEquals(AirPodsPairValues(), restored.load(other).getOrThrow())
        restored.forget(pair).getOrThrow()
        assertEquals(AirPodsPairValues(), store.load(pair).getOrThrow())
    }
    @Test fun corruptOrFailedPersistenceIsExplicitAndDoesNotInventRx() {
        val persistence = Persistence(); val store = AirPodsPairValueStore(persistence)
        persistence.data[pair] = byteArrayOf(1,2,3)
        assertTrue(store.load(pair).isFailure)
        persistence.fail = true
        assertTrue(store.save(pair, AirPodsPairValues()).isFailure)
        assertTrue(store.forget(pair).isFailure)
        assertEquals(AirPodsPairValues(), store.load(other).getOrThrow())
    }
}
