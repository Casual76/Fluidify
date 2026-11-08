// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.android

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import dev.lelonio.square.airpods.*
import dev.lelonio.square.airpods.protocol.HeadPacketFormat
import dev.lelonio.square.airpods.runtime.lifecycle.*
import dev.lelonio.square.airpods.runtime.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.LinkedBlockingQueue

/** PREPARED, NOT EXECUTED. Real controller, gate, store and repository; synthetic platform facts.
 * Admission is acquired through real ingress, never a reflected ticket or production bypass.
 * No service attachment, Bluetooth socket construction, Android device or hardware receipt. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
class I4RuntimeAuthorizationTest {
    @Before fun processIdentity() { org.robolectric.shadows.ShadowProcess.setPid(41004) }

    private class CountingContext(base: Context) : ContextWrapper(base) {
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
        override fun permitted() = permission
        override fun bond(address: PairAddress) = BondFact.BONDED
        override fun connection(address: PairAddress) = ProfileConnection.CONNECTED
        override fun prepare() = Unit
        override fun audioSettingsIntent() = Intent()
        override fun whenProfileReady(ready: () -> Unit): AutoCloseable? = null
    }
    private class Persistence : RuntimePersistence {
        private var bytes: ByteArray? = null
        var markerCommits = 0
        override fun read() = bytes
        override fun commit(bytes: ByteArray): Boolean {
            if (AirPodsRuntimeCodec.decode(bytes).start.marker != null) markerCommits++
            this.bytes = bytes
            return true
        }
    }
    private class Transport : AacpTransport {
        private val queue = LinkedBlockingQueue<ByteArray>()
        val writes = java.util.Collections.synchronizedList(mutableListOf<List<Int>>())
        override fun connect() {}
        override fun readSdu(): ByteArray? = queue.take().takeIf { it.isNotEmpty() }
        override fun write(packet: ByteArray) { writes.add(packet.map { it.toInt() and 255 }) }
        override fun close() { queue.offer(byteArrayOf()) }
    }
    private class Fixture : AutoCloseable {
        val context = CountingContext(RuntimeEnvironment.getApplication())
        val visible = MutableStateFlow(true)
        val pair = PairAddress("AA:BB:CC:DD:EE:04")
        val facts = Facts()
        val persistence = Persistence()
        var mediaFactoryCalls = 0
        var syntheticTransportFactories = 0
        val registry = object : CompanionRegistryFacade {
            override fun hasFeature() = true
            override fun hasObservePermission() = true
            override fun isBonded(address: PairAddress) = true
            override fun ownAssociations() = listOf(OwnedAssociation(4, pair, context.packageName))
            override fun start(registration: ObservationRegistration) = true
            override fun stop(registration: ObservationRegistration) = true
            override fun disassociate(association: OwnedAssociation) = true
        }
        val controller = AirPodsAndroidController(context, visible, { true },
            { mediaFactoryCalls++; error("Service must never attach") },
            factsOverride = facts, registryOverride = registry,
            exitHistoryOverride = ExitHistoryProvider { ExitHistory.Available(emptyList()) },
            runtimeDispatcher = Dispatchers.Unconfined, persistenceOverride = persistence)
        val repository = ReflectionHelpers.getField<AirPodsRepository>(controller, "repository")
        init {
            // Existing test-only reflection marks completed migration, not entitlement/admission.
            ReflectionHelpers.setField(controller, "migrationReady", true)
            controller.store.select(pair).getOrThrow()
            controller.store.recordSetup(pair, PairVerification("A3063", Build.FINGERPRINT, 1)).getOrThrow()
            controller.store.transaction {
                it.copy(pocMigrationCompleted = true, start = StartState(enabled = true, consentGeneration = 7)) to Unit
            }.getOrThrow()
            preferences(HostSettings(headGestures = true, automationsEnabled = true))
        }
        fun data() = controller.store.snapshot().getOrThrow()
        fun preferences(host: HostSettings) { controller.store.configure(pair) { it.copy(host = host) }.getOrThrow() }
        suspend fun admit() {
            controller.refresh(StartIngress.RECEIVER)
            assertTrue(controller.hasAdmission())
            assertEquals(1, context.starts)
        }
        suspend fun ready(): Transport {
            val transport = Transport()
            suspend fun await(stage: AacpStage) = withTimeout(2_000) { controller.state.first { it.stage == stage } }
            repository.construct { syntheticTransportFactories++; transport }; await(AacpStage.CONSTRUCTED)
            repository.connect(); await(AacpStage.CONNECTED)
            repository.handshake(); await(AacpStage.HANDSHAKE_SENT)
            repository.subscribe(); await(AacpStage.SUBSCRIBED)
            repository.receive(controller.state.value.generation,
                AacpEvent.Information(AirPodsIdentity("A3063", "Apple Inc.", "fixture")))
            return transport
        }
        fun assertNoAcquisition() {
            assertTrue(controller.runtime.value.productConformance)
            assertFalse(controller.hasAdmission())
            assertNull(ReflectionHelpers.getField<Any?>(controller, "ticket"))
            assertNull(ReflectionHelpers.getField<Any?>(controller, "transportLease"))
            assertNull(ReflectionHelpers.getField<Any?>(AirPodsTransportOwnership.process, "active"))
            assertEquals(0, context.starts)
            assertEquals(0, mediaFactoryCalls)
            assertEquals(0, syntheticTransportFactories)
            assertEquals(AacpStage.CLOSED, controller.state.value.stage)
            assertNull(data().start.marker)
            assertEquals(0, persistence.markerCommits)
        }
        override fun close() {
            runBlocking { controller.stopControls() }
            context.unregisterReceiver(ReflectionHelpers.getField(controller, "receiver"))
            ReflectionHelpers.getField<CoroutineScope>(controller, "processScope").cancel()
        }
    }

    @Test fun repeatedPermissionSettingsAndExportReturnsArePassiveWithPositiveEntitlement() = runBlocking {
        Fixture().use { f ->
            val before = f.data()
            // All three host callbacks converge on this controller entry point.
            repeat(6) { f.controller.permissionsChanged(); f.assertNoAcquisition() }
            assertEquals(before, f.data())
            assertEquals(TrackingBlockReason.NO_ADMITTED_SESSION, f.controller.trackingUnavailableReason(true))
            assertFalse(f.controller.setTracking(true))
            // Same facts, legitimate receiver ingress still admits exactly once.
            f.admit(); f.controller.refresh(StartIngress.RECEIVER)
            assertEquals(1, f.context.starts)
            assertEquals(1, f.persistence.markerCommits)
            assertNotNull(f.data().start.marker)
        }
    }
    @Test fun passiveRefreshDoesNotConsumeExplicitOrCompanionAdmission() = runBlocking {
        listOf(false, true).forEach { companion -> Fixture().use { f ->
            f.controller.permissionsChanged(); f.assertNoAcquisition()
            if (companion) f.controller.presence(f.pair.value, 4)
            else assertTrue(f.controller.connect() is GateResult.Started)
            assertTrue(f.controller.hasAdmission()); assertEquals(1, f.context.starts)
            f.controller.permissionsChanged(); f.controller.permissionsChanged()
            assertEquals(1, f.context.starts)
        } }
    }
    @Test fun permissionLossClosesActiveOwnerAndReturnNeverResumesSuppressedSession() = runBlocking {
        Fixture().use { f ->
            f.admit()
            val lease = ReflectionHelpers.getField<AirPodsTransportOwnership.Lease>(f.controller, "transportLease")
            f.facts.permission = false; f.controller.permissionsChanged()
            assertFalse(f.controller.hasAdmission()); assertTrue(lease.released)
            assertNull(ReflectionHelpers.getField<Any?>(f.controller, "ticket"))
            assertEquals(SuppressReason.REVOKED, f.data().start.suppressReason)
            assertTrue(f.data().start.suppressUntilExplicitResume)
            f.facts.permission = true
            repeat(3) { f.controller.permissionsChanged() }
            assertEquals(1, f.context.starts); assertFalse(f.controller.hasAdmission())
            assertTrue(f.data().start.suppressUntilExplicitResume)
        }
    }
    @Test fun trackingSelectorAndDispatchSharePreferencesAndForegroundAuthorization() = runBlocking {
        Fixture().use { f ->
            f.admit(); val transport = f.ready()
            val enabled = HostSettings(headGestures = true, automationsEnabled = true)
            suspend fun blocked(host: HostSettings, reason: TrackingBlockReason) {
                f.preferences(host)
                val before = transport.writes.size
                assertEquals(reason, f.controller.trackingUnavailableReason(true))
                assertFalse(f.controller.setTracking(true))
                assertEquals(before, transport.writes.size)
                assertNull(f.controller.trackingUnavailableReason(false))
            }
            blocked(enabled.copy(automationsEnabled = false), TrackingBlockReason.AUTOMATIONS_DISABLED)
            blocked(enabled.copy(headGestures = false), TrackingBlockReason.HEAD_GESTURES_DISABLED)
            f.preferences(enabled)
            assertNull(f.controller.trackingUnavailableReason(true)) // foreground, no BG opt-in
            assertTrue(f.controller.setTracking(true))
            withTimeout(2_000) { f.controller.state.first { it.activePacketFormat != null } }
            f.visible.value = false
            withTimeout(2_000) { f.controller.state.first { it.activePacketFormat == null } }
            blocked(enabled, TrackingBlockReason.BACKGROUND_DISABLED)
            f.preferences(enabled.copy(fluidifyBackgroundEnabled = true))
            assertNull(f.controller.trackingUnavailableReason(true))
            assertTrue(f.controller.setTracking(true))
            withTimeout(2_000) { f.controller.state.first { it.activePacketFormat != null } }
            // Stop uses the active format despite both preferences OFF and background missing.
            f.preferences(HostSettings())
            assertNull(f.controller.trackingUnavailableReason(false))
            assertTrue(f.controller.setTracking(false))
            withTimeout(2_000) { f.controller.state.first { it.activePacketFormat == null } }
            assertEquals(AacpCodec.headTracking(HeadPacketFormat.ALTERNATE, false).map { it.toInt() and 255 },
                transport.writes.last())
        }
    }
    @Test fun configureOffRevokesActiveTrackingWithoutNewStartAndOwnerRevocationBlocksStop() = runBlocking {
        Fixture().use { f ->
            f.admit(); f.ready()
            assertTrue(f.controller.setTracking(true))
            withTimeout(2_000) { f.controller.state.first { it.activePacketFormat != null } }
            f.controller.configure { it.copy(host = it.host.copy(automationsEnabled = false)) }.getOrThrow()
            assertEquals(TrackingBlockReason.AUTOMATIONS_DISABLED, f.controller.trackingUnavailableReason(true))
            assertFalse(f.controller.setTracking(true))
            withTimeout(2_000) { f.controller.state.first { it.activePacketFormat == null } }
            assertNull(f.controller.trackingUnavailableReason(false))
            f.facts.permission = false; f.controller.permissionsChanged()
            assertEquals(TrackingBlockReason.NO_ADMITTED_SESSION, f.controller.trackingUnavailableReason(false))
            assertFalse(f.controller.setTracking(false))
            assertEquals(1, f.context.starts)
        }
    }
}
