// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.android

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import dev.lelonio.square.airpods.*
import dev.lelonio.square.airpods.runtime.lifecycle.*
import dev.lelonio.square.airpods.runtime.storage.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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

/** PREPARED, NOT EXECUTED. Actual controller bootstrap decisions, repository, gate and store.
 * FGS is counted but not attached; only its bootstrap collector is driven here, using the
 * production advanceBootstrap method. Transport/platform facts are synthetic, no JNI/radio. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
class AirPodsControllerBootstrapTest {
    @Before fun processIdentity() { org.robolectric.shadows.ShadowProcess.setPid(41008) }

    private class SchedulingContext(base: Context) : ContextWrapper(base) {
        var starts = 0
        override fun checkSelfPermission(permission: String) = PackageManager.PERMISSION_GRANTED
        override fun startForegroundService(service: Intent): ComponentName {
            starts++; return ComponentName(this, AirPodsControlService::class.java)
        }
    }
    private class Facts : BluetoothRuntimeFacts {
        override val adapter: BluetoothAdapter? = null
        @Volatile var permission = true
        override fun permitted() = permission
        override fun bond(address: PairAddress) = BondFact.BONDED
        override fun connection(address: PairAddress) = ProfileConnection.CONNECTED
        override fun prepare() = Unit
        override fun audioSettingsIntent() = Intent()
        override fun whenProfileReady(ready: () -> Unit): AutoCloseable? = null
    }
    private class Disk : RuntimePersistence {
        private var bytes: ByteArray? = null
        override fun read() = bytes
        override fun commit(bytes: ByteArray): Boolean { this.bytes = bytes.copyOf(); return true }
    }
    private class Transport(private val connectGate: CountDownLatch? = null) : AacpTransport {
        private val rx = LinkedBlockingQueue<ByteArray>()
        val connectStarted = CountDownLatch(1)
        val connects = AtomicInteger()
        val writes = java.util.Collections.synchronizedList(mutableListOf<List<Int>>())
        override fun connect() {
            connects.incrementAndGet(); connectStarted.countDown()
            if (connectGate != null) check(connectGate.await(2, TimeUnit.SECONDS))
        }
        override fun readSdu(): ByteArray? = rx.take().takeIf { it.isNotEmpty() }
        override fun write(packet: ByteArray) { writes.add(packet.map { it.toInt() and 255 }) }
        override fun close() { connectGate?.countDown(); rx.offer(byteArrayOf()) }
    }
    private class Fixture(entitled: Boolean) : AutoCloseable {
        val context = SchedulingContext(RuntimeEnvironment.getApplication())
        val facts = Facts()
        val pair = PairAddress("AA:BB:CC:DD:EE:08")
        private val registry = object : CompanionRegistryFacade {
            override fun hasFeature() = true
            override fun hasObservePermission() = true
            override fun isBonded(address: PairAddress) = true
            override fun ownAssociations() = listOf(OwnedAssociation(8, pair, context.packageName))
            override fun start(registration: ObservationRegistration) = true
            override fun stop(registration: ObservationRegistration) = true
            override fun disassociate(association: OwnedAssociation) = true
        }
        val controller = AirPodsAndroidController(context, MutableStateFlow(true), { entitled }, null,
            factsOverride = facts, registryOverride = registry,
            exitHistoryOverride = ExitHistoryProvider { ExitHistory.Available(emptyList()) },
            runtimeDispatcher = Dispatchers.Unconfined, persistenceOverride = Disk())
        val repository = ReflectionHelpers.getField<AirPodsRepository>(controller, "repository")
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        init {
            ReflectionHelpers.setField(controller, "migrationReady", true)
            controller.store.select(pair).getOrThrow()
            controller.store.recordSetup(pair, PairVerification("A3063", Build.FINGERPRINT, 1)).getOrThrow()
            controller.store.transaction {
                it.copy(pocMigrationCompleted = true, start = StartState(enabled = true, consentGeneration = 7)) to Unit
            }.getOrThrow()
            // Calls production controller decisions, rather than reimplementing its stage switch.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                controller.state.collect { controller.advanceBootstrap(it.stage) }
            }
        }
        suspend fun admit() { controller.refresh(StartIngress.RECEIVER) }
        suspend fun construct(transport: Transport) { repository.construct { transport } }
        suspend fun await(stage: AacpStage) = withTimeout(2_000) { controller.state.first { it.stage == stage } }
        override fun close() {
            scope.cancel(); repository.close()
            runBlocking { controller.stopControls() }
            context.unregisterReceiver(ReflectionHelpers.getField(controller, "receiver"))
            ReflectionHelpers.getField<CoroutineScope>(controller, "processScope").cancel()
        }
    }

    @Test fun admittedControllerSendsInitializationPayloadsInOrderWithoutDirectRepositoryStageCalls() = runBlocking<Unit> {
        Fixture(entitled = true).use { f ->
            f.admit(); assertTrue(f.controller.hasAdmission()); assertEquals(1, f.context.starts)
            val transport = Transport(); f.construct(transport); f.await(AacpStage.SUBSCRIBED)
            // No handshake/subscribe/featureFlags/bootstrap calls in this test: controller owns them.
            val expected = listOf(
                "00 00 04 00 01 00 02 00 00 00 00 00 00 00 00 00",
                "04 00 04 00 4d 00 d7 00 00 00 00 00 00 00",
                "04 00 04 00 0f 00 ff ff ff ff",
                "04 00 04 00 29 00 00 ff ff ff ff ff ff ff",
            ).map { row -> row.split(" ").map { it.toInt(16) } }
            assertEquals(expected, transport.writes.toList())
            assertEquals(1, transport.connects.get())
            assertFalse(f.controller.runtime.value.setupVerified) // no INFORMATION/basic RX
            assertNull(f.controller.state.value.identityReceived)
            assertNull(f.controller.state.value.deviceAnnounced)
            assertTrue(f.controller.state.value.properties.isEmpty())
        }
    }

    @Test fun disabledProductCannotUseAConstructedTransportToStartBootstrap() = runBlocking<Unit> {
        Fixture(entitled = false).use { f ->
            f.admit(); assertFalse(f.controller.hasAdmission()); assertEquals(0, f.context.starts)
            val transport = Transport(); f.construct(transport); f.await(AacpStage.CONSTRUCTED)
            f.controller.advanceBootstrap(AacpStage.CONSTRUCTED)
            assertEquals(0, transport.connects.get()); assertTrue(transport.writes.isEmpty())
            assertFalse(f.controller.runtime.value.productConformance)
        }
    }

    @Test fun revokedPermissionAtConnectionCompletionCannotStartBootstrap() = runBlocking<Unit> {
        Fixture(entitled = true).use { f ->
            f.admit(); assertTrue(f.controller.hasAdmission())
            val release = CountDownLatch(1); val transport = Transport(release)
            f.construct(transport)
            assertTrue(transport.connectStarted.await(2, TimeUnit.SECONDS))
            f.facts.permission = false; release.countDown(); f.await(AacpStage.CONNECTED)
            f.controller.advanceBootstrap(AacpStage.CONNECTED)
            assertFalse(f.controller.hasAdmission()); assertTrue(transport.writes.isEmpty())
        }
    }
}
