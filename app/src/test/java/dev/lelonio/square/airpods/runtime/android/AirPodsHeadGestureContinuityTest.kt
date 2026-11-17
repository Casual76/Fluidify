// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.android

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import dev.lelonio.square.airpods.*
import dev.lelonio.square.airpods.runtime.lifecycle.*
import dev.lelonio.square.airpods.runtime.storage.*
import dev.lelonio.square.airpods.runtime.*
import dev.lelonio.square.airpods.runtime.media.*
import dev.lelonio.square.airpods.runtime.automation.*
import dev.lelonio.square.airpods.runtime.android.platform.*
import dev.lelonio.square.airpods.protocol.*
import java.util.concurrent.LinkedBlockingQueue
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** G1 regressions, using the R3c p10 fixture and the real pinned detector. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class AirPodsHeadGestureContinuityTest {
    @Before fun processIdentity() { org.robolectric.shadows.ShadowProcess.setPid(41008) }

    private class SchedulingContext(base: Context) : ContextWrapper(base) {
        override fun checkSelfPermission(permission: String) = PackageManager.PERMISSION_GRANTED
        override fun startForegroundService(service: Intent): ComponentName = ComponentName(this, AirPodsControlService::class.java)
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
        override fun read(): ByteArray? = bytes
        override fun commit(bytes: ByteArray): Boolean { this.bytes = bytes.copyOf(); return true }
    }
    private class Transport : AacpTransport {
        private val rx = LinkedBlockingQueue<ByteArray>()
        override fun connect() {}
        override fun readSdu(): ByteArray? = rx.take().takeIf { it.isNotEmpty() }
        override fun write(packet: ByteArray) {}
        override fun close() { rx.offer(byteArrayOf()) }
    }
    /** Media port fake: counts the main-thread context reads and records effects. */
    private class Port : AirPodsMediaPort {
        val contextReads = AtomicInteger()
        val gestures = java.util.Collections.synchronizedList(mutableListOf<AirPodsMediaGesture>())
        @Volatile var target: Any? = "fluidify-session"
        val rev = java.util.concurrent.atomic.AtomicLong()
        override val volume = MutableStateFlow<MediaVolume?>(null)
        override fun start(session: AutomationSession, pair: PairAddress, settings: HostSettings) {}
        override fun updateSettings(settings: HostSettings) { rev.incrementAndGet() }
        override fun setUiVisible(visible: Boolean) { rev.incrementAndGet() }
        override fun onEvent(event: Received<AacpEvent>) {}
        override fun setUserVolume(value: Int) = false
        override fun gestureContext(): Any? { contextReads.incrementAndGet(); return target }
        override val gestureContextRevision: Long get() = rev.get()
        override fun gesture(action: AirPodsMediaGesture): Boolean { gestures.add(action); return true }
        override fun stop() {}
    }
    private class Fixture : AutoCloseable {
        val context = SchedulingContext(RuntimeEnvironment.getApplication())
        val facts = Facts()
        val callReads = AtomicInteger()
        @Volatile var callState = OrdinaryCallState(OrdinaryCallState.IDLE)
        val visible = MutableStateFlow(true)
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
        val controller = AirPodsAndroidController(context, visible, { true }, null,
            factsOverride = facts, registryOverride = registry,
            exitHistoryOverride = ExitHistoryProvider { ExitHistory.Available(emptyList()) },
            runtimeDispatcher = Dispatchers.Unconfined, persistenceOverride = Disk(),
            callPort = object : OrdinaryCallPort {
                override fun state(): OrdinaryCallState { callReads.incrementAndGet(); return callState }
                override fun dispatch(answer: Boolean) = PlatformResult.RequestAccepted
            })
        val repository = ReflectionHelpers.getField<AirPodsRepository>(controller, "repository")
        val port = Port()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        init {
            ReflectionHelpers.setField(controller, "migrationReady", true)
            controller.store.select(pair).getOrThrow()
            controller.store.recordSetup(pair, PairVerification("A3063", Build.FINGERPRINT, 1)).getOrThrow()
            controller.store.transaction {
                it.copy(pocMigrationCompleted = true, start = StartState(enabled = true, consentGeneration = 7)) to Unit
            }.getOrThrow()
            scope.launch(start = CoroutineStart.UNDISPATCHED) { controller.state.collect { controller.advanceBootstrap(it.stage) } }
        }
        suspend fun ready() {
            controller.refresh(StartIngress.RECEIVER)
            repository.construct(pair) { Transport() }
            withTimeout(2_000) { controller.state.first { it.stage == AacpStage.SUBSCRIBED } }
            repository.receive(repository.state.value.generation, AacpEvent.Information(AirPodsIdentity("A3063", "Apple Inc.", "fixture")))
            ReflectionHelpers.setField(controller, "media", port)
        }
        fun gestures(on: Boolean, background: Boolean = false) = controller.store.configure(pair) {
            it.copy(host = HostSettings(headGestures = on, automationsEnabled = true, fluidifyBackgroundEnabled = background),
                nodAction = HeadAction.NEXT, shakeAction = HeadAction.PREVIOUS)
        }.getOrThrow()
        suspend fun tracking(on: Boolean) {
            assertTrue(repository.setHeadTracking(HeadPacketFormat.STANDARD, on))
            withTimeout(2_000) { controller.state.first { (it.activePacketFormat != null) == on } }
        }
        fun head(v: Int) = Received<AacpEvent>(AacpEvent.Head(HeadSample(0, 0, 0, 0, v, 0x44)),
            android.os.SystemClock.elapsedRealtime(), repository.state.value.generation)
        /** Vertical oscillation +-1000, no horizontal: what the pinned detector reads as a nod. */
        suspend fun nod() {
            repeat(4) { listOf(0, 1000, 1000, 0, -1000, -1000).forEach { controller.consumeHead(head(it)) } }
            repeat(3) { controller.consumeHead(head(0)) }
        }
        fun fresh(): Boolean = ReflectionHelpers.callInstanceMethod<Any?>(controller, "currentHeadContext") != null
        override fun close() {
            scope.cancel(); repository.close()
            runBlocking { controller.stopControls() }
            context.unregisterReceiver(ReflectionHelpers.getField(controller, "receiver"))
            ReflectionHelpers.getField<CoroutineScope>(controller, "processScope").cancel()
        }
    }

    private val nodSeq = List(4) { listOf(0, 1000, 1000, 0, -1000, -1000) }.flatten() + listOf(0, 0, 0)
    private fun Fixture.detector(): Any = ReflectionHelpers.getField(controller, "detector")
    private suspend fun Fixture.feed(values: List<Int>) { values.forEach { controller.consumeHead(head(it)) } }
    private suspend fun prepared(block: suspend (Fixture) -> Unit) {
        Fixture().use { f ->
            f.ready(); f.gestures(true); f.tracking(true)
            f.controller.refreshHeadContext(); assertTrue(f.fresh())
            block(f)
        }
    }
    private suspend fun nodWithBump(bumpAt: Int?): Int {
        var fired = -1
        prepared { f ->
            nodSeq.forEachIndexed { i, v ->
                if (i == bumpAt) f.port.rev.incrementAndGet()
                f.controller.consumeHead(f.head(v))
                if (i == bumpAt) f.controller.refreshHeadContext()
                if (f.port.gestures.isNotEmpty() && fired < 0) fired = i
            }
            assertEquals(listOf(AirPodsMediaGesture.NEXT), f.port.gestures.toList())
        }
        return fired
    }
    @Test fun p10_sameTargetObservationPreservesNod() = runBlocking<Unit> {
        val control = nodWithBump(null)
        assertTrue(control > 0)
        // Every interruption strictly before recognition must still produce exactly NEXT.
        repeat(control) { bump -> assertEquals("bump at $bump", control, nodWithBump(bump)) }
        println("CR3 p10: control sample=$control, preserved bump positions=$control")
    }
    @Test fun changedTargetResetsOnlyWhenFreshAndCannotInheritNod() = runBlocking<Unit> {
        prepared { f ->
            f.feed(nodSeq.take(12)); val before = f.detector()
            f.port.target = "new-session"; f.port.rev.incrementAndGet()
            f.feed(nodSeq.subList(12, 15))
            assertSame(before, f.detector()); assertTrue(f.port.gestures.isEmpty())
            f.controller.refreshHeadContext(); assertTrue(f.fresh())
            f.controller.consumeHead(f.head(nodSeq[15]))
            assertNotSame(before, f.detector())
            f.feed(nodSeq.drop(16)); assertTrue(f.port.gestures.isEmpty())
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1001))
            f.controller.refreshHeadContext(); f.feed(nodSeq)
            assertEquals(listOf(AirPodsMediaGesture.NEXT), f.port.gestures.toList())
        }
    }
    @Test fun expiredContextSuppressesEffectsWithoutResetOrPlatformReads() = runBlocking<Unit> {
        prepared { f ->
            f.feed(nodSeq.take(12)); val before = f.detector()
            val reads = f.port.contextReads.get(); val calls = f.callReads.get()
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(300))
            assertFalse(f.fresh())
            f.feed(nodSeq.drop(12))
            assertSame(before, f.detector()); assertTrue(f.port.gestures.isEmpty())
            assertEquals(reads, f.port.contextReads.get()); assertEquals(calls, f.callReads.get())
            // Still stale even at recognition: no queued effect is dispatched by refresh itself.
            f.controller.refreshHeadContext(); assertTrue(f.port.gestures.isEmpty())
            assertSame(before, f.detector())
        }
    }
    @Test fun liveTargetRecheckRejectsUnannouncedTargetChange() = runBlocking<Unit> {
        prepared { f ->
            f.feed(nodSeq.take(12))
            f.port.target = "new-session" // Cached context is fresh; final live read must fence it.
            f.feed(nodSeq.drop(12)); assertTrue(f.port.gestures.isEmpty())
            f.controller.refreshHeadContext()
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1001))
            f.controller.refreshHeadContext(); f.feed(nodSeq)
            assertEquals(listOf(AirPodsMediaGesture.NEXT), f.port.gestures.toList())
        }
    }
    @Test fun permissionRevokedMidNodSuppressesEvenFreshCachedContext() = runBlocking<Unit> {
        prepared { f ->
            f.feed(nodSeq.take(12)); f.facts.permission = false
            f.feed(nodSeq.drop(12)); assertTrue(f.port.gestures.isEmpty())
            f.controller.permissionsChanged(); assertFalse(f.fresh())
            f.feed(nodSeq); assertTrue(f.port.gestures.isEmpty())
        }
    }
    @Test fun visibilityRevokedMidNodCannotActOrCarryIntoResume() = runBlocking<Unit> {
        prepared { f ->
            val timers = TestScope()
            val job = timers.launch { f.controller.runHeadContextLoop { true } }
            timers.runCurrent()
            f.feed(nodSeq.take(12)); f.visible.value = false
            withTimeout(2_000) { f.controller.state.first { it.activePacketFormat == null } }
            timers.runCurrent(); job.cancel(); timers.runCurrent()
            f.feed(nodSeq.subList(12, 15)); assertTrue(f.port.gestures.isEmpty())
            f.visible.value = true
            withTimeout(2_000) { f.controller.state.first { it.activePacketFormat != null } }
            f.controller.refreshHeadContext()
            f.feed(nodSeq.drop(15)); assertTrue(f.port.gestures.isEmpty())
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1001))
            f.controller.refreshHeadContext(); f.feed(nodSeq)
            assertEquals(listOf(AirPodsMediaGesture.NEXT), f.port.gestures.toList())
        }
    }
    @Test fun gapOverOneSecondStillResetsDetector() = runBlocking<Unit> {
        prepared { f ->
            f.feed(nodSeq.take(12)); val before = f.detector()
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1001))
            f.controller.refreshHeadContext()
            f.controller.consumeHead(f.head(nodSeq[12])); assertNotSame(before, f.detector())
            f.feed(nodSeq.drop(13)); assertTrue(f.port.gestures.isEmpty())
        }
    }
    @Test fun disabledGesturesAndMasterDropPartialNod() = runBlocking<Unit> {
        for (master in listOf(false, true)) prepared { f ->
            val timers = TestScope()
            val job = timers.launch { f.controller.runHeadContextLoop { true } }
            timers.runCurrent()
            try {
                f.feed(nodSeq.take(12))
                f.controller.store.configure(f.pair) { it.copy(host = it.host.copy(
                    automationsEnabled = master, headGestures = !master)) }.getOrThrow()
                timers.advanceTimeBy(100); timers.runCurrent()
                f.feed(nodSeq.drop(12)); assertTrue(f.port.gestures.isEmpty())
                f.gestures(true)
                timers.advanceTimeBy(100); timers.runCurrent()
                f.feed(nodSeq.drop(12)); assertTrue(f.port.gestures.isEmpty())
                org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1001))
                f.controller.refreshHeadContext(); f.feed(nodSeq)
                assertEquals(listOf(AirPodsMediaGesture.NEXT), f.port.gestures.toList())
            } finally { job.cancel(); timers.runCurrent() }
        }
    }
    @Test fun renewedSessionRejectsOldGenerationAndPartialNod() = runBlocking<Unit> {
        prepared { f ->
            f.feed(nodSeq.take(12)); val old = f.head(1000)
            f.repository.close()
            f.ready(); f.gestures(true); f.tracking(true); f.controller.refreshHeadContext()
            assertNotEquals(old.generation, f.repository.state.value.generation)
            f.controller.consumeHead(old)
            f.feed(nodSeq.drop(12)); assertTrue(f.port.gestures.isEmpty())
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1001))
            f.controller.refreshHeadContext(); f.feed(nodSeq)
            assertEquals(listOf(AirPodsMediaGesture.NEXT), f.port.gestures.toList())
        }
    }
}
