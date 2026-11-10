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
import dev.lelonio.square.airpods.runtime.lifecycle.ConnectionAdmission
import dev.lelonio.square.airpods.runtime.lifecycle.ExitHistory
import dev.lelonio.square.airpods.runtime.lifecycle.ExitHistoryProvider
import dev.lelonio.square.airpods.runtime.lifecycle.GateResult
import dev.lelonio.square.airpods.runtime.lifecycle.ObservationRegistration
import dev.lelonio.square.airpods.runtime.lifecycle.OwnedAssociation
import dev.lelonio.square.airpods.runtime.lifecycle.StartIngress
import dev.lelonio.square.airpods.runtime.storage.HostSettings
import dev.lelonio.square.airpods.runtime.storage.PairAddress
import dev.lelonio.square.airpods.runtime.storage.PairVerification
import dev.lelonio.square.airpods.runtime.storage.RuntimePersistence
import dev.lelonio.square.airpods.runtime.storage.StartState
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
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
 * PREPARATO, NON ESEGUITO E NON COMPILATO. Pubblicazione della porta media contro lo Stop, nei
 * due ordini, e callback di volume rimaste vive dopo lo Stop.
 *
 * Vero: controller, ammissione, AirPodsStartGate, store, stopControls / enableOrResume, stopOwner
 * e il ritiro della porta, e publishMedia, cioe' lo stesso passo che il collector di bootstrap
 * esegue sul main. La factory e' quella vera del costruttore.
 * Finto: fatti Bluetooth, registro companion, disco e la porta. Il test chiama publishMedia
 * direttamente sul main con il ticket reale: niente servizio, socket, AACP o hardware, e il
 * collector di attach() non gira. Lo scope passato a publishMedia NON viene cancellato dallo
 * Stop, cosi' il collector del volume e' la callback stantia peggiore possibile.
 *
 * Gli ordini sono forzati da due hook del rig: dentro la factory (porta creata, non ancora
 * pubblicata) e dentro port.start() (porta pubblicata). In quei punti il main del test aspetta un
 * worker: e' il rig che lo fa, il codice di produzione non aspetta nessuno.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class AirPodsMediaPublicationRaceTest {
    @Before fun processIdentity() { ShadowProcess.setPid(41033) }

    private class CountingContext(base: Context) : ContextWrapper(base) {
        @Volatile var starts = 0
        override fun checkSelfPermission(permission: String) = PackageManager.PERMISSION_GRANTED
        override fun startForegroundService(service: Intent): ComponentName {
            starts++; return ComponentName(this, AirPodsControlService::class.java)
        }
    }

    private class Facts : BluetoothRuntimeFacts {
        override val adapter: BluetoothAdapter? = null
        override fun permitted() = true
        override fun bond(address: PairAddress) = BondFact.BONDED
        override fun connection(address: PairAddress) = ProfileConnection.CONNECTED
        override fun prepare() = Unit
        override fun audioSettingsIntent() = Intent()
        override fun whenProfileReady(ready: () -> Unit): AutoCloseable? = null
    }

    private class Disk : RuntimePersistence {
        private var bytes: ByteArray? = null
        override fun read() = bytes
        override fun commit(bytes: ByteArray): Boolean { this.bytes = bytes; return true }
    }

    /** Fake port that owns nothing and does not fence itself: only the controller protects it. */
    private class Port : AirPodsMediaPort {
        override val volume = MutableStateFlow<MediaVolume?>(null)
        val revokes = AtomicInteger()
        val startCalls = AtomicInteger()
        val visibleCalls = AtomicInteger()
        val stops = AtomicInteger()
        @Volatile var revokeThread: Thread? = null
        @Volatile var stopThread: Thread? = null
        @Volatile var startedAfterRevoke = false
        @Volatile var stoppedBeforeStartReturned = false
        @Volatile private var starting = false
        @Volatile var onStart: () -> Unit = {}
        override fun start(session: AutomationSession, pair: PairAddress, settings: HostSettings) {
            if (revokes.get() > 0) startedAfterRevoke = true
            startCalls.incrementAndGet()
            starting = true
            try { onStart() } finally { starting = false }
        }
        override fun updateSettings(settings: HostSettings) = Unit
        override fun setUiVisible(visible: Boolean) { visibleCalls.incrementAndGet() }
        override fun onEvent(event: Received<AacpEvent>) = Unit
        override fun setUserVolume(value: Int): Boolean = false
        override fun gesture(action: AirPodsMediaGesture): Boolean = false
        override fun revoke() { if (revokes.getAndIncrement() == 0) revokeThread = Thread.currentThread() }
        override fun stop() {
            if (starting) stoppedBeforeStartReturned = true
            stopThread = Thread.currentThread()
            stops.incrementAndGet()
        }
    }

    /** A real off-main caller. Nothing here touches the main looper. */
    private class Background<T>(name: String, block: suspend () -> T) {
        val task = FutureTask<T>(Callable<T> { runBlocking { block() } })
        val thread: Thread = Thread(task, name).apply { isDaemon = true; start() }
        fun result(): T = task.get(10, TimeUnit.SECONDS)
    }

    private class Fixture : AutoCloseable {
        val main: Thread = Thread.currentThread()
        val looper = shadowOf(Looper.getMainLooper())
        private val app: Application = RuntimeEnvironment.getApplication()
        val context = CountingContext(app)
        val pair = PairAddress("AA:BB:CC:DD:EE:34")
        val facts = Facts()
        val disk = Disk()
        val port = Port()
        val factoryCalls = AtomicInteger()
        /** Runs on main inside the real factory: the port exists but is not published yet. */
        @Volatile var onFactory: () -> Unit = {}
        private val registry: CompanionRegistryFacade = object : CompanionRegistryFacade {
            override fun hasFeature() = true
            override fun hasObservePermission() = true
            override fun isBonded(address: PairAddress) = true
            override fun ownAssociations() = listOf(OwnedAssociation(5, pair, context.packageName))
            override fun start(registration: ObservationRegistration) = true
            override fun stop(registration: ObservationRegistration) = true
            override fun disassociate(association: OwnedAssociation) = true
        }
        // Process scope inert, as in I5VolumeDispatchTest. Product purpose models an OPEN gate.
        val controller = AirPodsAndroidController(context, MutableStateFlow(true), { true },
            { factoryCalls.incrementAndGet(); onFactory(); port },
            factsOverride = facts, registryOverride = registry,
            exitHistoryOverride = ExitHistoryProvider { ExitHistory.Available(emptyList()) },
            runtimeDispatcher = StandardTestDispatcher(), persistenceOverride = disk)
        val gate: AirPodsStartGate = ReflectionHelpers.getField(controller, "gate")
        private val ownerLock: Any = ReflectionHelpers.getField(controller, "lock")
        /** Stands for the session scope, but outlives the Stop on purpose. */
        val session = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        init {
            check(Looper.getMainLooper().thread === main) { "RIG: il test non gira sul thread main" }
            ReflectionHelpers.setField(controller, "migrationReady", true)
            controller.store.select(pair).getOrThrow()
            controller.store.recordSetup(pair, PairVerification("A3063", Build.FINGERPRINT, 1)).getOrThrow()
            controller.store.transaction {
                it.copy(pocMigrationCompleted = true, start = StartState(enabled = true, consentGeneration = 7)) to Unit
            }.getOrThrow()
            runBlocking { controller.refresh(StartIngress.RECEIVER) }
            assertTrue("RIG: ammissione reale mancante", controller.hasAdmission())
            assertEquals("RIG: avvio del servizio non richiesto una volta", 1, context.starts)
            assertEquals("RIG: la factory non deve essere chiamata dall'ammissione", 0, factoryCalls.get())
        }

        fun <T> background(name: String, block: suspend () -> T) = Background(name, block)
        fun ticket(): ConnectionAdmission? = ReflectionHelpers.getField<ConnectionAdmission?>(controller, "ticket")
        fun published(): AirPodsMediaPort? = ReflectionHelpers.getField<AirPodsMediaPort?>(controller, "media")
        fun cleanupPending(): Boolean = ReflectionHelpers.getField<Boolean>(gate, "cleanupPending")
        fun retiring(): List<AirPodsMediaPort> = synchronized(ownerLock) {
            ReflectionHelpers.getField<List<AirPodsMediaPort>>(controller, "retiringMedia").toList()
        }
        /** The production step, on main, for the admission [a] (default: the current ticket). */
        fun publish(a: ConnectionAdmission = requireNotNull(ticket()) { "RIG: nessun ticket" }): Boolean =
            controller.publishMedia(a, 1L, HostSettings(), session)

        /** True when another thread can enter the gate monitor right now: nobody is holding it. */
        fun gateFree(): Boolean {
            val entered = CountDownLatch(1)
            Thread({ synchronized(gate) { entered.countDown() } }, "publication-gate-probe").apply { isDaemon = true; start() }
            return entered.await(50, TimeUnit.MILLISECONDS)
        }

        fun pumpUntil(what: String, done: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (true) {
                looper.idle()
                if (done()) return
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

        /** A volume callback on the old port, delivered by its collector if one is still alive. */
        fun emitStale(id: String) {
            port.volume.value = MediaVolume(9, 10)
            looper.idle(); Thread.sleep(20); looper.idle()
            assertNull("CALLBACK STANTIA ($id): volume della porta vecchia pubblicato dopo lo Stop", controller.volume.value)
        }

        override fun close() {
            try {
                onFactory = {}
                port.onStart = {}
                val last = background("publication-final-stop") { controller.stopControls() }
                pumpUntil("arresto finale") { last.task.isDone }
                report("close", "finalStop=${last.result()} portStops=${port.stops.get()}")
            } finally {
                session.cancel()
                runCatching { context.unregisterReceiver(ReflectionHelpers.getField(controller, "receiver")) }
                ReflectionHelpers.getField<CoroutineScope>(controller, "processScope").cancel()
                looper.idle()
            }
        }
    }

    /** Control: with no Stop the step publishes, starts once and forwards the volume. A Stop then
     * ends it, and a callback that is still alive publishes nothing. */
    @Test fun p0_control_publishedPortIsStartedAndItsStaleVolumeCallbackIsRefusedAfterStop() {
        Fixture().use { f ->
            assertTrue("CONTROLLO FALLITO (p0): pubblicazione rifiutata senza alcuno Stop", f.publish())
            assertSame(f.port, f.published())
            assertEquals(1, f.factoryCalls.get())
            assertEquals(1, f.port.startCalls.get())
            assertEquals(1, f.port.visibleCalls.get())
            assertEquals(0, f.port.revokes.get())
            f.looper.idle() // the collector subscribes on main
            f.port.volume.value = MediaVolume(4, 10)
            f.looper.idle()
            assertEquals("CONTROLLO FALLITO (p0): il volume della porta viva non arriva", MediaVolume(4, 10), f.controller.volume.value)

            val stop = f.background("publication-stop-caller") { f.controller.stopControls() }
            f.pumpUntil("arresto concluso") { stop.task.isDone }
            report("p0", "result=${stop.result()} revokes=${f.port.revokes.get()} revokeThread='${f.port.revokeThread?.name}' portStops=${f.port.stops.get()}")
            assertTrue("ARRESTO NON RIUSCITO (p0): ${stop.result()}", stop.result() is GateResult.Stopped)
            assertNotSame("(p0) revoca eseguita dal main", f.main, f.port.revokeThread)
            assertEquals(1, f.port.stops.get())
            assertSame(f.main, f.port.stopThread)
            assertNull(f.published())
            assertNull(f.controller.volume.value)
            assertTrue("RIG (p0): lo scope del collector doveva sopravvivere allo Stop", f.session.isActive)
            f.emitStale("p0")
        }
    }

    /** Order A: the Stop clears the ticket after the port was created and before it is published. */
    @Test fun p1_stopBeforePublication_thePortIsRefusedRevokedAndNeverStarted() {
        Fixture().use { f ->
            var stopped: GateResult? = null
            f.onFactory = {
                val stop = f.background("publication-stop-caller") { f.controller.stopControls() }
                // No port is published, so this Stop needs nothing from main and completes here.
                f.waitWithoutPumping("arresto concluso mentre la porta e' in creazione") { stop.task.isDone }
                stopped = stop.result()
            }
            val published = f.publish()
            report("p1", "published=$published stop=$stopped revokes=${f.port.revokes.get()} startCalls=${f.port.startCalls.get()} " +
                "visibleCalls=${f.port.visibleCalls.get()} media=${f.published()} retiring=${f.retiring().size}")
            assertEquals("RIG (p1): la factory non e' stata chiamata una volta", 1, f.factoryCalls.get())
            assertTrue("RIG (p1): lo Stop non si e' concluso durante la creazione: $stopped", stopped is GateResult.Stopped)
            assertFalse("PUBBLICAZIONE (p1): porta accettata dopo lo Stop", published)
            assertNull("PORTA SFUGGITA (p1): pubblicata dopo che lo Stop aveva gia' catturato", f.published())
            assertEquals("PORTA SFUGGITA (p1): creata dopo lo Stop e non revocata", 1, f.port.revokes.get())
            assertEquals("OSSERVATORI (p1): start() su una porta creata dopo la revoca", 0, f.port.startCalls.get())
            assertEquals(0, f.port.visibleCalls.get())
            assertTrue(f.retiring().isEmpty())
            assertFalse(f.cleanupPending())
            assertFalse(f.controller.hasAdmission())
            assertEquals(1, f.context.starts)
            f.looper.idle()
            f.emitStale("p1")
            assertEquals("(p1) nessun teardown dovuto per una porta mai avviata", 0, f.port.stops.get())
        }
    }

    /** Order B: the port is published, and the Stop captures it while start() is still running. */
    @Test fun p2_publicationBeforeStop_theStopCapturesThePortAndCannotCompleteBeforeItsTeardown() {
        Fixture().use { f ->
            var stop: Background<GateResult>? = null
            f.port.onStart = {
                stop = f.background("publication-stop-caller") { f.controller.stopControls() }
                f.waitWithoutPumping("porta catturata e revocata dallo Stop, gate libero") { f.port.revokes.get() > 0 && f.gateFree() }
            }
            val published = f.publish()
            val caller = requireNotNull(stop) { "RIG (p2): start() non e' stato chiamato" }
            report("p2", "published=$published revokes=${f.port.revokes.get()} revokeThread='${f.port.revokeThread?.name}' " +
                "visibleCalls=${f.port.visibleCalls.get()} portStops=${f.port.stops.get()} retiring=${f.retiring().size} " +
                "cleanupPending=${f.cleanupPending()} stopDone=${caller.task.isDone}")
            assertTrue(published)
            assertEquals(1, f.port.startCalls.get())
            assertFalse("RIG (p2): la porta era gia' revocata all'ingresso di start()", f.port.startedAfterRevoke)
            assertNotSame("(p2) revoca eseguita dal main", f.main, f.port.revokeThread)
            assertNull("(p2) la porta catturata e' ancora pubblicata", f.published())
            assertEquals("PASSI DOPO LA REVOCA (p2): setUiVisible su una porta gia' catturata", 0, f.port.visibleCalls.get())
            // Main has served nothing: the Stop owns the port and is still waiting for its teardown.
            assertEquals(listOf<AirPodsMediaPort>(f.port), f.retiring())
            assertTrue(f.cleanupPending())
            assertEquals(0, f.port.stops.get())
            assertFalse("STOP COMPLETATO (p2) con la porta nuova non ancora fermata", caller.task.isDone)

            f.pumpUntil("arresto concluso") { caller.task.isDone }
            report("p2", "result=${caller.result()} portStops=${f.port.stops.get()}")
            assertTrue("ARRESTO NON RIUSCITO (p2): ${caller.result()}", caller.result() is GateResult.Stopped)
            assertEquals(1, f.port.stops.get())
            assertSame(f.main, f.port.stopThread)
            assertFalse("(p2) teardown eseguito dentro start()", f.port.stoppedBeforeStartReturned)
            assertTrue(f.retiring().isEmpty())
            assertFalse(f.cleanupPending())
            assertNull(f.controller.volume.value)
            f.emitStale("p2")
        }
    }

    /** Order A with a successor: the ticket is not null when the old port arrives, it is another one. */
    @Test fun p3_ticketReplacedDuringCreation_theOldPortDoesNotBecomeTheNewOwnersPort() {
        Fixture().use { f ->
            val old = requireNotNull(f.ticket())
            var restarted: GateResult? = null
            f.onFactory = {
                val swap = f.background("publication-restart-caller") { f.controller.stopControls(); f.controller.enableOrResume() }
                f.waitWithoutPumping("Stop e nuova ammissione durante la creazione") { swap.task.isDone }
                restarted = swap.result()
            }
            val published = f.publish(old)
            report("p3", "published=$published restart=$restarted serviceStarts=${f.context.starts} revokes=${f.port.revokes.get()} " +
                "startCalls=${f.port.startCalls.get()} media=${f.published()}")
            assertTrue("RIG (p3): nuova ammissione non ottenuta: $restarted", restarted is GateResult.Started)
            assertEquals("RIG (p3)", 2, f.context.starts)
            val current = f.ticket()
            assertNotNull("RIG (p3): nessun nuovo ticket", current)
            assertNotSame("RIG (p3): il ticket non e' cambiato", old, current)
            assertFalse("PUBBLICAZIONE (p3): porta del vecchio ticket accettata sotto il nuovo", published)
            assertNull("PORTA SFUGGITA (p3): il nuovo owner ha ereditato la porta vecchia", f.published())
            assertEquals(1, f.port.revokes.get())
            assertEquals(0, f.port.startCalls.get())
            assertTrue(f.controller.hasAdmission())
        }
    }

    /** No creation at all for an admission that is no longer current. */
    @Test fun p4_staleAdmission_createsNothing() {
        Fixture().use { f ->
            val old = requireNotNull(f.ticket())
            val stop = f.background("publication-stop-caller") { f.controller.stopControls() }
            f.pumpUntil("arresto concluso") { stop.task.isDone }
            assertTrue(stop.result() is GateResult.Stopped)
            assertFalse(f.publish(old))
            assertEquals("CREAZIONE (p4): factory chiamata per un'ammissione non piu' corrente", 0, f.factoryCalls.get())
            assertNull(f.published())
        }
    }
}

private fun report(id: String, text: String) = println("PUBLICATION[$id] $text")
