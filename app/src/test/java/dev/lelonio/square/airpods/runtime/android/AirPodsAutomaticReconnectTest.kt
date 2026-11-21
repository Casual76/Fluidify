// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.android

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import dev.lelonio.square.airpods.AirPodsTransportOwnership
import dev.lelonio.square.airpods.runtime.lifecycle.*
import dev.lelonio.square.airpods.runtime.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
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

/** Real receiver/controller, start gate, persistence, link lifetimes and process ownership.
 * Fake profile facts, companion association and FGS scheduling; no transport or device.
 * Prepared for the coordinator's serial test run. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
class AirPodsAutomaticReconnectTest {
    @Before fun processIdentity() { org.robolectric.shadows.ShadowProcess.setPid(41049) }

    private class SchedulingContext(base: Context) : ContextWrapper(base) {
        var starts = 0
        var backgroundPermission = true
        override fun checkSelfPermission(permission: String) =
            if (permission == Manifest.permission.REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND && !backgroundPermission)
                PackageManager.PERMISSION_DENIED else PackageManager.PERMISSION_GRANTED
        override fun startForegroundService(service: Intent): ComponentName {
            starts++
            return ComponentName(this, AirPodsControlService::class.java)
        }
    }
    private class Disk : RuntimePersistence {
        private var bytes: ByteArray? = null
        override fun read() = bytes?.copyOf()
        override fun commit(bytes: ByteArray): Boolean { this.bytes = bytes.copyOf(); return true }
    }
    private class Facts : BluetoothRuntimeFacts {
        override val adapter: BluetoothAdapter? = null
        var permission = true
        var bonded = true
        var profile = ProfileConnection.DISCONNECTED
        var profileRequests = 0
        val callbacks = mutableListOf<() -> Unit>()
        override fun permitted() = permission
        override fun bond(address: PairAddress) = if (bonded) BondFact.BONDED else BondFact.NONE
        override fun connection(address: PairAddress) = profile
        override fun prepare() = Unit
        override fun whenProfileReady(ready: () -> Unit): AutoCloseable {
            profileRequests++; callbacks.add(ready)
            return AutoCloseable { callbacks.remove(ready) }
        }
        override fun audioSettingsIntent() = Intent()
        fun ready(connection: ProfileConnection = ProfileConnection.CONNECTED) {
            profile = connection; callbacks.toList().forEach { it() }
        }
    }
    private class Fixture : AutoCloseable {
        val context = SchedulingContext(RuntimeEnvironment.getApplication())
        val pair = PairAddress("AA:BB:CC:DD:EE:49")
        val facts = Facts()
        val visible = MutableStateFlow(false)
        var associationPresent = true
        var observationStarts = 0
        var history: ExitHistory = ExitHistory.Available(emptyList())
        val registry = object : CompanionRegistryFacade {
            override fun hasFeature() = true
            override fun hasObservePermission() = true
            override fun isBonded(address: PairAddress) = facts.bonded(address)
            override fun ownAssociations() = if (associationPresent)
                listOf(OwnedAssociation(49, pair, context.packageName)) else emptyList()
            override fun start(registration: ObservationRegistration): Boolean { observationStarts++; return true }
            override fun stop(registration: ObservationRegistration) = true
            override fun disassociate(association: OwnedAssociation) = true
        }
        val controller = AirPodsAndroidController(context, visible, { true }, null,
            factsOverride = facts, registryOverride = registry,
            exitHistoryOverride = ExitHistoryProvider { history },
            runtimeDispatcher = Dispatchers.Unconfined, persistenceOverride = Disk())
        val gate: AirPodsStartGate = ReflectionHelpers.getField(controller, "gate")
        init {
            // Durable existing consent, with migration deliberately not initialized in this process.
            controller.store.select(pair).getOrThrow()
            controller.store.recordSetup(pair, PairVerification("A3063", Build.FINGERPRINT, 1)).getOrThrow()
            controller.store.configure(pair) { it.copy(host = it.host.copy(fluidifyBackgroundEnabled = true)) }.getOrThrow()
            controller.store.transaction { it.copy(pocMigrationCompleted = true,
                start = StartState(enabled = true, consentGeneration = 7)) to Unit }.getOrThrow()
        }
        fun data() = controller.store.snapshot().getOrThrow()
        /** Unconfined does not make cold migration synchronous: migrateInto uses real IO.
         * Join only the finite ingress workers created by this action, leaving the permanent
         * visibility collector and future profile callbacks untouched. This preserves the real
         * receiver/presence path and makes negative checks wait for a real admission decision. */
        private fun ingress(action: () -> Unit) {
            val process = ReflectionHelpers.getField<CoroutineScope>(controller, "processScope")
                .coroutineContext[Job]!!
            val existing = process.children.toSet()
            action()
            val workers = process.children.filter { it !in existing }.toList()
            runBlocking { withTimeout(5_000) { workers.joinAll() } }
            workers.forEach { assertFalse("ingress worker failed or was cancelled", it.isCancelled) }
        }
        fun broadcast() = ingress {
            ReflectionHelpers.getField<BroadcastReceiver>(controller, "receiver")
                .onReceive(context, Intent(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED))
        }
        fun presence() = ingress { controller.presence(pair.value, 49) }
        fun lease() = ReflectionHelpers.getField<AirPodsTransportOwnership.Lease>(controller, "transportLease")
        override fun close() {
            runBlocking { controller.stopControls() }
            context.unregisterReceiver(ReflectionHelpers.getField(controller, "receiver"))
            ReflectionHelpers.getField<CoroutineScope>(controller, "processScope").cancel()
        }
    }

    @Test fun appearanceBeforeAudioConnectionDoesNotConsumeTheBackgroundReconnect() = Fixture().use { f ->
        f.presence() // Companion/BLE appearance arrives while the audio profile is disconnected.
        assertEquals(0, f.context.starts)
        f.facts.profile = ProfileConnection.CONNECTED
        f.broadcast(); f.broadcast(); f.presence()
        assertEquals(1, f.context.starts)
        assertTrue(f.controller.hasAdmission())
        assertEquals(7L, f.data().start.consentGeneration)
        assertFalse(f.data().start.suppressUntilExplicitResume)
    }

    @Test fun coldReceiverRestoresAnExistingOptedInAssociation() = Fixture().use { f ->
        f.facts.profile = ProfileConnection.CONNECTED
        assertFalse(ReflectionHelpers.getField<Boolean>(f.controller, "migrationReady"))
        assertNull(ReflectionHelpers.getField<Any?>(f.controller, "observation"))
        f.broadcast()
        assertTrue(ReflectionHelpers.getField<Boolean>(f.controller, "migrationReady"))
        assertEquals(1, f.observationStarts)
        assertEquals(1, f.context.starts)
        assertTrue(f.controller.hasAdmission())
        assertEquals(7L, f.data().start.consentGeneration)
    }

    @Test fun receiverAwaitsTheProfileProxyOnceWithoutPollingOrAnotherEvent() = Fixture().use { f ->
        f.facts.profile = ProfileConnection.UNRESOLVED
        f.broadcast(); f.broadcast()
        assertEquals(1, f.facts.profileRequests)
        assertEquals(1, f.facts.callbacks.size)
        assertEquals(0, f.context.starts)
        f.facts.ready()
        assertEquals(1, f.context.starts)
        assertTrue(f.controller.hasAdmission())
        assertTrue(f.facts.callbacks.isEmpty())
        f.broadcast()
        assertEquals(1, f.context.starts)
    }

    @Test fun ordinaryDisconnectAndReconnectRetainConsentAndAcquireANewLink() = Fixture().use { f ->
        f.facts.profile = ProfileConnection.CONNECTED; f.broadcast()
        assertTrue("first real admission must complete before disconnect", f.controller.hasAdmission())
        assertEquals(1, f.context.starts)
        val firstLease = f.lease()
        val firstAdmission = ReflectionHelpers.getField<ConnectionAdmission>(f.controller, "ticket")
        assertNotNull(firstAdmission.connectionOrdinal)
        f.facts.profile = ProfileConnection.DISCONNECTED; f.broadcast()
        assertFalse(f.controller.hasAdmission()); assertTrue(firstLease.released)
        assertTrue(f.data().start.marker!!.clean)
        f.facts.profile = ProfileConnection.CONNECTED; f.broadcast()
        assertEquals(2, f.context.starts)
        assertNotSame(firstLease, f.lease())
        assertTrue(ReflectionHelpers.getField<ConnectionAdmission>(f.controller, "ticket").connectionOrdinal!! > firstAdmission.connectionOrdinal!!)
        assertEquals(7L, f.data().start.consentGeneration)
        assertFalse(f.data().start.suppressUntilExplicitResume)
    }

    @Test fun backgroundReconnectRequiresBothTheUserToggleAndCurrentAssociation() {
        listOf<(Fixture) -> Unit>(
            { f -> f.controller.store.configure(f.pair) { it.copy(host = it.host.copy(fluidifyBackgroundEnabled = false)) }.getOrThrow() },
            { f -> f.associationPresent = false },
            { f -> f.context.backgroundPermission = false },
            { f -> f.facts.permission = false },
        ).forEach { revoke -> Fixture().use { f ->
            revoke(f); f.facts.profile = ProfileConnection.CONNECTED; f.broadcast()
            assertEquals(0, f.context.starts)
            assertFalse(f.controller.hasAdmission())
        } }
    }

    @Test fun lateProfileCallbackAndReceiverCannotUndoExplicitStop() = Fixture().use { f ->
        f.facts.profile = ProfileConnection.UNRESOLVED; f.broadcast()
        assertEquals(1, f.facts.profileRequests)
        val captured = f.facts.callbacks.single()
        runBlocking { f.controller.stopControls() }
        f.facts.profile = ProfileConnection.CONNECTED; captured(); f.broadcast(); f.presence()
        assertEquals(0, f.context.starts)
        assertTrue(f.data().start.suppressUntilExplicitResume)
        assertEquals(SuppressReason.USER_STOP, f.data().start.suppressReason)
    }

    @Test fun pendingProfileRechecksAssociationRatherThanTrustingItsOriginalCallback() = Fixture().use { f ->
        f.facts.profile = ProfileConnection.UNRESOLVED; f.broadcast()
        assertEquals("a real pending callback is required before revocation", 1, f.facts.callbacks.size)
        f.associationPresent = false; f.facts.ready()
        assertEquals(0, f.context.starts)
        assertFalse(f.controller.hasAdmission())
    }

    @Test fun reconnectWaitsForThePreviousServiceDestructionThenUsesTheSameEvent() = Fixture().use { f ->
        f.facts.profile = ProfileConnection.CONNECTED; f.broadcast()
        assertTrue("admit before synthesizing service attachment", f.controller.hasAdmission())
        val oldService = Robolectric.buildService(AirPodsControlService::class.java).create()
        // Only service attachment is synthetic: a real attach would construct Bluetooth transport.
        ReflectionHelpers.setField(f.controller, "service", oldService.get())
        ReflectionHelpers.setField(oldService.get(), "attachedOwner", f.controller)
        ReflectionHelpers.setField(oldService.get(), "attachedStopAction", f.controller.serviceStopAction)
        f.facts.profile = ProfileConnection.DISCONNECTED; f.broadcast()
        assertTrue(f.gate.cleanupUnconfirmed)
        f.facts.profile = ProfileConnection.CONNECTED; f.broadcast()
        assertEquals(1, f.context.starts)
        assertFalse(f.controller.hasAdmission())
        shadowOf(Looper.getMainLooper()).idle() // old stopSelf runs before actual destruction
        oldService.destroy()
        assertFalse(f.gate.cleanupUnconfirmed)
        assertEquals(2, f.context.starts)
        assertTrue(f.controller.hasAdmission())
        assertFalse(f.data().start.suppressUntilExplicitResume) // unexpected destruction is an ordinary disconnect, not a user stop
    }

    @Test fun unexpectedServiceDestructionDoesNotWaitForASecondDestruction() = Fixture().use { f ->
        f.facts.profile = ProfileConnection.CONNECTED; f.broadcast()
        assertTrue("admit before synthesizing service attachment", f.controller.hasAdmission())
        val service = Robolectric.buildService(AirPodsControlService::class.java).create()
        ReflectionHelpers.setField(f.controller, "service", service.get())
        ReflectionHelpers.setField(service.get(), "attachedOwner", f.controller)
        ReflectionHelpers.setField(service.get(), "attachedStopAction", f.controller.serviceStopAction)
        service.destroy()
        assertFalse(f.controller.hasAdmission())
        assertFalse(f.gate.cleanupUnconfirmed)
        assertFalse(f.data().start.suppressUntilExplicitResume) // unexpected destruction is an ordinary disconnect, not a user stop
        assertNull(AirPodsServiceOwnerRegistry.resolve())
    }
}
