// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.android

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import dev.lelonio.square.airpods.*
import dev.lelonio.square.airpods.runtime.AirPodsForegroundState
import dev.lelonio.square.airpods.runtime.lifecycle.*
import dev.lelonio.square.airpods.runtime.storage.*
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** PREPARED, NOT COMPILED OR EXECUTED. Actual controller, gate, repository, persistence and
 * process lease. Synthetic profile/history/FGS/transport. Service attachment is synthetic only
 * in the retirement test; no JNI, device, hardware, UI rendering or performance assertions. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
class AirPodsI01SelectionRecoveryTest {
    @Before fun processIdentity() { org.robolectric.shadows.ShadowProcess.setPid(41051) }

    private class SchedulingContext(base: Context) : ContextWrapper(base) {
        val starts = AtomicInteger()
        var rejectStart = false
        override fun checkSelfPermission(permission: String) = PackageManager.PERMISSION_GRANTED
        override fun startForegroundService(service: Intent): ComponentName {
            starts.incrementAndGet()
            if (rejectStart) throw IllegalStateException("synthetic scheduling failure")
            return ComponentName(this, AirPodsControlService::class.java)
        }
    }
    private class Disk : RuntimePersistence {
        var bytes: ByteArray? = null
        val commits = AtomicInteger()
        var failCommit = false
        override fun read() = bytes?.copyOf()
        override fun commit(bytes: ByteArray): Boolean {
            if (failCommit) return false
            commits.incrementAndGet(); this.bytes = bytes.copyOf(); return true
        }
    }
    private class Facts : BluetoothRuntimeFacts {
        override val adapter: BluetoothAdapter? = null
        var permission = true
        var profile = ProfileConnection.CONNECTED
        val requested = CompletableDeferred<Unit>()
        var callback: (() -> Unit)? = null
        override fun permitted() = permission
        override fun bond(address: PairAddress) = BondFact.BONDED
        override fun connection(address: PairAddress) = profile
        override fun prepare() = Unit
        override fun audioSettingsIntent() = Intent()
        override fun whenProfileReady(ready: () -> Unit): AutoCloseable {
            callback = ready; requested.complete(Unit)
            return AutoCloseable { if (callback === ready) callback = null }
        }
        fun ready() { profile = ProfileConnection.CONNECTED; callback?.invoke() }
    }
    private class Fixture : AutoCloseable {
        val context = SchedulingContext(RuntimeEnvironment.getApplication())
        val visible = AirPodsForegroundState().also { it.value = true }
        val facts = Facts()
        val disk = Disk()
        val pair = PairAddress("AA:BB:CC:DD:EE:51")
        val observationStops = AtomicInteger()
        val registry = object : CompanionRegistryFacade {
            override fun hasFeature() = true
            override fun hasObservePermission() = true
            override fun isBonded(address: PairAddress) = true
            override fun ownAssociations() = listOf(OwnedAssociation(51, pair, context.packageName))
            override fun start(registration: ObservationRegistration) = true
            override fun stop(registration: ObservationRegistration): Boolean { observationStops.incrementAndGet(); return true }
            override fun disassociate(association: OwnedAssociation) = true
        }
        val controller = AirPodsAndroidController(context, visible, { true }, null,
            factsOverride = facts, registryOverride = registry,
            exitHistoryOverride = ExitHistoryProvider { ExitHistory.Available(emptyList()) },
            runtimeDispatcher = Dispatchers.Unconfined, persistenceOverride = disk)
        val repository = ReflectionHelpers.getField<AirPodsRepository>(controller, "repository")
        val gate = ReflectionHelpers.getField<AirPodsStartGate>(controller, "gate")
        private val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        init {
            // Only bypass completed migration; ticket, consent and lease come from real admission.
            ReflectionHelpers.setField(controller, "migrationReady", true)
            controller.store.select(pair).getOrThrow()
            controller.store.recordSetup(pair, PairVerification("A3063", Build.FINGERPRINT, 1)).getOrThrow()
            controller.store.configure(pair) { it.copy(host = it.host.copy(fluidifyBackgroundEnabled = true)) }.getOrThrow()
            controller.store.transaction {
                it.copy(pocMigrationCompleted = true, start = StartState(enabled = true, consentGeneration = 7)) to Unit
            }.getOrThrow()
            collectorScope.launch(start = CoroutineStart.UNDISPATCHED) {
                // Same decision invoked by the attached production collector, including ERROR.
                controller.state.collect { controller.advanceBootstrap(it.stage) }
            }
        }
        fun data() = controller.store.snapshot().getOrThrow()
        fun admission() = ReflectionHelpers.getField<ConnectionAdmission>(controller, "ticket")
        fun lease() = ReflectionHelpers.getField<AirPodsTransportOwnership.Lease>(controller, "transportLease")
        suspend fun start() {
            controller.refresh(StartIngress.RECEIVER)
            assertTrue(controller.hasAdmission()); assertEquals(1, context.starts.get())
        }
        fun nextOpening() { visible.value = false; visible.value = true }
        fun fail() = controller.controlChannelFailed(admission())
        override fun close() {
            collectorScope.cancel(); repository.close(); disk.failCommit = false
            runBlocking { controller.stopControls() }
            context.unregisterReceiver(ReflectionHelpers.getField(controller, "receiver"))
            ReflectionHelpers.getField<CoroutineScope>(controller, "processScope").cancel()
        }
    }

