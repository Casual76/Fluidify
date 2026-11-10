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
 * PREPARED, NOT EXECUTED by corrective I1 implementer. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
class AirPodsDeferredIngressTest {
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
        var stops = 0
        var stopAllowed = true
        val removed = mutableListOf<OwnedAssociation>()
        override fun hasFeature() = true
        override fun hasObservePermission() = true
        override fun isBonded(address: PairAddress) = true
        override fun ownAssociations() = associations.toList()
        override fun start(registration: ObservationRegistration) = true
        override fun stop(registration: ObservationRegistration): Boolean { stops++; return stopAllowed }
        override fun disassociate(association: OwnedAssociation): Boolean {
            removed.add(association); associations.remove(association); return true
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
        val controller: AirPodsAndroidController
        init {
            context.getSharedPreferences("airpods_runtime_v1", Context.MODE_PRIVATE).edit().clear().commit()
            controller = AirPodsAndroidController(context, visible, { true }, { factoryCalls++; error("Must never attach in this fixture") },
                factsOverride = facts, registryOverride = registry, exitHistoryOverride = ExitHistoryProvider { history }, runtimeDispatcher = Dispatchers.Unconfined)
            ReflectionHelpers.setField(controller, "migrationReady", true)
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
    @Test fun companionDelayedProxyAdmitsOnceWithoutAnotherBroadcast() = Fixture().use { f ->
        f.presence(); f.presence()
        assertEquals(1, f.facts.requests); assertEquals(0, f.context.starts)
        f.facts.ready(); f.facts.ready(); f.presence()
        assertEquals(1, f.context.starts); assertTrue(f.controller.hasAdmission())
        assertEquals(0, f.factoryCalls)
    }
    @Test fun concurrentOriginalCallbacksAndReadyDeliveryKeepOneOwner() = Fixture().use { f ->
        val barrier = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(3)
        try {
            val jobs = (1..3).map { pool.submit { check(barrier.await(3, TimeUnit.SECONDS)); f.presence() } }
            barrier.countDown(); jobs.forEach { it.get(3, TimeUnit.SECONDS) }
            assertEquals(1, f.facts.requests)
            val readyJobs = (1..3).map { pool.submit { f.facts.ready() } }
            readyJobs.forEach { it.get(3, TimeUnit.SECONDS) }
            assertEquals(1, f.context.starts); assertTrue(f.controller.hasAdmission())
        } finally { pool.shutdownNow() }
    }
    @Test fun callbackCapturedBeforeStopCannotReopenAfterBarrier() = Fixture().use { f ->
        f.presence()
        val captured = f.facts.callbacks.single()
        val capturedBarrier = CountDownLatch(1); val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val job = pool.submit {
                capturedBarrier.countDown(); check(release.await(3, TimeUnit.SECONDS))
                f.facts.profile = ProfileConnection.CONNECTED; captured()
            }
            assertTrue(capturedBarrier.await(3, TimeUnit.SECONDS))
            runBlocking { f.controller.stopControls() }
            release.countDown(); job.get(3, TimeUnit.SECONDS)
            assertEquals(0, f.context.starts); assertEquals(0, f.factoryCalls)
        } finally { release.countDown(); pool.shutdownNow() }
    }
    @Test fun delayedCallbackRechecksPairConsentPermissionAndAssociation() {
        listOf<(Fixture) -> Unit>(
            { f -> runBlocking { f.controller.selectPair(f.other).getOrThrow() } },
            { f -> f.controller.store.transaction { it.copy(start = it.start.copy(consentGeneration = 8)) to Unit }.getOrThrow() },
            { f -> f.facts.permission = false; runBlocking { f.controller.permissionsChanged() } },
            { f -> f.registry.associations.remove(f.registry.selected) },
            { f -> f.visible.value = false }, // no background opt-in
        ).forEach { intervene -> Fixture().use { f ->
            f.presence(); intervene(f); f.facts.ready()
            assertEquals(0, f.context.starts); assertEquals(0, f.factoryCalls)
        } }
    }
    @Test fun currentSystemUserStopIsReconciledBeforeFgsOnReadiness() = Fixture().use { f ->
        f.presence()
        f.history = ExitHistory.Available(listOf(OwnProcessExit(ExitKey(200, 41003, Application.getProcessName(), 10), ExitCause.USER_REQUESTED)))
        f.facts.ready()
        assertEquals(0, f.context.starts); assertTrue(f.data().start.suppressUntilExplicitResume)
        assertEquals(0, f.factoryCalls)
    }
    @Test fun refusedProfileHasNoSelfRetryButDistinctIngressCanTry() = Fixture().use { f ->
        f.facts.accepted = false; f.presence()
        assertEquals(1, f.facts.requests); assertEquals(0, f.context.starts)
        f.facts.accepted = true; f.presence(); assertEquals(2, f.facts.requests)
        f.facts.ready(); assertEquals(1, f.context.starts)
    }
    @Test fun receiverUnpairWithAndWithoutTicketClearsOnlySelectedOwnPair() {
        listOf(false, true).forEach { active -> Fixture().use { f ->
            f.presence()
            if (active) { f.facts.ready(); assertTrue(f.controller.hasAdmission()) }
            f.facts.bond = BondFact.NONE; f.bondBroadcast()
            val d = f.data()
            assertNull(d.selectedPair); assertFalse(d.pairs.containsKey(f.pair))
            assertNotNull(d.pairs[f.other]?.verification)
            assertFalse(d.start.enabled); assertTrue(d.start.suppressUntilExplicitResume)
            assertEquals(SuppressReason.UNPAIRED, d.start.suppressReason)
            assertTrue(d.start.consentGeneration > 7)
            assertEquals(listOf(f.registry.selected), f.registry.removed)
            assertTrue(f.registry.associations.containsAll(listOf(f.registry.otherOwn, f.registry.foreign)))
            assertTrue(f.registry.stops > 0)
            assertNull(ReflectionHelpers.getField<Any?>(f.controller, "observation"))
            val starts = f.context.starts; f.facts.ready(); assertEquals(starts, f.context.starts)
            assertEquals(0, f.factoryCalls)
        } }
    }
    @Test fun unreadableOrTransitionalBondNeverErasesSelectionOrVerification() {
        listOf(BondFact.UNKNOWN, BondFact.BONDING).forEach { bond -> Fixture().use { f ->
            f.facts.bond = bond; f.bondBroadcast()
            assertEquals(f.pair, f.data().selectedPair)
            assertNotNull(f.data().pairs[f.pair]?.verification)
            assertTrue(f.registry.removed.isEmpty())
        } }
    }
    @Test fun permissionLossRevokesRatherThanUnpairs() = Fixture().use { f ->
        f.presence(); f.facts.permission = false; f.bondBroadcast(); f.facts.ready()
        assertEquals(SuppressReason.REVOKED, f.data().start.suppressReason)
        assertEquals(f.pair, f.data().selectedPair); assertNotNull(f.data().pairs[f.pair]?.verification)
        assertTrue(f.registry.removed.isEmpty()); assertEquals(0, f.context.starts)
    }
    @Test fun failedObservationCloseKeepsHandleAndBlocksReadiness() = Fixture().use { f ->
        f.presence(); f.registry.stopAllowed = false
        f.facts.bond = BondFact.NONE; f.bondBroadcast(); f.facts.ready()
        assertNotNull(ReflectionHelpers.getField<Any?>(f.controller, "observation"))
        assertEquals("COMPANION_CLEANUP_FAILED", f.controller.runtime.value.failure)
        assertEquals(0, f.context.starts)
        f.registry.stopAllowed = true // explicit fixture cleanup can now finish
    }
    @Test fun alreadyRemovedAssociationUnpairIsIdempotentAndOldCallbackIsInert() = Fixture().use { f ->
        f.presence(); f.registry.associations.remove(f.registry.selected)
        f.facts.bond = BondFact.NONE; f.bondBroadcast(); f.bondBroadcast(); f.facts.ready()
        assertNull(f.data().selectedPair); assertTrue(f.registry.removed.isEmpty())
        assertEquals(0, f.context.starts)
    }
}
