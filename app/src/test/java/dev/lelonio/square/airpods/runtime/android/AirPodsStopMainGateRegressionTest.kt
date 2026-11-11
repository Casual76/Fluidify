// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.android

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import dev.lelonio.square.airpods.AacpEvent
import dev.lelonio.square.airpods.Received
import dev.lelonio.square.airpods.runtime.AirPodsMediaGesture
import dev.lelonio.square.airpods.runtime.AirPodsMediaPort
import dev.lelonio.square.airpods.runtime.automation.AutomationSession
import dev.lelonio.square.airpods.runtime.automation.MediaVolume
import dev.lelonio.square.airpods.runtime.lifecycle.AirPodsStartGate
import dev.lelonio.square.airpods.runtime.lifecycle.CompanionRegistryFacade
import dev.lelonio.square.airpods.runtime.lifecycle.ExitHistory
import dev.lelonio.square.airpods.runtime.lifecycle.ExitHistoryProvider
import dev.lelonio.square.airpods.runtime.lifecycle.GateReason
import dev.lelonio.square.airpods.runtime.lifecycle.GateResult
import dev.lelonio.square.airpods.runtime.lifecycle.ObservationRegistration
import dev.lelonio.square.airpods.runtime.lifecycle.OwnedAssociation
import dev.lelonio.square.airpods.runtime.lifecycle.StartIngress
import dev.lelonio.square.airpods.runtime.storage.AirPodsRuntimeStore
import dev.lelonio.square.airpods.runtime.storage.HostSettings
import dev.lelonio.square.airpods.runtime.storage.PairAddress
import dev.lelonio.square.airpods.runtime.storage.PairVerification
import dev.lelonio.square.airpods.runtime.storage.RuntimePersistence
import dev.lelonio.square.airpods.runtime.storage.StartState
import dev.lelonio.square.airpods.runtime.storage.SuppressReason
import dev.lelonio.square.ui.airpods.host.AirPodsHost
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowProcess
import org.robolectric.util.ReflectionHelpers

