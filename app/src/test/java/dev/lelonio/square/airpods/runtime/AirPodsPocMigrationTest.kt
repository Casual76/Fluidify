package dev.lelonio.square.airpods.runtime

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.lelonio.square.airpods.AirPodsPreferences
import dev.lelonio.square.airpods.runtime.storage.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
class AirPodsPocMigrationTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val prefs = app.getSharedPreferences("airpods_internal", Context.MODE_PRIVATE)
    private val address = "AA:BB:CC:DD:EE:01"
    private class Disk : RuntimePersistence {
        var bytes: ByteArray? = null
        var fail = false
        override fun read() = bytes
        override fun commit(bytes: ByteArray): Boolean {
            if (fail) return false
            this.bytes = bytes
            return true
        }
    }

    @Test fun actualLegacyKeysImportOnlyUnverifiedCandidateAndLocalMode() = runBlocking {
        prefs.edit().putBoolean("enabled", true).putString("verified_address", address)
            .putString("verified_model", "A3063").putString("platform_build", "current")
            .putString("second_$address", "ADAPTIVE").commit()
        val store = AirPodsRuntimeStore(Disk())
        AirPodsPreferences(app).migrateInto(store).getOrThrow()
        val data = store.snapshot().getOrThrow()
        assertTrue(data.pocMigrationCompleted)
        assertNull(data.selectedPair)
        assertFalse(data.start.enabled)
        assertEquals(0L, data.start.consentGeneration)
        assertNull(data.pairs[PairAddress(address)]!!.verification)
        assertEquals(QuickMode.ADAPTIVE, data.pairs[PairAddress(address)]!!.secondMode)
        assertEquals(HostSettings(), data.pairs[PairAddress(address)]!!.host)
        assertTrue(prefs.getBoolean("enabled", false)) // source is left untouched
    }

    @Test fun completedMigrationDoesNotReadBrokenLegacyValuesOrResetRuntimeSettings() = runBlocking {
        val store = AirPodsRuntimeStore(Disk())
        store.migratePoc(LegacyPocSnapshot(address, "OFF")).getOrThrow()
        val pair = PairAddress(address)
        store.select(pair).getOrThrow()
        store.configure(pair) { it.copy(headPacketAlternate = false) }.getOrThrow()
        val before = store.snapshot().getOrThrow()
        prefs.edit().putInt("verified_address", 42).commit()
        AirPodsPreferences(app).migrateInto(store).getOrThrow()
        assertEquals(before, store.snapshot().getOrThrow())
    }

    @Test fun failedCommitPreservesLegacyCacheAndCanBeRetried() = runBlocking {
        prefs.edit().putString("verified_address", address).putString("second_$address", "TRANSPARENCY").commit()
        val disk = Disk().also { it.fail = true }
        val store = AirPodsRuntimeStore(disk)
        assertTrue(AirPodsPreferences(app).migrateInto(store).isFailure)
        assertFalse(store.snapshot().getOrThrow().pocMigrationCompleted)
        assertEquals(address, prefs.getString("verified_address", null))
        disk.fail = false
        AirPodsPreferences(app).migrateInto(store).getOrThrow()
        assertEquals(QuickMode.TRANSPARENCY, store.snapshot().getOrThrow().pairs[PairAddress(address)]!!.secondMode)
    }

    @Test fun corruptLegacyTypeDoesNotMarkMigrationCompleted() = runBlocking {
        prefs.edit().putBoolean("enabled", true).putInt("verified_address", 42).commit()
        val store = AirPodsRuntimeStore(Disk())
        assertTrue(AirPodsPreferences(app).migrateInto(store).isFailure)
        val data = store.snapshot().getOrThrow()
        assertFalse(data.pocMigrationCompleted)
        assertFalse(data.start.enabled)
        assertTrue(data.pairs.isEmpty())
    }

    @Test fun invalidAddressOrAncPreferenceNeverBecomesSelectedOrVerified() = runBlocking {
        prefs.edit().putString("verified_address", "not an address").putBoolean("enabled", true).commit()
        val store = AirPodsRuntimeStore(Disk())
        AirPodsPreferences(app).migrateInto(store).getOrThrow()
        assertTrue(store.snapshot().getOrThrow().pairs.isEmpty())
        val otherStore = AirPodsRuntimeStore(Disk())
        prefs.edit().putString("verified_address", address).putString("second_$address", "ANC").commit()
        AirPodsPreferences(app).migrateInto(otherStore).getOrThrow()
        val other = otherStore.snapshot().getOrThrow()
        assertNull(other.selectedPair)
        assertNull(other.pairs[PairAddress(address)]!!.verification)
        assertNull(other.pairs[PairAddress(address)]!!.secondMode)
        assertFalse(other.start.enabled)
    }
}
