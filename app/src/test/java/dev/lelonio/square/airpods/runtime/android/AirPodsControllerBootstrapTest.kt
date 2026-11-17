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
import dev.lelonio.square.airpods.runtime.*
import dev.lelonio.square.airpods.runtime.media.*
import dev.lelonio.square.airpods.runtime.automation.*
import dev.lelonio.square.airpods.runtime.android.platform.*
import dev.lelonio.square.airpods.protocol.*
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Actual controller bootstrap decisions, repository, gate and store.
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
        val reads = AtomicInteger()
        @Volatile var permission = true
        override fun permitted(): Boolean { reads.incrementAndGet(); return permission }
        override fun bond(address: PairAddress) = BondFact.BONDED
        override fun connection(address: PairAddress) = ProfileConnection.CONNECTED
        override fun prepare() = Unit
        override fun audioSettingsIntent() = Intent()
        override fun whenProfileReady(ready: () -> Unit): AutoCloseable? = null
    }
    private class Disk : RuntimePersistence {
        private var bytes: ByteArray? = null
        val reads = AtomicInteger()
        override fun read(): ByteArray? { reads.incrementAndGet(); return bytes }
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
        val disk = Disk()
        val callReads = AtomicInteger()
        var callState = OrdinaryCallState(OrdinaryCallState.IDLE)
        val pair = PairAddress("AA:BB:CC:DD:EE:08")
        private val registry = object : CompanionRegistryFacade {
            private var associated = true
            override fun hasFeature() = true
            override fun hasObservePermission() = true
            override fun isBonded(address: PairAddress) = true
            override fun ownAssociations() = if (associated) listOf(OwnedAssociation(8, pair, context.packageName)) else emptyList()
            override fun start(registration: ObservationRegistration) = true
            override fun stop(registration: ObservationRegistration) = true
            override fun disassociate(association: OwnedAssociation): Boolean { associated = false; return true }
        }
        val controller = AirPodsAndroidController(context, MutableStateFlow(true), { entitled }, null,
            factsOverride = facts, registryOverride = registry,
            exitHistoryOverride = ExitHistoryProvider { ExitHistory.Available(emptyList()) },
            runtimeDispatcher = Dispatchers.Unconfined, persistenceOverride = disk,
            callPort = object : OrdinaryCallPort {
                override fun state(): OrdinaryCallState { callReads.incrementAndGet(); return callState }
                override fun dispatch(answer: Boolean) = PlatformResult.RequestAccepted
            })
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
        suspend fun construct(transport: Transport) { repository.construct(pair) { transport } }
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
    @Test fun forgetDeletesPairValuesThroughTheControllerAndDoesNotRestoreThemOnReselect() = runBlocking<Unit> {
        Fixture(entitled = true).use { f ->
            f.admit(); f.construct(Transport()); f.await(AacpStage.SUBSCRIBED)
            val g = f.repository.state.value.generation
            f.repository.receive(g, AacpEvent.Information(AirPodsIdentity("A3063", "Apple Inc.", "fixture")))
            assertTrue(f.repository.setMode(ListeningMode.ADAPTIVE))
            withTimeout(2_000) { f.repository.state.first { it.desired[CommandProperty.MODE]?.status == dev.lelonio.square.airpods.protocol.DesiredStatus.SENT } }
            assertTrue(f.repository.state.value.cachedValues.choices.isNotEmpty())
            val values = ReflectionHelpers.getField<AirPodsPairValueStore>(f.repository, "valueStore")
            val forgotten = f.controller.forget()
            assertTrue(forgotten.toString(), forgotten is GateResult.Stopped)
            assertNull(f.controller.store.snapshot().getOrThrow().selectedPair)
            assertEquals(AirPodsPairValues(), values.load(f.pair).getOrThrow())
            assertTrue(f.repository.state.value.cachedValues.choices.isEmpty())
            f.controller.store.select(f.pair).getOrThrow()
            f.repository.construct(f.pair) { Transport() }; f.await(AacpStage.CONSTRUCTED)
            assertTrue(f.repository.state.value.cachedValues.choices.isEmpty())
            assertTrue(f.repository.state.value.desired.isEmpty())
        }
    }
    @Test fun realHeadConsumerDoesNotReadMainEnvironmentPhoneOrStorePerSampleAndResetsOnContextChange() = runBlocking<Unit> {
        Fixture(entitled = true).use { f ->
            f.admit(); f.construct(Transport()); f.await(AacpStage.SUBSCRIBED)
            val g = f.repository.state.value.generation
            f.repository.receive(g, AacpEvent.Information(AirPodsIdentity("A3063", "Apple Inc.", "fixture")))
            f.controller.store.configure(f.pair) { it.copy(host = HostSettings(headGestures = true, automationsEnabled = true)) }.getOrThrow()
            assertTrue(f.repository.setHeadTracking(HeadPacketFormat.STANDARD, true))
            withTimeout(2_000) { f.controller.state.first { it.activePacketFormat != null } }
            val environmentReads = AtomicInteger()
            val local = object : LocalAirPodsMediaBridge {
                override fun read(pair: PairAddress): MediaCandidate? { environmentReads.incrementAndGet(); return null }
                override fun observe(changed: () -> Unit) = Closeable {}
                override fun execute(target: MediaTarget, action: MediaAction, value: Int?, effectId: Long?) = false
                override fun gesture(target: MediaTarget, action: AirPodsMediaGesture) = false
            }
            val environment = AndroidMediaEnvironment(f.context, local).also { it.pair = f.pair }
            val media = AndroidAirPodsMediaRuntime(f.context, environment = environment)
            ReflectionHelpers.setField(f.controller, "media", media)
            f.controller.refreshHeadContext() // Actual Android gestureContext -> environment.read.
            assertEquals(1, environmentReads.get())
            val storeReads = f.disk.reads.get(); val phoneReads = f.callReads.get()
            val head = Received<AacpEvent>(AacpEvent.Head(HeadSample(0, 0, 0, 0, 0, 0x44)), android.os.SystemClock.elapsedRealtime(), g)
            // Main is not pumped while the background consumer runs this burst.
            withContext(Dispatchers.IO) { repeat(500) { f.controller.consumeHead(head) } }
            assertEquals(1, environmentReads.get())
            assertEquals(phoneReads, f.callReads.get())
            assertEquals(storeReads, f.disk.reads.get())
            val detector = ReflectionHelpers.getField<Any>(f.controller, "detector")
            f.controller.refreshHeadContext(); f.controller.consumeHead(head)
            assertSame(detector, ReflectionHelpers.getField(f.controller, "detector"))
            f.callState = OrdinaryCallState(OrdinaryCallState.RINGING)
            f.controller.refreshHeadContext(); f.controller.consumeHead(head)
            val changed = ReflectionHelpers.getField<Any>(f.controller, "detector")
            assertNotSame(detector, changed)
            f.controller.store.configure(f.pair) { it.copy(nodAction = HeadAction.NEXT) }.getOrThrow()
            f.controller.consumeHead(head) // Revision fences stale settings before the next refresh.
            assertSame(changed, ReflectionHelpers.getField(f.controller, "detector"))
            f.controller.refreshHeadContext(); f.controller.consumeHead(head)
            assertNotSame(changed, ReflectionHelpers.getField(f.controller, "detector"))
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun headContextLoopDoesNoWorkWhenGesturesOffAndRestartsWhenEnabled() = runBlocking {
        val timers = TestScope()
        Fixture(entitled = true).use { f ->
            f.admit(); f.construct(Transport()); f.await(AacpStage.SUBSCRIBED)
            val job = timers.launch { f.controller.runHeadContextLoop { true } }
            val before = f.callReads.get()
            val bluetoothBefore = f.facts.reads.get()
            timers.runCurrent(); timers.advanceTimeBy(1_000); timers.runCurrent()
            assertEquals("No Bluetooth context polling while disabled", bluetoothBefore, f.facts.reads.get())
            assertEquals(before, f.callReads.get())
            f.controller.store.configure(f.pair) { it.copy(host = HostSettings(headGestures = true, automationsEnabled = true)) }.getOrThrow()
            f.repository.receive(f.repository.state.value.generation, AacpEvent.Information(AirPodsIdentity("A3063", "Apple Inc.", "fixture")))
            assertTrue(f.repository.setHeadTracking(HeadPacketFormat.STANDARD, true))
            withTimeout(2_000) { f.controller.state.first { it.activePacketFormat != null } }
            timers.advanceTimeBy(200); timers.runCurrent()
            assertTrue("Enabled tracking restarts context refresh", f.callReads.get() > before)
            f.controller.store.configure(f.pair) { it.copy(host = it.host.copy(headGestures = false)) }.getOrThrow()
            timers.advanceTimeBy(200); timers.runCurrent()
            val stopped = f.facts.reads.get()
            timers.advanceTimeBy(1_000); timers.runCurrent()
            assertEquals(stopped, f.facts.reads.get())
            job.cancel(); timers.runCurrent(); job.join()
        }
    }

}