/**
 * PREPARATO, NON ESEGUITO E NON COMPILATO. Regressioni dell'arresto dopo la correzione
 * dell'inversione gate -> main / main -> gate. Rig derivato da StopMainGateReproductionTest.
 *
 * Vero: controller, ammissione, AirPodsStartGate, store, stopControls / refresh(RECEIVER) /
 * enableOrResume / connect / selectPair, stopOwner e il ritiro della porta, AirPodsHost.refresh
 * sul looper main di Robolectric.
 * Finto: fatti Bluetooth, registro companion, disco, e la porta media, iniettata per riflessione
 * dopo l'ammissione reale (niente servizio, socket, bootstrap o hardware).
 *
 * Convenzione: ogni test PASSA solo se il comportamento e' quello corretto. Gli s1-s4 rifanno gli
 * interleaving di r1-r4; li' il PASS voleva dire difetto riprodotto, qui vuol dire difetto assente.
 * Nessun JUnit timeout: sposterebbe il test fuori dal thread main. Ogni attesa ha una scadenza.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class AirPodsStopMainGateRegressionTest {
    @Before fun processIdentity() { ShadowProcess.setPid(41032) }

    /** [order] stamps service starts and port events on one sequence, to compare them. */
    private class CountingContext(base: Context, private val order: AtomicInteger) : ContextWrapper(base) {
        @Volatile var starts = 0
        val startOrders = CopyOnWriteArrayList<Int>()
        override fun checkSelfPermission(permission: String) = PackageManager.PERMISSION_GRANTED
        override fun startForegroundService(service: Intent): ComponentName {
            starts++; startOrders.add(order.incrementAndGet())
            return ComponentName(this, AirPodsControlService::class.java)
        }
    }

    /** [nextQuery] models the latency of one profile query; the controller makes it while owning the gate. */
    private class Facts : BluetoothRuntimeFacts {
        override val adapter: BluetoothAdapter? = null
        @Volatile var profile = ProfileConnection.CONNECTED
        @Volatile var nextQuery: (() -> Unit)? = null
        override fun permitted() = true
        override fun bond(address: PairAddress) = BondFact.BONDED
        override fun connection(address: PairAddress): ProfileConnection {
            val hold = nextQuery
            if (hold != null) { nextQuery = null; hold() }
            return profile
        }
        override fun prepare() = Unit
        override fun audioSettingsIntent() = Intent()
        override fun whenProfileReady(ready: () -> Unit): AutoCloseable? = null
    }

    /** [nextCommit] models the latency of one durable commit; the gate makes it while owning its monitor. */
    private class Disk : RuntimePersistence {
        private var bytes: ByteArray? = null
        @Volatile var lastCommitThread: Thread? = null
        @Volatile var nextCommit: (() -> Unit)? = null
        override fun read() = bytes
        override fun commit(bytes: ByteArray): Boolean {
            lastCommitThread = Thread.currentThread()
            val hold = nextCommit
            if (hold != null) { nextCommit = null; hold() }
            this.bytes = bytes
            return true
        }
    }

    /** Fake port that owns nothing. It records who revoked it, who stopped it and in which order. */
    private class Port(private val order: AtomicInteger) : AirPodsMediaPort {
        override val volume = MutableStateFlow<MediaVolume?>(MediaVolume(8, 10))
        val revokes = AtomicInteger()
        val stopAttempts = AtomicInteger()
        val stops = AtomicInteger()
        @Volatile var revokeThread: Thread? = null
        @Volatile var stopThread: Thread? = null
        @Volatile var stoppedAt = 0
        @Volatile var stoppedBeforeRevoke = false
        @Volatile var failStop = false
        override fun start(session: AutomationSession, pair: PairAddress, settings: HostSettings) = Unit
        override fun updateSettings(settings: HostSettings) = Unit
        override fun setUiVisible(visible: Boolean) = Unit
        override fun onEvent(event: Received<AacpEvent>) = Unit
        override fun setUserVolume(value: Int): Boolean = false
        override fun gesture(action: AirPodsMediaGesture): Boolean = false
        override fun revoke() { if (revokes.getAndIncrement() == 0) revokeThread = Thread.currentThread() }
        override fun stop() {
            stopAttempts.incrementAndGet()
            stopThread = Thread.currentThread()
            if (revokes.get() == 0) stoppedBeforeRevoke = true
            check(!failStop) { "synthetic teardown failure" }
            stoppedAt = order.incrementAndGet()
            stops.incrementAndGet()
        }
    }

    /** The host's own registry: parks ONE association query, i.e. the second IO hop of AirPodsHost.refresh. */
    private class ParkingRegistry(private val delegate: CompanionRegistryFacade) : CompanionRegistryFacade by delegate {
        val parked = CountDownLatch(1)
        val release = CountDownLatch(1)
        @Volatile var parkNext = false
        override fun ownAssociations(): List<OwnedAssociation> {
            if (parkNext) {
                parkNext = false
                parked.countDown()
                release.await(20, TimeUnit.SECONDS)
            }
            return delegate.ownAssociations()
        }
    }

    /** A real off-main caller. Nothing here touches the main looper. */
    private class Background<T>(name: String, block: suspend () -> T) {
        val task = FutureTask<T>(Callable<T> { runBlocking { block() } })
        val thread: Thread = Thread(task, name).apply { isDaemon = true; start() }
        fun result(): T = task.get(10, TimeUnit.SECONDS)
    }

    /** Samples until it sees the defect: main BLOCKED at the gate while the gate's owner waits. */
    private class Witness(private val main: Thread, private val knownOwner: () -> Thread?) {
        private val seen = AtomicReference<String?>(null)
        @Volatile private var finished = false
        private val thread: Thread = Thread({
            while (!finished && seen.get() == null) {
                seen.set(inversionSnapshot(main, knownOwner()))
                Thread.sleep(1)
            }
        }, "stopgate-witness").apply { isDaemon = true; start() }
        fun finish(): String? { finished = true; thread.join(2_000); return seen.get() }
    }

    private class Contention<T>(val result: T, val blockedAtGate: String?, val inversion: String?, val stallMs: Long)

    private class Fixture(functional: Boolean = false) : AutoCloseable {
        val main: Thread = Thread.currentThread()
        val looper = shadowOf(Looper.getMainLooper())
        private val app: Application = RuntimeEnvironment.getApplication()
        private val order = AtomicInteger()
        val context = CountingContext(app, order)
        val pair = PairAddress("AA:BB:CC:DD:EE:32")
        val other = PairAddress("AA:BB:CC:DD:EE:33")
        val facts = Facts()
        val disk = Disk()
        val port = Port(order)
        private val registry: CompanionRegistryFacade = object : CompanionRegistryFacade {
            override fun hasFeature() = true
            override fun hasObservePermission() = true
            override fun isBonded(address: PairAddress) = true
            override fun ownAssociations() = listOf(OwnedAssociation(5, pair, context.packageName))
            override fun start(registration: ObservationRegistration) = true
            override fun stop(registration: ObservationRegistration) = true
            override fun disassociate(association: OwnedAssociation) = true
        }
        val hostRegistry = ParkingRegistry(registry)
        private val permit = if (functional) AirPodsFunctionalTestPermit.issue { 1L } else null
        // Same construction as I5VolumeDispatchTest and the reproduction: process scope inert, so
        // nothing but the operations below posts to main. Product purpose models an OPEN gate.
        val controller = AirPodsAndroidController(context, MutableStateFlow(true), { true }, null,
            testPermit = permit, scratchStore = if (functional) AirPodsRuntimeStore(disk) else null,
            factsOverride = facts, registryOverride = registry,
            exitHistoryOverride = ExitHistoryProvider { ExitHistory.Available(emptyList()) },
            runtimeDispatcher = StandardTestDispatcher(), persistenceOverride = disk)
        val gate: AirPodsStartGate = ReflectionHelpers.getField(controller, "gate")
        private val ownerLock: Any = ReflectionHelpers.getField(controller, "lock")
        val ui = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val host: AirPodsHost

        init {
            check(Looper.getMainLooper().thread === main) { "RIG: il test non gira sul thread main" }
            ReflectionHelpers.setField(controller, "migrationReady", true)
            controller.store.select(pair).getOrThrow()
            controller.store.recordSetup(pair, PairVerification("A3063", Build.FINGERPRINT, 1)).getOrThrow()
            controller.store.transaction {
                it.copy(pocMigrationCompleted = true, start = StartState(enabled = true, consentGeneration = 7)) to Unit
            }.getOrThrow()
            runBlocking {
                if (functional) assertTrue("RIG: sessione funzionale non ammessa", controller.beginFunctionalTest() is GateResult.Started)
                else controller.refresh(StartIngress.RECEIVER)
            }
            assertTrue("RIG: ammissione reale mancante", controller.hasAdmission())
            assertEquals("RIG: avvio del servizio non richiesto una volta", 1, context.starts)
            // SYNTHETIC: the port normally appears after service attach and AACP bootstrap.
            ReflectionHelpers.setField(controller, "media", port)
            host = AirPodsHost(app, controller, hostRegistry)
        }

        fun <T> background(name: String, block: suspend () -> T) = Background(name, block)
        fun witness(owner: () -> Thread? = { null }) = Witness(main, owner)
        fun failure(): String? = controller.runtime.value.failure
        fun start(): StartState = controller.store.snapshot().getOrThrow().start
        fun cleanupPending(): Boolean = ReflectionHelpers.getField<Boolean>(gate, "cleanupPending")
        fun outstanding(): Any? = ReflectionHelpers.getField<Any?>(gate, "outstanding")
        fun retained(): List<AirPodsMediaPort> = synchronized(ownerLock) {
            ReflectionHelpers.getField<List<AirPodsMediaPort>>(controller, "failedMedia").toList()
        }
        fun retiring(): List<AirPodsMediaPort> = synchronized(ownerLock) {
            ReflectionHelpers.getField<List<AirPodsMediaPort>>(controller, "retiringMedia").toList()
        }

        /** True when another thread can enter the gate monitor right now: nobody is holding it. */
        fun gateFree(): Boolean {
            val entered = CountDownLatch(1)
            Thread({ synchronized(gate) { entered.countDown() } }, "stopgate-gate-probe").apply { isDaemon = true; start() }
            return entered.await(50, TimeUnit.MILLISECONDS)
        }

        /** The main thread serves its queue. Returns the longest single stall of the looper, in ms. */
        fun pumpUntil(what: String, done: () -> Boolean): Long {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            var longest = 0L
            while (true) {
                val began = System.nanoTime()
                looper.idle()
                longest = maxOf(longest, System.nanoTime() - began)
                if (done()) return TimeUnit.NANOSECONDS.toMillis(longest)
                if (System.nanoTime() > deadline) throw AssertionError("RIG: tempo scaduto in attesa di: $what")
                Thread.sleep(1)
            }
        }

        /** The main thread is busy elsewhere: its queue is NOT served. */
        fun waitWithoutPumping(what: String, done: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (!done()) {
                if (System.nanoTime() > deadline) throw AssertionError("RIG: tempo scaduto in attesa di: $what")
                Thread.sleep(1)
            }
        }

        /** The real UI refresh on main, with nobody holding the gate. Doubles as an in-test control. */
        fun completedRefresh() {
            val refresh = ui.async { host.refresh() }
            pumpUntil("refresh UI senza contesa") { refresh.isCompleted }
            runBlocking { refresh.await() }
            assertNotNull("RIG: il vero host non ha prodotto uno stato", host.stamp)
        }

        /**
         * Same interleaving as the reproduction, no real-time dependence. [work] is the real
         * off-main operation. [armHold] installs a one-shot hold in a fake that the operation
         * calls while it owns the gate; the hold lasts until main is BLOCKED at the gate.
         */
        fun <T> contendAtGate(name: String, armHold: (hold: () -> Unit) -> Unit, work: suspend () -> T): Contention<T> {
            completedRefresh()
            hostRegistry.parkNext = true
            val refresh = ui.async { host.refresh() }
            pumpUntil("refresh UI fermo nella query delle associazioni") { hostRegistry.parked.count == 0L }
            val holding = CountDownLatch(1)
            val blocked = AtomicReference<String?>(null)
            val owner = AtomicReference<Thread?>(null)
            armHold {
                val self = Thread.currentThread()
                owner.set(self)
                holding.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
                while (blocked.get() == null && System.nanoTime() < deadline) {
                    blocked.set(contentionSnapshot(main, self))
                    if (blocked.get() == null) Thread.sleep(1)
                }
            }
            val worker = background(name, work)
            if (!holding.await(10, TimeUnit.SECONDS)) throw AssertionError("RIG: l'operazione non ha raggiunto il punto di attesa sotto gate")
            hostRegistry.release.countDown()
            val watch = witness { owner.get() }
            val stall = pumpUntil("refresh UI e operazione conclusi") { refresh.isCompleted && worker.task.isDone }
            return Contention(worker.result(), blocked.get(), watch.finish(), stall)
        }

        /**
         * Taken while main has served NOTHING since the operation began: everything asserted here
         * happened on the worker, without the main thread. In the defect the gate could not be
         * free at this point with the port still unstopped and no failure recorded.
         */
        fun assertRevokedWithoutMain(id: String) {
            report(id, "beforeMain revokes=${port.revokes.get()} revokeThread='${port.revokeThread?.name}' " +
                "stopAttempts=${port.stopAttempts.get()} retiring=${retiring().size} cleanupPending=${cleanupPending()} " +
                "admitted=${controller.hasAdmission()} failure=${failure()} serviceStarts=${context.starts}")
            assertNotNull("REVOCA ASSENTE ($id)", port.revokeThread)
            assertNotSame("REVOCA NON IMMEDIATA ($id): eseguita dal main", main, port.revokeThread)
            assertEquals("RIG ($id): il teardown non puo' essere gia' stato eseguito, il main non ha servito la coda",
                0, port.stopAttempts.get())
            assertEquals("TEARDOWN NON ACCODATO ($id)", listOf<AirPodsMediaPort>(port), retiring())
            assertTrue("GATE APERTO ($id): cleanup non confermato ma gia' concluso", cleanupPending())
            assertNull("FALLIMENTO PREMATURO ($id)", failure())
            assertFalse("AUTORITA' VECCHIA ($id): ammissione ancora corrente", controller.hasAdmission())
            assertEquals("AUTORITA' VECCHIA ($id)", TrackingBlockReason.NO_ADMITTED_SESSION, controller.trackingUnavailableReason(false))
            assertFalse("AUTORITA' VECCHIA ($id): volume ancora accettato", controller.setVolume(5))
        }

        fun assertPortStoppedOnceOnMain(id: String) {
            assertEquals("ARRESTO NON EFFETTIVO ($id): port.stop() non eseguito una volta", 1, port.stops.get())
            assertEquals("($id) tentativi di teardown", 1, port.stopAttempts.get())
            assertSame("($id) teardown fuori dal main", main, port.stopThread)
            assertFalse("($id) stop() eseguito prima della revoca", port.stoppedBeforeRevoke)
            assertTrue("($id) porta ancora trattenuta per un nuovo tentativo", retained().isEmpty())
            assertTrue("($id) porta ancora in ritiro", retiring().isEmpty())
            assertFalse("($id) cleanup non concluso nel gate", cleanupPending())
        }

        fun assertStoppedForReal(id: String, result: GateResult, inversion: String?, stallMs: Long) {
            report(id, "result=$result failure=${failure()} portStops=${port.stops.get()} stoppedOnMain=${port.stopThread === main} " +
                "retainedForRetry=${retained().size} retiring=${retiring().size} cleanupPending=${cleanupPending()} " +
                "admitted=${controller.hasAdmission()} longestMainStallMs=$stallMs")
            report(id, "inversion=${inversion ?: "none"}")
            assertNull("INVERSIONE ($id): il proprietario del gate attende mentre il main e' fermo sul gate: $inversion", inversion)
            assertTrue("ARRESTO NON RIUSCITO ($id): $result", result is GateResult.Stopped)
            assertPortStoppedOnceOnMain(id)
            assertNull("($id) failure", failure())
            assertFalse(controller.hasAdmission())
            assertTrue("STALLO ($id): main fermo $stallMs ms; il difetto ne costava almeno 750", stallMs < STALL_LIMIT_MS)
        }

        override fun close() {
            try {
                // Real stop with a served main looper: it also retries what a failed test retained.
                port.failStop = false
                val last = background("stopgate-final-stop") { controller.stopControls() }
                pumpUntil("arresto finale") { last.task.isDone }
                report("close", "finalStop=${last.result()} portStops=${port.stops.get()}")
            } finally {
                host.close()
                ui.cancel()
                runCatching { context.unregisterReceiver(ReflectionHelpers.getField(controller, "receiver")) }
                ReflectionHelpers.getField<CoroutineScope>(controller, "processScope").cancel()
                looper.idle()
            }
        }
    }

    // ---- Control --------------------------------------------------------------------------

    @Test fun k1_control_userStopWithFreeMainStopsThePortOnMain() {
        Fixture().use { f ->
            f.completedRefresh()
            val witness = f.witness()
            val stop = f.background("stopgate-stop-caller") { f.controller.stopControls() }
            val stall = f.pumpUntil("arresto completato") { stop.task.isDone }
            f.assertStoppedForReal("k1", stop.result(), witness.finish(), stall)
            f.completedRefresh()
            assertEquals(SuppressReason.USER_STOP, f.start().suppressReason)
            assertEquals(1, f.context.starts)
        }
    }

    // ---- The four reproduced interleavings, now with the correct outcome ------------------------

    /** r1: only the order of two entries in the main queue. The stop must not need main at all. */
    @Test fun s1_userStop_gateIsReleasedBeforeMainServesAnything() {
        Fixture().use { f ->
            f.completedRefresh()
            // 1. A UI refresh is in flight; its continuation gets queued on main, which is busy elsewhere.
            f.hostRegistry.parkNext = true
            val refresh = f.ui.async { f.host.refresh() }
            f.pumpUntil("refresh UI fermo nella query delle associazioni") { f.hostRegistry.parked.count == 0L }
            f.hostRegistry.release.countDown()
            f.waitWithoutPumping("continuation del refresh in coda al main") { !f.looper.isIdle }
            // 2. The real stop runs unmodified. Main serves nothing until step 3.
            f.disk.lastCommitThread = null
            val witness = f.witness { f.disk.lastCommitThread }
            val stop = f.background("stopgate-stop-caller") { f.controller.stopControls() }
            f.waitWithoutPumping("arresto fuori dal gate con la porta revocata") { f.port.revokes.get() > 0 && f.gateFree() }
            f.assertRevokedWithoutMain("s1")
            val during = f.start()
            assertTrue("SOPPRESSIONE NON DUREVOLE prima del cleanup (s1)", during.suppressUntilExplicitResume)
            assertEquals(SuppressReason.USER_STOP, during.suppressReason)
            assertFalse("ESITO PREMATURO (s1): Stopped prima che la porta sia stata fermata", stop.task.isDone)
            // 3. Main serves its queue in FIFO order: the refresh continuation first, then the teardown.
            val stall = f.pumpUntil("refresh UI e arresto conclusi") { refresh.isCompleted && stop.task.isDone }
            f.assertStoppedForReal("s1", stop.result(), witness.finish(), stall)
            assertNotNull("(s1) il refresh UI non ha prodotto uno stato", f.host.stamp)
        }
    }

    /** r2: the refresh reaches the gate while the stop is in its durable commit. */
    @Test fun s2_userStop_refreshBlockedAtGateDuringDurableCommit() = userStopUnderContention("s2", functional = false)

    /** r3: same on the session type that can own a media port in the frozen candidate. */
    @Test fun s3_debugFunctionalSession_sameContention() = userStopUnderContention("s3", functional = true)

    private fun userStopUnderContention(id: String, functional: Boolean) {
        Fixture(functional).use { f ->
            val c = f.contendAtGate("stopgate-stop-caller", { hold -> f.disk.nextCommit = hold }) { f.controller.stopControls() }
            report(id, "contention=${c.blockedAtGate ?: "NOT OBSERVED"}")
            val contention = c.blockedAtGate
                ?: throw AssertionError("RIG ($id): il main non e' mai stato visto bloccato sul gate")
            assertGateOwnership(contention)
            f.assertStoppedForReal(id, c.result, c.inversion, c.stallMs)
            assertEquals(SuppressReason.USER_STOP, f.start().suppressReason)
            assertEquals(1, f.context.starts)
        }
    }

    /** r4: nobody presses Stop. The disconnect must stop the port and must NOT suppress the feature. */
    @Test fun s4_ordinaryDisconnect_stopsThePortAndKeepsTheOptIn() {
        Fixture().use { f ->
            val c = f.contendAtGate("stopgate-receiver-worker", { hold ->
                f.facts.profile = ProfileConnection.DISCONNECTED
                f.facts.nextQuery = hold
            }) { f.controller.refresh(StartIngress.RECEIVER) }
            val start = f.start()
            report("s4", "failure=${f.failure()} portStops=${f.port.stops.get()} retainedForRetry=${f.retained().size} " +
                "suppressed=${start.suppressUntilExplicitResume} suppressReason=${start.suppressReason} " +
                "cleanupPending=${f.cleanupPending()} longestMainStallMs=${c.stallMs}")
            report("s4", "contention=${c.blockedAtGate ?: "NOT OBSERVED"}")
            report("s4", "inversion=${c.inversion ?: "none"}")
            val contention = c.blockedAtGate
                ?: throw AssertionError("RIG (s4): il main non e' mai stato visto bloccato sul gate")
            assertGateOwnership(contention)
            assertNull("INVERSIONE (s4): ${c.inversion}", c.inversion)
            f.assertPortStoppedOnceOnMain("s4")
            assertNull("(s4) failure", f.failure())
            assertFalse("SOPPRESSIONE (s4): una disconnessione pulita ha spento la funzione", start.suppressUntilExplicitResume)
            assertNull(start.suppressReason)
            assertTrue(start.enabled)
            assertFalse(f.controller.hasAdmission())
            assertTrue("STALLO (s4): main fermo ${c.stallMs} ms; il difetto ne costava almeno 1500", c.stallMs < STALL_LIMIT_MS)
        }
    }

    // ---- Revocation and new starts while the cleanup is not finished ---------------------------

    /** The AirPods reconnect while the old port is revoked but its teardown is still queued on main. */
    @Test fun s5_reconnectDuringCleanup_noSecondOwnerUntilThePortHasStopped() {
        Fixture().use { f ->
            f.completedRefresh()
            f.facts.profile = ProfileConnection.DISCONNECTED
            val witness = f.witness()
            val receiver = f.background("stopgate-receiver-worker") { f.controller.refresh(StartIngress.RECEIVER) }
            f.waitWithoutPumping("disconnessione fuori dal gate con la porta revocata") { f.port.revokes.get() > 0 && f.gateFree() }
            f.assertRevokedWithoutMain("s5")
            assertFalse("SOPPRESSIONE (s5): una disconnessione ordinaria non sopprime", f.start().suppressUntilExplicitResume)
            // Opt-in retained, device connected again: only the unfinished cleanup stands in the way.
            f.facts.profile = ProfileConnection.CONNECTED
            val early = f.background("stopgate-early-start") { f.controller.connect() }
            f.waitWithoutPumping("tentativo di avvio durante il cleanup") { early.task.isDone }
            report("s5", "earlyStart=${early.result()} serviceStarts=${f.context.starts}")
            assertEquals("SECONDO OWNER (s5): avvio ammesso a cleanup non concluso",
                GateResult.Denied(GateReason.CLEANUP_PENDING), early.result())
            assertEquals("SECONDO OWNER (s5): servizio avviato durante il cleanup", 1, f.context.starts)
            assertFalse(f.controller.hasAdmission())
            assertEquals(0, f.port.stopAttempts.get())
            // Main is served: the teardown runs, the gate settles, and only then a start is admitted.
            f.pumpUntil("disconnessione conclusa") { receiver.task.isDone && !f.cleanupPending() }
            receiver.result()
            assertNull("INVERSIONE (s5)", witness.finish())
            f.assertPortStoppedOnceOnMain("s5")
            assertNull(f.failure())
            val late = f.background("stopgate-late-start") { f.controller.connect() }
            f.pumpUntil("avvio dopo il cleanup") { late.task.isDone }
            report("s5", "lateStart=${late.result()} serviceStarts=${f.context.starts} portStoppedAt=${f.port.stoppedAt} startOrders=${f.context.startOrders}")
            assertTrue("AVVIO NEGATO (s5) a cleanup concluso: ${late.result()}", late.result() is GateResult.Started)
            assertEquals(2, f.context.starts)
            assertTrue("ORDINE (s5): nuovo owner avviato prima dello stop della porta", f.port.stoppedAt < f.context.startOrders.last())
            assertTrue(f.controller.hasAdmission())
            assertEquals("(s5) la porta vecchia e' stata fermata di nuovo", 1, f.port.stopAttempts.get())
        }
    }

    /** Explicit resume over a live owner: the old port stops on main before the new owner starts. */
    @Test fun s6_explicitResumeOverALiveOwner_waitsOffGateThenStarts() {
        Fixture().use { f ->
            f.completedRefresh()
            val witness = f.witness()
            val resume = f.background("stopgate-resume-caller") { f.controller.enableOrResume() }
            f.waitWithoutPumping("vecchio owner revocato e gate libero") { f.port.revokes.get() > 0 && f.gateFree() }
            f.assertRevokedWithoutMain("s6")
            val during = f.start()
            assertTrue("CONSENSO PREMATURO (s6): concesso a cleanup non concluso", during.suppressUntilExplicitResume)
            assertEquals(SuppressReason.USER_STOP, during.suppressReason)
            assertEquals("SECONDO OWNER (s6) durante il cleanup", 1, f.context.starts)
            assertFalse("ESITO PREMATURO (s6)", resume.task.isDone)
            f.pumpUntil("ripresa conclusa") { resume.task.isDone }
            report("s6", "result=${resume.result()} serviceStarts=${f.context.starts} portStoppedAt=${f.port.stoppedAt} " +
                "startOrders=${f.context.startOrders} failure=${f.failure()}")
            assertNull("INVERSIONE (s6)", witness.finish())
            assertTrue("RIPRESA NON RIUSCITA (s6): ${resume.result()}", resume.result() is GateResult.Started)
            f.assertPortStoppedOnceOnMain("s6")
            assertEquals(2, f.context.starts)
            assertTrue("ORDINE (s6): nuovo owner avviato prima dello stop della porta", f.port.stoppedAt < f.context.startOrders.last())
            val after = f.start()
            assertTrue(after.enabled)
            assertFalse(after.suppressUntilExplicitResume)
            assertTrue(f.controller.hasAdmission())
        }
    }

    /** Passive visibility/permission return stays observational after a settled disconnect.
     * The distinct, ordinary MainActivity opening is covered by AirPodsForegroundRestoreTest. */
    @Test fun s7_passiveReturnAfterASettledDisconnect_startsNothing() {
        Fixture().use { f ->
            f.completedRefresh()
            f.facts.profile = ProfileConnection.DISCONNECTED
            val receiver = f.background("stopgate-receiver-worker") { f.controller.refresh(StartIngress.RECEIVER) }
            f.pumpUntil("disconnessione conclusa") { receiver.task.isDone }
            receiver.result()
            f.assertPortStoppedOnceOnMain("s7")
            f.facts.profile = ProfileConnection.CONNECTED
            val passive = f.background("stopgate-app-open") { f.controller.refresh(StartIngress.APP_OPEN, allowAcquisition = false) }
            f.pumpUntil("apertura passiva") { passive.task.isDone }
            passive.result()
            f.completedRefresh()
            assertEquals("APERTURA PASSIVA (s7): servizio avviato", 1, f.context.starts)
            assertFalse(f.controller.hasAdmission())
            // Control: the very same state does admit a real automatic ingress.
            val automatic = f.background("stopgate-receiver-reconnect") { f.controller.refresh(StartIngress.RECEIVER) }
            f.pumpUntil("ingresso automatico") { automatic.task.isDone }
            automatic.result()
            assertEquals("CONTROLLO FALLITO (s7): l'ingresso automatico non avvia, il rilievo passivo non prova nulla", 2, f.context.starts)
            assertTrue(f.controller.hasAdmission())
        }
    }

    // ---- Failed cleanups stay failed, and are retried only explicitly ---------------------------

    @Test fun s8_teardownFailureAfterUserStop_staysLatchedUntilAnExplicitStop() {
        Fixture().use { f ->
            f.port.failStop = true
            val stop = f.background("stopgate-stop-caller") { f.controller.stopControls() }
            f.pumpUntil("arresto con teardown fallito") { stop.task.isDone && f.outstanding() == null }
            report("s8", "result=${stop.result()} failure=${f.failure()} stopAttempts=${f.port.stopAttempts.get()} " +
                "retainedForRetry=${f.retained().size} cleanupPending=${f.cleanupPending()}")
            assertEquals(GateResult.Denied(GateReason.CLEANUP_FAILED), stop.result())
            assertEquals("CLEANUP_FAILED", f.failure())
            assertEquals(listOf<AirPodsMediaPort>(f.port), f.retained())
            assertTrue(f.cleanupPending())
            assertEquals(SuppressReason.USER_STOP, f.start().suppressReason)
            // No automatic retry: serving main again changes nothing.
            f.looper.idle(); Thread.sleep(50); f.looper.idle()
            assertEquals("RIPROVA AUTOMATICA (s8)", 1, f.port.stopAttempts.get())
            assertEquals(0, f.port.stops.get())
            f.port.failStop = false
            val retry = f.background("stopgate-retry-stop") { f.controller.stopControls() }
            f.pumpUntil("arresto ripetuto") { retry.task.isDone }
            report("s8", "retry=${retry.result()} stopAttempts=${f.port.stopAttempts.get()} failure=${f.failure()}")
            assertTrue("RECUPERO FALLITO (s8): ${retry.result()}", retry.result() is GateResult.Stopped)
            assertEquals(2, f.port.stopAttempts.get())
            assertEquals(1, f.port.stops.get())
            assertTrue(f.retained().isEmpty())
            assertFalse(f.cleanupPending())
            assertNull(f.failure())
        }
    }

    /** A late failure of an ordinary disconnect escalates as a synchronous one did: once. */
    @Test fun s9_teardownFailureAfterDisconnect_suppressesOnceWithoutARetryLoop() {
        Fixture().use { f ->
            f.port.failStop = true
            f.facts.profile = ProfileConnection.DISCONNECTED
            val receiver = f.background("stopgate-receiver-worker") { f.controller.refresh(StartIngress.RECEIVER) }
            f.pumpUntil("disconnessione, due teardown falliti e gate assestato") {
                receiver.task.isDone && f.port.stopAttempts.get() == 2 && f.retiring().isEmpty() && f.outstanding() == null
            }
            receiver.result()
            f.looper.idle(); Thread.sleep(50); f.looper.idle()
            val start = f.start()
            report("s9", "failure=${f.failure()} stopAttempts=${f.port.stopAttempts.get()} retainedForRetry=${f.retained().size} " +
                "suppressed=${start.suppressUntilExplicitResume} suppressReason=${start.suppressReason} cleanupPending=${f.cleanupPending()}")
            assertEquals("RIPROVA AUTOMATICA (s9)", 2, f.port.stopAttempts.get())
            assertEquals(0, f.port.stops.get())
            assertTrue(start.suppressUntilExplicitResume)
            assertEquals(SuppressReason.START_FAILED, start.suppressReason)
            assertEquals("CLEANUP_FAILED", f.failure())
            assertTrue(f.cleanupPending())
            assertEquals(listOf<AirPodsMediaPort>(f.port), f.retained())
            assertEquals(1, f.context.starts)
        }
    }

    /** Selecting another pair stops the live owner as its own step and never waits for main under the gate. */
    @Test fun s10_selectingAnotherPair_stopsTheLiveOwnerFirst() {
        Fixture().use { f ->
            f.completedRefresh()
            val witness = f.witness()
            val select = f.background("stopgate-select-caller") { f.controller.selectPair(f.other).isSuccess }
            f.pumpUntil("selezione conclusa") { select.task.isDone }
            report("s10", "result=${select.result()} portStops=${f.port.stops.get()} selected=${f.controller.store.snapshot().getOrThrow().selectedPair}")
            assertNull("INVERSIONE (s10)", witness.finish())
            assertTrue("SELEZIONE FALLITA (s10)", select.result())
            f.assertPortStoppedOnceOnMain("s10")
            assertEquals(f.other, f.controller.store.snapshot().getOrThrow().selectedPair)
            assertEquals(1, f.context.starts)
            assertFalse(f.controller.hasAdmission())
        }
    }

    private companion object {
        /** The defect cost 750 ms per bounded wait. A healthy stop stalls main for a few ms. */
        const val STALL_LIMIT_MS = 500L
    }
}