    @Test fun selectingCurrentPairPreservesLiveOwnerLeaseObservationAndConsent() = runBlocking {
        Fixture().use { f ->
            f.start(); val ticket = f.admission(); val lease = f.lease(); val before = f.data()
            val commits = f.disk.commits.get(); val stops = f.observationStops.get()
            repeat(3) { assertTrue(f.controller.selectPair(PairAddress(f.pair.value.lowercase())).isSuccess) }
            assertSame(ticket, f.admission()); assertSame(lease, f.lease()); assertFalse(lease.released)
            assertEquals(before, f.data()); assertEquals(commits, f.disk.commits.get())
            assertEquals(stops, f.observationStops.get()); assertEquals(1, f.context.starts.get())
            assertTrue(f.controller.hasAdmission())
        }
    }

    @Test fun reselectingCurrentPairDoesNotCancelAnOpeningAwaitingItsProfile() = runBlocking {
        Fixture().use { f ->
            f.facts.profile = ProfileConnection.UNRESOLVED
            val opening = async { f.controller.appOpened() }
            withTimeout(2_000) { f.facts.requested.await() }
            val callback = f.facts.callback; assertNotNull(callback)
            assertTrue(f.controller.selectPair(f.pair).isSuccess)
            assertSame(callback, f.facts.callback)
            f.facts.ready(); withTimeout(2_000) { opening.await() }
            assertTrue(f.controller.hasAdmission()); assertEquals(1, f.context.starts.get())
            assertEquals(7L, f.data().start.consentGeneration)
            assertFalse(f.data().start.suppressUntilExplicitResume)
        }
    }

    @Test fun reselectingStoppedPairDoesNotGrantConsentAndDifferentSelectionStillStopsOwner() = runBlocking {
        Fixture().use { f ->
            f.start(); val lease = f.lease()
            val other = PairAddress("AA:BB:CC:DD:EE:52")
            assertTrue(f.controller.selectPair(other).isSuccess)
            assertTrue(lease.released); assertFalse(f.controller.hasAdmission())
            assertEquals(other, f.data().selectedPair)
            val stopped = f.data().start
            assertEquals(SuppressReason.USER_STOP, stopped.suppressReason)
            assertTrue(f.controller.selectPair(other).isSuccess)
            assertEquals(stopped, f.data().start); assertEquals(1, f.context.starts.get())
        }
    }

    private class FailingTransport(private val failConnect: Boolean) : AacpTransport {
        val connects = AtomicInteger()
        val reads = AtomicInteger()
        val closes = AtomicInteger()
        val closed = CompletableDeferred<Unit>()
        override fun connect() {
            connects.incrementAndGet()
            if (failConnect) throw IOException("synthetic connect failure")
        }
        override fun readSdu(): ByteArray? { reads.incrementAndGet(); throw IOException("synthetic read failure") }
        override fun write(packet: ByteArray) = Unit
        override fun close() { closes.incrementAndGet(); closed.complete(Unit) }
    }

