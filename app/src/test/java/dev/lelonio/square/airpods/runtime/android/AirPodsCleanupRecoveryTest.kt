// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.android

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import dev.lelonio.square.airpods.runtime.lifecycle.*
import dev.lelonio.square.airpods.runtime.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

/** Actual controller/receiver paths; only Android facts, CDM and exit history are fake.
 * FGS is counted but never attached; no transport factory, radio or device is opened.
 * PREPARED, NOT EXECUTED by corrective I2 implementer. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
class AirPodsCleanupRecoveryTest {
    @Before fun processIdentity() { org.robolectric.shadows.ShadowProcess.setPid(41003) }
    private class ContextFixture(base: Context) : ContextWrapper(base) {
        var starts = 0
        override fun checkSelfPermission(permission: String) = PackageManager.PERMISSION_GRANTED
        override fun startForegroundService(service: Intent): ComponentName {
            starts++
            return ComponentName(this, AirPodsControlService::class.java)
        }
    }
    private class Facts : BluetoothRuntimeFacts {
        override val adapter: BluetoothAdapter? = null
        var permission = true
        var bond = BondFact.BONDED
        var profile = ProfileConnection.UNRESOLVED
        var accepted = true
        var requests = 0
        val callbacks = mutableListOf<() -> Unit>()
        override fun permitted() = permission
        override fun bond(address: PairAddress) = bond
        override fun connection(address: PairAddress) = profile
        override fun prepare() = Unit
        override fun audioSettingsIntent() = Intent()
        override fun whenProfileReady(ready: () -> Unit): AutoCloseable? {
            requests++
            if (!accepted) return null
            callbacks.add(ready)
            // Keep captured callback to explicitly exercise delivery after cancellation.
            return AutoCloseable {}
        }
        fun ready() { profile = ProfileConnection.CONNECTED; callbacks.toList().forEach { it() } }
    }
    private class Registry(val ownPackage: String, val pair: PairAddress, val other: PairAddress) : CompanionRegistryFacade {
        val selected = OwnedAssociation(4, pair, ownPackage, "a".repeat(64))
        val otherOwn = OwnedAssociation(5, other, ownPackage)
        val foreign = OwnedAssociation(6, pair, "foreign.package")
        val associations = mutableListOf(selected, otherOwn, foreign)
        var queriesFail = false
        var queryPermissionDenied = false
        var observationStarts = 0
        var removal = "ok"
        var afterRemove: (() -> Unit)? = null
        var stops = 0
        var stopAllowed = true
        var metadataChanges = false
        var stopAfterEffect = "ok"
        val removed = mutableListOf<OwnedAssociation>()
        override fun hasFeature() = true
        override fun hasObservePermission() = true
        override fun isBonded(address: PairAddress) = true
        override fun ownAssociations(): List<OwnedAssociation> { if (queryPermissionDenied) throw SecurityException(); check(!queriesFail); return associations.toList() }
        override fun start(registration: ObservationRegistration): Boolean {
            observationStarts++
            if (metadataChanges) mutateMetadata(registration.association)
            return true
        }
        private fun mutateMetadata(target: OwnedAssociation) {
            val at = associations.indexOfFirst { it.sameEndpoint(target) }
            if (at >= 0) associations[at] = associations[at].copy(identity = if (associations[at].identity == "b".repeat(64)) "c".repeat(64) else "b".repeat(64))
        }
        override fun stop(registration: ObservationRegistration): Boolean {
            stops++
            if (metadataChanges) mutateMetadata(registration.association)
            if (stopAfterEffect == "throw") error("stop after effect")
            if (stopAfterEffect == "false") return false
            return stopAllowed
        }
        override fun disassociate(association: OwnedAssociation): Boolean {
            if (removal == "throw") error("platform failure")
            if (removal == "false") return false
            removed.add(association); associations.removeAll { it.sameEndpoint(association) }; afterRemove?.invoke()
            if (removal == "throw-after") error("remove after effect")
            return removal != "false-after"
        }
    }
    private class Disk : RuntimePersistence {
        var bytes: ByteArray? = null
        var failNext = false
        override fun read() = bytes
        override fun commit(bytes: ByteArray): Boolean {
            if (failNext) { failNext = false; return false }
            this.bytes = bytes.clone(); return true
        }
    }
    private class Fixture : AutoCloseable {
        val context = ContextFixture(RuntimeEnvironment.getApplication())
        val pair = PairAddress("AA:BB:CC:DD:EE:01")
        val other = PairAddress("AA:BB:CC:DD:EE:02")
        val facts = Facts()
        val registry = Registry(context.packageName, pair, other)
        val visible = MutableStateFlow(true)
        var history: ExitHistory = ExitHistory.Available(emptyList())
        var factoryCalls = 0
        val disk = Disk()
        var controller: AirPodsAndroidController
        fun createController(): AirPodsAndroidController = AirPodsAndroidController(context, visible, { true },
            { factoryCalls++; error("No socket") }, factsOverride = facts, registryOverride = registry,
            exitHistoryOverride = ExitHistoryProvider { history }, runtimeDispatcher = Dispatchers.Unconfined,
            persistenceOverride = disk).also { ReflectionHelpers.setField(it, "migrationReady", true) }
        fun abandon() {
            context.unregisterReceiver(ReflectionHelpers.getField(controller, "receiver"))
            ReflectionHelpers.getField<CoroutineScope>(controller, "processScope").cancel()
        }
        fun recreate() { abandon(); controller = createController() }
        init {
            context.getSharedPreferences("airpods_runtime_v1", Context.MODE_PRIVATE).edit().clear().commit()
            controller = createController()
            controller.store.select(other).getOrThrow()
            controller.store.recordSetup(other, PairVerification("other", Build.FINGERPRINT, 1)).getOrThrow()
            controller.store.select(pair).getOrThrow()
            controller.store.recordSetup(pair, PairVerification("A3063", Build.FINGERPRINT, 1)).getOrThrow()
            controller.store.transaction { it.copy(start = StartState(enabled = true, consentGeneration = 7)) to Unit }.getOrThrow()
        }
        fun presence() = controller.presence(pair.value, 4)
        fun bondBroadcast() {
            ReflectionHelpers.getField<BroadcastReceiver>(controller, "receiver").onReceive(context,
                Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        }
        fun data() = controller.store.snapshot().getOrThrow()
        override fun close() {
            runBlocking { controller.stopControls() }
            context.unregisterReceiver(ReflectionHelpers.getField(controller, "receiver"))
            ReflectionHelpers.getField<CoroutineScope>(controller, "processScope").cancel()
        }
    }
    private fun retry(f: Fixture): CompanionCleanupResult {
        val starts = f.registry.observationStarts
        return runBlocking { f.controller.retryPendingCleanup() }.also { assertEquals(starts, f.registry.observationStarts) }
    }
    private fun forget(f: Fixture) = runBlocking { f.controller.forget() }
    private fun assertQuiet(f: Fixture, starts: Int = 0) {
        assertEquals(starts, f.context.starts); assertEquals(0, f.factoryCalls)
    }
    @Test fun falseAndThrowRecoverAfterRecreationWithAndWithoutTicketAndStaleCallbacks() {
        for (failure in listOf("false", "throw")) for (active in listOf(false, true)) Fixture().use { f ->
            f.presence()
            if (active) f.facts.ready()
            val starts = f.context.starts
            f.registry.removal = failure
            assertTrue(forget(f) is GateResult.Denied)
            assertNull(f.data().selectedPair); assertNull(f.data().pairs[f.pair])
            assertEquals(1, f.data().pendingCleanup.size)
            f.facts.ready(); assertQuiet(f, starts)
            f.recreate(); assertNull(f.data().selectedPair)
            f.registry.removal = "ok"
            assertTrue(retry(f) is CompanionCleanupResult.Batch)
            assertTrue(f.data().pendingCleanup.isEmpty())
            assertEquals(listOf(f.registry.selected), f.registry.removed)
            assertTrue(retry(f) is CompanionCleanupResult.NoPending)
            assertQuiet(f, starts)
        }
    }
    @Test fun bondNoneUsesSameDurablePathWithAndWithoutTicket() {
        for (active in listOf(false, true)) Fixture().use { f ->
            f.presence(); if (active) f.facts.ready()
            f.registry.removal = "false"; f.facts.bond = BondFact.NONE; f.bondBroadcast()
            assertNull(f.data().selectedPair); assertEquals(1, f.data().pendingCleanup.size)
            f.recreate(); f.registry.removal = "ok"; retry(f)
            assertTrue(f.data().pendingCleanup.isEmpty()); assertEquals(0, f.factoryCalls)
        }
    }
    @Test fun failedQueryNeverBecomesAbsenceOrDeletionAuthority() = Fixture().use { f ->
        f.registry.queriesFail = true; forget(f)
        assertEquals(1, f.data().pendingCleanup.size); assertTrue(f.registry.removed.isEmpty())
        f.recreate(); retry(f); assertEquals(1, f.data().pendingCleanup.size)
        f.registry.queriesFail = false; retry(f)
        assertEquals(1, f.data().pendingCleanup.size); assertTrue(f.registry.removed.isEmpty())
        f.registry.associations.remove(f.registry.selected); retry(f)
        assertTrue(f.data().pendingCleanup.isEmpty()); assertQuiet(f)
    }
    @Test fun validMarkerQueryFailureAndPermissionLossRetainAuthorityWithoutObservation() = Fixture().use { f ->
        f.registry.removal = "false"; forget(f) // no presence registration, no ticket
        val pending = f.data().pendingCleanup
        f.recreate(); f.registry.queriesFail = true; retry(f)
        assertEquals(pending, f.data().pendingCleanup)
        f.registry.queriesFail = false; f.registry.queryPermissionDenied = true; retry(f)
        assertEquals(pending, f.data().pendingCleanup)
        f.registry.queryPermissionDenied = false; f.registry.removal = "ok"; retry(f)
        assertTrue(f.data().pendingCleanup.isEmpty()); assertEquals(0, f.registry.observationStarts); assertQuiet(f)
    }
    @Test fun captureCommitFailureRetainsSelectionAndVerificationAndNeverDisassociates() = Fixture().use { f ->
        f.disk.failNext = true
        assertEquals(GateResult.Denied(GateReason.PERSISTENCE_FAILED), forget(f))
        assertEquals(f.pair, f.data().selectedPair); assertNotNull(f.data().pairs[f.pair]?.verification)
        assertTrue(f.data().pendingCleanup.isEmpty()); assertTrue(f.registry.removed.isEmpty())
        assertQuiet(f)
    }
    @Test fun completionCommitFailureKeepsMarkerAndAlreadyRemovedRetryIsIdempotent() = Fixture().use { f ->
        f.registry.afterRemove = { f.disk.failNext = true }
        assertTrue(forget(f) is GateResult.Denied)
        assertEquals(1, f.data().pendingCleanup.size)
        f.recreate(); retry(f)
        assertTrue(f.data().pendingCleanup.isEmpty()); assertEquals(1, f.registry.removed.size)
        assertQuiet(f)
    }
    @Test fun retryPreservesNewPairPreferencesVerificationConsentAndObservation() = Fixture().use { f ->
        f.registry.removal = "false"; forget(f)
        runBlocking { f.controller.selectPair(f.other).getOrThrow() }
        f.controller.store.configure(f.other) { it.copy(headPacketAlternate = false) }.getOrThrow()
        f.controller.store.transaction { it.copy(start = it.start.copy(enabled = true,
            suppressUntilExplicitResume = false, suppressReason = null, consentGeneration = 20)) to Unit }.getOrThrow()
        f.controller.presence(f.other.value, 5)
        val before = f.data(); val observation = ReflectionHelpers.getField<Any?>(f.controller, "observation")
        val starts = f.context.starts
        f.registry.removal = "ok"; retry(f)
        assertEquals(before.copy(pendingCleanup = emptyList()), f.data())
        assertEquals(observation, ReflectionHelpers.getField<Any?>(f.controller, "observation"))
        assertTrue(f.registry.associations.contains(f.registry.otherOwn)); assertQuiet(f, starts)
    }
    @Test fun newlySelectedSamePairNeverLosesItsAssociationOrVerificationOnRetry() = Fixture().use { f ->
        f.registry.removal = "false"; forget(f)
        runBlocking { f.controller.selectPair(f.pair).getOrThrow() }
        f.controller.store.recordSetup(f.pair, PairVerification("new-model", Build.FINGERPRINT, 2)).getOrThrow()
        val before = f.data(); f.registry.removal = "ok"; retry(f)
        assertEquals(before, f.data()); assertTrue(f.registry.removed.isEmpty()); assertQuiet(f)
    }
    @Test fun reusedIdReplacedSamePairAndForeignAssociationAreNeverRemoved() {
        for (replacement in listOf(
            OwnedAssociation(4, PairAddress("AA:BB:CC:DD:EE:02"), "placeholder", "b".repeat(64)),
            OwnedAssociation(44, PairAddress("AA:BB:CC:DD:EE:01"), "placeholder", "b".repeat(64)),
            OwnedAssociation(4, PairAddress("AA:BB:CC:DD:EE:01"), "foreign.package", "a".repeat(64)),
        )) Fixture().use { f ->
            f.registry.removal = "false"; forget(f); f.recreate()
            f.registry.associations.remove(f.registry.selected)
            val value = if (replacement.packageName == "placeholder") replacement.copy(packageName = f.context.packageName) else replacement
            f.registry.associations.add(value); f.registry.removal = "ok"; retry(f)
            assertTrue(f.registry.removed.isEmpty()); assertTrue(f.registry.associations.contains(value)); assertQuiet(f)
        }
    }
    @Test fun stopFailureIsRecoverableAndAlreadyAbsentDoesNotDisassociate() = Fixture().use { f ->
        f.presence(); f.registry.stopAllowed = false; forget(f)
        assertEquals(1, f.data().pendingCleanup.size); assertTrue(f.registry.removed.isEmpty())
        f.registry.stopAllowed = true; f.registry.associations.remove(f.registry.selected)
        f.recreate(); retry(f)
        assertTrue(f.data().pendingCleanup.isEmpty()); assertTrue(f.registry.removed.isEmpty()); assertQuiet(f)
    }
    @Test fun unknownBondBondingAndPermissionLossAreNotForget() {
        for (bond in listOf(BondFact.UNKNOWN, BondFact.BONDING)) Fixture().use { f ->
            f.facts.bond = bond; f.bondBroadcast()
            assertEquals(f.pair, f.data().selectedPair); assertTrue(f.data().pendingCleanup.isEmpty())
        }
        Fixture().use { f ->
            f.facts.permission = false; f.bondBroadcast()
            assertEquals(SuppressReason.REVOKED, f.data().start.suppressReason)
            assertEquals(f.pair, f.data().selectedPair); assertTrue(f.data().pendingCleanup.isEmpty()); assertQuiet(f)
        }
    }
    @Test fun metadataChangingStartStopAndExplicitRetryUsePublicHandleAfterRecreation() {
        for (failure in listOf("false", "throw", "false-after", "throw-after")) Fixture().use { f ->
            f.registry.metadataChanges = true
            f.presence()
            f.registry.removal = failure
            assertTrue(forget(f) is GateResult.Denied)
            assertEquals(1, f.data().pendingCleanup.size)
            val requests = f.facts.requests
            val starts = f.registry.observationStarts
            f.recreate(); f.registry.removal = "ok"
            retry(f)
            assertTrue(f.data().pendingCleanup.isEmpty())
            assertEquals(starts, f.registry.observationStarts)
            assertEquals(requests, f.facts.requests)
            assertEquals(1, f.registry.removed.size)
            assertQuiet(f)
        }
    }
    @Test fun stopFalseOrThrowAfterMetadataEffectRetainsMarkerAndInvalidatesCallbacks() {
        for (failure in listOf("false", "throw")) Fixture().use { f ->
            f.registry.metadataChanges = true; f.presence()
            f.registry.stopAfterEffect = failure
            assertTrue(forget(f) is GateResult.Denied)
            assertEquals(1, f.data().pendingCleanup.size)
            assertTrue(f.registry.removed.isEmpty())
            f.facts.ready(); assertQuiet(f)
            f.recreate(); f.registry.stopAfterEffect = "ok"
            retry(f)
            assertTrue(f.data().pendingCleanup.isEmpty())
            assertEquals(1, f.registry.removed.size); assertQuiet(f)
        }
    }
    @Test fun samePairSetupSupersessionSurvivesUnselectionAndRecreation() = Fixture().use { f ->
        f.registry.removal = "false"; forget(f)
        val cancelled = f.data().pendingCleanup.single()
        runBlocking { f.controller.selectPair(f.pair).getOrThrow() }
        f.controller.store.recordSetup(f.pair, PairVerification("new", Build.FINGERPRINT, 2)).getOrThrow()
        runBlocking { f.controller.selectPair(f.other).getOrThrow() }
        f.recreate(); f.registry.removal = "ok"
        assertEquals(CompanionCleanupResult.NoPending, retry(f))
        assertEquals(listOf(cancelled), f.data().cancelledCleanup)
        assertTrue(f.registry.removed.isEmpty()); assertQuiet(f)
    }
    @Test fun failedSetupSupersessionCommitKeepsOriginalRemovalRequest() = Fixture().use { f ->
        f.registry.removal = "false"; forget(f)
        val pending = f.data().pendingCleanup
        f.disk.failNext = true
        assertTrue(runBlocking { f.controller.selectPair(f.pair) }.isFailure)
        assertEquals(pending, f.data().pendingCleanup); assertTrue(f.data().cancelledCleanup.isEmpty())
        f.registry.removal = "ok"; retry(f)
        assertTrue(f.data().pendingCleanup.isEmpty()); assertEquals(1, f.registry.removed.size); assertQuiet(f)
    }

    @Test fun explicitNewSetupResetsRememberedOldIdBeforeNextForget() = Fixture().use { f ->
        f.presence(); runBlocking { f.controller.stopControls() }
        val replacement = f.registry.selected.copy(id = 44)
        f.registry.associations.removeAll { it.sameEndpoint(f.registry.selected) }
        f.registry.associations.add(replacement)
        runBlocking { f.controller.selectPair(f.pair).getOrThrow() }
        assertTrue(forget(f) is GateResult.Stopped)
        assertEquals(listOf(replacement), f.registry.removed)
        assertTrue(f.data().pendingCleanup.isEmpty()); assertQuiet(f)
    }

}