private fun report(id: String, text: String) = println("STOPFIX[$id] $text")

private const val CONTROLLER = "dev.lelonio.square.airpods.runtime.android.AirPodsAndroidController"
private const val GATE = "dev.lelonio.square.airpods.runtime.lifecycle.AirPodsStartGate"
private val FOREIGN = listOf("java.", "javax.", "jdk.", "sun.", "kotlin.", "kotlinx.", "android.", "androidx.",
    "com.android.", "org.robolectric.", "org.junit.")

private class HeldMonitor(val name: String, val ownerId: Long, val ownerName: String)

/** java.lang.management is not in android.jar, so the host JVM is asked reflectively. Supplementary. */
private fun monitorOf(blocked: Thread): HeldMonitor? = runCatching {
    val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
    val info = Class.forName("java.lang.management.ThreadMXBean")
        .getMethod("getThreadInfo", Long::class.javaPrimitiveType).invoke(bean, blocked.id)
    val type = Class.forName("java.lang.management.ThreadInfo")
    HeldMonitor(type.getMethod("getLockName").invoke(info) as String,
        type.getMethod("getLockOwnerId").invoke(info) as Long,
        type.getMethod("getLockOwnerName").invoke(info) as String)
}.getOrNull()

/** True when main is BLOCKED exactly at the monitor entry of trackingUnavailableReason, i.e. at synchronized(gate). */
private fun blockedAtGate(main: Thread): StackTraceElement? {
    if (main.state != Thread.State.BLOCKED) return null
    val top = main.stackTrace.firstOrNull() ?: return null
    return if (top.methodName == "trackingUnavailableReason" && main.state == Thread.State.BLOCKED) top else null
}