    @Test fun realRepositoryConnectAndReadFailuresReachControllerRecoveryWithAudioStillConnected() = runBlocking {
        listOf(true, false).forEach { connectFailure -> Fixture().use { f ->
            f.start(); val oldLease = f.lease(); val consent = f.data().start.consentGeneration
            val transport = FailingTransport(connectFailure)
            f.repository.construct { transport }
            withTimeout(3_000) { f.controller.runtime.first { it.controlRecovery == ControlChannelRecovery.WAITING } }
            withTimeout(3_000) { transport.closed.await() }
            assertEquals(1, transport.connects.get())
            assertEquals(if (connectFailure) 0 else 1, transport.reads.get())
            assertTrue(transport.closes.get() > 0)
            assertTrue(oldLease.released); assertFalse(f.controller.hasAdmission())
            assertEquals(ProfileConnection.CONNECTED, f.facts.profile)
            assertEquals("TRANSPORT_FAILED", f.controller.runtime.value.failure)
            assertFalse(f.controller.runtime.value.active)
            assertFalse(f.data().start.suppressUntilExplicitResume); assertNull(f.data().start.suppressReason)
            assertTrue(f.data().start.marker!!.clean); assertEquals(consent, f.data().start.consentGeneration)
            repeat(3) { f.controller.refresh(StartIngress.RECEIVER); f.controller.permissionsChanged() }
            f.controller.appOpened() // same foreground epoch as the failure, not another opening
            assertEquals(1, f.context.starts.get())
            f.nextOpening(); f.controller.appOpened()
            assertEquals(2, f.context.starts.get()); assertTrue(f.controller.hasAdmission())
            assertEquals(ControlChannelRecovery.RECONNECTING, f.controller.runtime.value.controlRecovery)
            assertEquals("TRANSPORT_FAILED", f.controller.runtime.value.failure)
        } }
    }

    @Test fun newAudioLinkRecoversAndOldFailureCannotAffectTheReplacementOwner() = runBlocking {
        Fixture().use { f ->
            f.start(); val old = f.admission(); val oldLease = f.lease(); f.fail()
            assertTrue(oldLease.released)
            assertTrue(f.controller.selectPair(f.pair).isSuccess)
            assertEquals(ControlChannelRecovery.WAITING, f.controller.runtime.value.controlRecovery)
            f.facts.profile = ProfileConnection.DISCONNECTED; f.controller.refresh(StartIngress.RECEIVER)
            f.facts.profile = ProfileConnection.CONNECTED; f.controller.refresh(StartIngress.RECEIVER)
            val replacement = f.admission(); assertNotSame(old, replacement)
            assertTrue(replacement.connectionOrdinal!! > old.connectionOrdinal!!)
            assertEquals(2, f.context.starts.get()); assertTrue(f.controller.hasAdmission())
            val runtime = f.controller.runtime.value
            f.controller.controlChannelFailed(old)
            assertSame(replacement, f.admission()); assertEquals(runtime, f.controller.runtime.value)
            assertEquals(7L, f.data().start.consentGeneration)
        }
    }

    @Test fun repeatedFailureNeedsAnotherOpeningAndExplicitStopWinsOverLateFaults() = runBlocking {
        Fixture().use { f ->
            f.start(); f.fail(); f.nextOpening(); f.controller.appOpened()
            val recovered = f.admission(); f.fail()
            repeat(3) { f.controller.appOpened(); f.controller.refresh(StartIngress.RECEIVER) }
            assertEquals(2, f.context.starts.get())
            f.controller.stopControls()
            val stopped = f.controller.runtime.value
            f.controller.controlChannelFailed(recovered)
            f.nextOpening(); f.controller.appOpened()
            f.facts.profile = ProfileConnection.DISCONNECTED; f.controller.refresh(StartIngress.RECEIVER)
            f.facts.profile = ProfileConnection.CONNECTED; f.controller.refresh(StartIngress.RECEIVER)
            assertEquals(stopped, f.controller.runtime.value)
            assertEquals(ControlChannelRecovery.NONE, f.controller.runtime.value.controlRecovery)
            assertEquals(SuppressReason.USER_STOP, f.data().start.suppressReason)
            assertEquals(2, f.context.starts.get()); assertFalse(f.controller.hasAdmission())
        }
    }

