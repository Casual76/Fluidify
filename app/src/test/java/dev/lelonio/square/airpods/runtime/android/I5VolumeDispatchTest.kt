// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.android

import android.app.Activity
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import dev.lelonio.square.airpods.*
import dev.lelonio.square.airpods.runtime.*
import dev.lelonio.square.airpods.runtime.automation.*
import dev.lelonio.square.airpods.runtime.lifecycle.*
import dev.lelonio.square.airpods.runtime.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** PREPARED ONLY. Real controller/admission/gate/store and real paused-main dispatch.
 * Fake media port injected after real admission, without service/socket attach or physical proof.
 * Lifecycle collector deliberately unstarted: the source epoch alone must catch hide/show.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class I5VolumeDispatchTest {
    @Before fun processIdentity() { org.robolectric.shadows.ShadowProcess.setPid(41005) }
    private class CountingContext(base: Context) : ContextWrapper(base) {
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
        override fun commit(bytes: ByteArray): Boolean { this.bytes = bytes; return true }
    }
    private class Port : AirPodsMediaPort {
        override val volume = MutableStateFlow<MediaVolume?>(MediaVolume(8, 10))
        val sets = mutableListOf<Int>()
        var guardedCalls = 0
        var beforeGuard: () -> Unit = {}
        override fun start(session: AutomationSession, pair: PairAddress, settings: HostSettings) = Unit
        override fun updateSettings(settings: HostSettings) = Unit
        override fun setUiVisible(visible: Boolean) = Unit
        override fun onEvent(event: Received<AacpEvent>) = Unit
        override fun setUserVolume(value: Int): Boolean = error("Owner must use guarded overload")
        override fun setUserVolume(value: Int, stillCurrent: () -> Boolean): Boolean {
            guardedCalls++; beforeGuard()
            if (!stillCurrent()) return false
            sets.add(value); return true
        }
        override fun gesture(action: AirPodsMediaGesture): Boolean = error("No transport allowed")
        override fun stop() = Unit
    }
    private class Fixture(plainSource: Boolean = false, functional: Boolean = false) : AutoCloseable {
        val app = RuntimeEnvironment.getApplication()
        val context = CountingContext(app)
        val lifecycle = AirPodsAppVisibility().also { it.attach(app) }
        val activity = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        val visibility: StateFlow<Boolean> = if (plainSource) MutableStateFlow(true) else lifecycle.active
        val pair = PairAddress("AA:BB:CC:DD:EE:05")
        val other = PairAddress("AA:BB:CC:DD:EE:06")
        val facts = Facts(); val disk = Disk(); val port = Port()
        val testPermit = if (functional) AirPodsFunctionalTestPermit.issue { 1L } else null
        val scratch = if (functional) AirPodsRuntimeStore(disk) else null
        private val registry = object : CompanionRegistryFacade {
            override fun hasFeature() = true
            override fun hasObservePermission() = true
            override fun isBonded(address: PairAddress) = true
            override fun ownAssociations() = listOf(OwnedAssociation(5, pair, context.packageName))
            override fun start(registration: ObservationRegistration) = true
            override fun stop(registration: ObservationRegistration) = true
            override fun disassociate(association: OwnedAssociation) = true
        }
        val controller = AirPodsAndroidController(context, visibility, { true }, null,
            testPermit = testPermit, scratchStore = scratch, factsOverride = facts, registryOverride = registry,
            exitHistoryOverride = ExitHistoryProvider { ExitHistory.Available(emptyList()) },
            runtimeDispatcher = StandardTestDispatcher(), persistenceOverride = disk)
        val repository = ReflectionHelpers.getField<AirPodsRepository>(controller, "repository")
        val gate = ReflectionHelpers.getField<AirPodsStartGate>(controller, "gate")
        init {
            ReflectionHelpers.setField(controller, "migrationReady", true)
            controller.store.select(pair).getOrThrow()
            controller.store.recordSetup(pair, PairVerification("A3063", Build.FINGERPRINT, 1)).getOrThrow()
            controller.store.transaction {
                it.copy(pocMigrationCompleted = true, start = StartState(enabled = true, consentGeneration = 7)) to Unit
            }.getOrThrow()
            runBlocking {
                if (functional) assertTrue(controller.beginFunctionalTest() is GateResult.Started)
                else controller.refresh(StartIngress.RECEIVER)
            }
            assertTrue(controller.hasAdmission()); assertEquals(1, context.starts)
            if (functional) {
                // Model completed setup only; this fixture does not certify bootstrap/hardware.
                val runtime = ReflectionHelpers.getField<MutableStateFlow<AndroidRuntimeState>>(controller, "mutableRuntime")
                runtime.value = runtime.value.copy(setupVerified = true)
            }
            ReflectionHelpers.setField(controller, "media", port)
        }
        fun hideShow() { activity.pause(); activity.resume() }
        fun replaceTicket() {
            val old = ReflectionHelpers.getField<ConnectionAdmission>(controller, "ticket")
            assertTrue(gate.stop(SuppressReason.USER_STOP) is GateResult.Stopped)
            gate.explicitConsent(StartIngress.EXPLICIT_RESUME)
            runBlocking { controller.refresh(StartIngress.RECEIVER) }
            assertTrue(controller.hasAdmission())
            assertNotSame(old, ReflectionHelpers.getField<ConnectionAdmission>(controller, "ticket"))
            ReflectionHelpers.setField(controller, "media", port)
        }
        fun deferred(mutate: () -> Unit): Boolean {
            val result = FutureTask { controller.setVolume(5) }
            val worker = Thread(result, "I5-manual-volume-dispatch")
            worker.start()
            try {
                // mainCall posts before entering its bounded latch wait. No production test hook.
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500)
                while (!result.isDone && worker.state != Thread.State.TIMED_WAITING && System.nanoTime() < deadline)
                    Thread.yield()
                assertFalse("Action must be queued, not run inline", result.isDone)
                assertEquals(Thread.State.TIMED_WAITING, worker.state)
                assertTrue(worker.stackTrace.any { it.methodName == "mainCall" })
                mutate()
                shadowOf(Looper.getMainLooper()).idle()
                return result.get(500, TimeUnit.MILLISECONDS)
            } finally {
                shadowOf(Looper.getMainLooper()).idle()
                worker.join(1_000)
                assertFalse("No residual writer thread", worker.isAlive)
            }
        }
        override fun close() {
            // Fake port owns nothing. Detach injection before off-main production cleanup.
            ReflectionHelpers.setField(controller, "media", null)
            runBlocking { controller.stopControls() }
            context.unregisterReceiver(ReflectionHelpers.getField(controller, "receiver"))
            ReflectionHelpers.getField<CoroutineScope>(controller, "processScope").cancel()
            activity.pause().stop().destroy(); lifecycle.detach()
        }
    }
    @Test fun normalForegroundMainDispatchAndQueuedDispatchBothUseGuardedPort() {
        Fixture().use { f ->
            assertTrue(f.controller.setVolume(4)); assertTrue(f.deferred {})
            assertEquals(listOf(4, 5), f.port.sets); assertEquals(2, f.port.guardedCalls)
            assertEquals(1, f.context.starts)
        }
    }
    @Test fun hideShowBeforeMainDispatchRejectsDespiteFinalTrueAndNoCollectorDelivery() {
        Fixture().use { f ->
            assertFalse(f.deferred { f.hideShow() })
            assertTrue(f.visibility.value); assertTrue(f.port.sets.isEmpty())
            assertEquals(0, f.port.guardedCalls); assertEquals(1, f.context.starts)
        }
    }
    @Test fun initiallyHiddenActionIsRejectedBeforePostingEvenWithAdmittedTicket() {
        Fixture().use { f ->
            f.activity.pause()
            assertFalse(f.visibility.value); assertTrue(f.controller.hasAdmission())
            assertFalse(f.controller.setVolume(5)); assertTrue(f.port.sets.isEmpty())
            assertEquals(0, f.port.guardedCalls)
        }
    }
    @Test fun oldAdmissionReferenceCannotDispatchIntoNewWritableTicket() {
        Fixture().use { f ->
            assertFalse(f.deferred { f.replaceTicket() })
            assertTrue(f.controller.hasAdmission()); assertTrue(f.port.sets.isEmpty())
        }
    }
    @Test fun changedRepositoryGenerationRejectsQueuedAction() {
        Fixture().use { f ->
            val generation = f.controller.state.value.generation
            assertFalse(f.deferred { f.repository.close() })
            assertTrue(f.controller.state.value.generation > generation)
            assertTrue(f.port.sets.isEmpty())
        }
    }
    @Test fun changedPairPermissionConsentOrStopStateRejectsQueuedAction() {
        for (change in 0..3) Fixture().use { f ->
            assertFalse(f.deferred {
                when (change) {
                    0 -> f.controller.store.select(f.other).getOrThrow()
                    1 -> f.facts.permission = false
                    2 -> f.controller.store.transaction { it.copy(start = it.start.copy(consentGeneration = 8)) to Unit }.getOrThrow()
                    3 -> f.controller.store.transaction { it.copy(start = it.start.copy(suppressUntilExplicitResume = true,
                        suppressReason = SuppressReason.USER_STOP)) to Unit }.getOrThrow()
                }
            })
            assertTrue(f.port.sets.isEmpty()); assertEquals(0, f.port.guardedCalls)
        }
    }
    @Test fun permissionReturnNotificationEpochRejectsEvenWhenAuthorizationRemainsCurrent() {
        Fixture().use { f ->
            assertFalse(f.deferred {
                // Permissions return is passive. With no fake media cleanup, no mainCall deadlock.
                ReflectionHelpers.setField(f.controller, "media", null)
                runBlocking { f.controller.permissionsChanged() }
                ReflectionHelpers.setField(f.controller, "media", f.port)
            })
            assertTrue(f.controller.hasAdmission()); assertTrue(f.port.sets.isEmpty())
            assertEquals(1, f.context.starts)
        }
    }
    @Test fun guardRechecksAtPortExecutionAfterControllerPrecheck() {
        Fixture().use { f ->
            f.port.beforeGuard = { f.hideShow() }
            assertFalse(f.controller.setVolume(5)); assertTrue(f.port.sets.isEmpty())
            assertEquals(1, f.port.guardedCalls)
        }
    }
    @Test fun plainBooleanFlowConservativelyRejectsManualVolumeWithoutPretendingAnEpoch() {
        Fixture(plainSource = true).use { f ->
            assertTrue(f.controller.hasAdmission()); assertFalse(f.controller.setVolume(5))
            assertTrue(f.port.sets.isEmpty()); assertEquals(0, f.port.guardedCalls)
        }
    }
    @Test fun functionalPermitStillRequiresFencedForegroundAndRejectsRevocation() {
        Fixture(functional = true).use { f ->
            assertTrue(f.controller.isFunctionalTest); assertTrue(f.controller.setVolume(4))
            assertFalse(f.deferred { f.testPermit!!.revoke() })
            assertEquals(listOf(4), f.port.sets)
        }
    }
}