/** Ordinary contention, harmless by itself: main BLOCKED at the gate while [worker] owns it. */
private fun contentionSnapshot(main: Thread, worker: Thread): String? {
    val top = blockedAtGate(main) ?: return null
    val monitor = monitorOf(main)
    return "main=BLOCKED at ${top.className.substringAfterLast('.')}.${top.methodName}:${top.lineNumber} " +
        "lock=${monitor?.name ?: "unavailable"} owner='${monitor?.ownerName ?: "unavailable"}' " +
        "ownerIsWorker=${monitor?.let { it.ownerId == worker.id } ?: "unknown"} worker='${worker.name}' ${worker.state}"
}

/**
 * The defect: main BLOCKED at the gate while the thread that owns the gate is itself waiting, and
 * the code that put it to wait is the controller or the gate (mainCall in the frozen candidate).
 * A wait started by a fake of this rig, by the repository or by the scheduler is not reported.
 */
private fun inversionSnapshot(main: Thread, knownOwner: Thread?): String? {
    val top = blockedAtGate(main) ?: return null
    val monitor = monitorOf(main)
    val owner = monitor?.let { held -> Thread.getAllStackTraces().keys.firstOrNull { it.id == held.ownerId } } ?: knownOwner ?: return null
    val state = owner.state
    if (state != Thread.State.WAITING && state != Thread.State.TIMED_WAITING) return null
    val waiter = owner.stackTrace.firstOrNull { frame -> FOREIGN.none { frame.className.startsWith(it) } } ?: return null
    if (!waiter.className.startsWith(CONTROLLER) && !waiter.className.startsWith(GATE)) return null
    return "main=BLOCKED at ${top.className.substringAfterLast('.')}.${top.methodName}:${top.lineNumber} " +
        "lock=${monitor?.name ?: "unavailable"} owner='${owner.name}' $state " +
        "waitingIn=${waiter.className.substringAfterLast('.')}.${waiter.methodName}:${waiter.lineNumber}"
}

/** Stack evidence is always required; the monitor identity is asserted whenever the JVM exposes it. */
private fun assertGateOwnership(evidence: String) {
    if ("lock=unavailable" in evidence) return
    assertTrue(evidence, "lock=dev.lelonio.square.airpods.runtime.lifecycle.AirPodsStartGate@" in evidence)
    assertTrue(evidence, "ownerIsWorker=true" in evidence)
}