    @Test fun interruptedRecoveryKeepsItsDiagnosisAndReturnsToWaitingUntilAnotherAdmission() = runBlocking {
        Fixture().use { f ->
            f.start(); f.fail(); f.nextOpening(); f.controller.appOpened()
            assertEquals(ControlChannelRecovery.RECONNECTING, f.controller.runtime.value.controlRecovery)
            f.facts.profile = ProfileConnection.DISCONNECTED; f.controller.refresh(StartIngress.RECEIVER)
            assertFalse(f.controller.hasAdmission())
            assertEquals("TRANSPORT_FAILED", f.controller.runtime.value.failure)
            assertEquals(ControlChannelRecovery.WAITING, f.controller.runtime.value.controlRecovery)
            f.facts.profile = ProfileConnection.CONNECTED; f.controller.refresh(StartIngress.RECEIVER)
            assertTrue(f.controller.hasAdmission()); assertEquals(3, f.context.starts.get())
            assertEquals(ControlChannelRecovery.RECONNECTING, f.controller.runtime.value.controlRecovery)
            assertFalse(f.data().start.suppressUntilExplicitResume)
        }
    }

    @Test fun schedulingFailureRetainsAnObservableReasonAndRecoversOnANewOpening() = runBlocking {
        Fixture().use { f ->
            f.context.rejectStart = true; f.controller.refresh(StartIngress.RECEIVER)
            assertEquals(1, f.context.starts.get()); assertFalse(f.controller.hasAdmission())
            assertEquals("CONTROL_START_FAILED", f.controller.runtime.value.failure)
            assertEquals(ControlChannelRecovery.WAITING, f.controller.runtime.value.controlRecovery)
            assertFalse(f.data().start.suppressUntilExplicitResume)
            f.context.rejectStart = false; f.nextOpening(); f.controller.appOpened()
            assertEquals(2, f.context.starts.get()); assertTrue(f.controller.hasAdmission())
        }
    }

    @Test fun failedMarkerConclusionPublishesPersistenceFailureInsteadOfPromisingAutomaticRecovery() = runBlocking {
        Fixture().use { f ->
            f.start(); f.disk.failCommit = true; f.fail()
            assertFalse(f.controller.hasAdmission())
            assertEquals("PERSISTENCE_FAILED", f.controller.runtime.value.failure)
            assertEquals(ControlChannelRecovery.NONE, f.controller.runtime.value.controlRecovery)
            assertFalse(f.data().start.marker!!.clean)
            f.disk.failCommit = false; f.nextOpening(); f.controller.appOpened()
            assertEquals(1, f.context.starts.get()); assertFalse(f.controller.hasAdmission())
        }
    }

    @Test fun channelRecoveryRetainsThePreviousServiceRetirementFence() = runBlocking {
        Fixture().use { f ->
            f.start()
            val oldService = Robolectric.buildService(AirPodsControlService::class.java).create()
            ReflectionHelpers.setField(f.controller, "service", oldService.get())
            ReflectionHelpers.setField(oldService.get(), "attachedOwner", f.controller)
            ReflectionHelpers.setField(oldService.get(), "attachedStopAction", f.controller.serviceStopAction)
            try {
                f.fail(); assertTrue(f.gate.cleanupUnconfirmed)
                f.facts.profile = ProfileConnection.DISCONNECTED; f.controller.refresh(StartIngress.RECEIVER)
                f.facts.profile = ProfileConnection.CONNECTED; f.controller.refresh(StartIngress.RECEIVER)
                assertEquals(1, f.context.starts.get()); assertFalse(f.controller.hasAdmission())
            } finally {
                shadowOf(Looper.getMainLooper()).idle(); oldService.destroy()
            }
            assertFalse(f.gate.cleanupUnconfirmed)
            assertEquals(2, f.context.starts.get()); assertTrue(f.controller.hasAdmission())
            assertFalse(f.data().start.suppressUntilExplicitResume)
            assertEquals(7L, f.data().start.consentGeneration)
        }
    }
}
