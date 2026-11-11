// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.android

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import dev.lelonio.square.airpods.runtime.AirPodsForegroundState
import dev.lelonio.square.airpods.runtime.lifecycle.*
import dev.lelonio.square.airpods.runtime.storage.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Retains the independent B1 three-case counterexample and adds cancellation controls.
 * Original probe and red XML are preserved under build/final-review-20261111 and B1-probe-02.
 * Models only Android's asynchronous profile callback; owner/gate/store are production code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
class AirPodsExplicitProfileAdmissionTest {
    @Before fun processIdentity() { org.robolectric.shadows.ShadowProcess.setPid(41077) }

    private class SchedulingContext(base: Context) : ContextWrapper(base) {
        val starts = AtomicInteger()
        override fun checkSelfPermission(permission: String) = PackageManager.PERMISSION_GRANTED
        override fun startForegroundService(service: Intent): ComponentName {
            starts.incrementAndGet(); return ComponentName(this, AirPodsControlService::class.java)
        }
    }

    /** Models AndroidBluetoothFacts: prepare() only REQUESTS the proxy; it connects later. */
    private class ColdProfileFacts(warm: Boolean) : BluetoothRuntimeFacts {
        override val adapter: BluetoothAdapter? = null
        @Volatile var proxyReady = warm
        val prepares = AtomicInteger()
        val requests = AtomicInteger()
        val waiting = CopyOnWriteArrayList<() -> Unit>()
        @Volatile var permission = true
        @Volatile var bondFact = BondFact.BONDED
        @Volatile var resolvedConnection = ProfileConnection.CONNECTED
        var refused = false
        override fun permitted() = permission
        override fun bond(address: PairAddress) = bondFact
        // The AirPods ARE connected; only this process's proxy is not resolved yet.
        override fun connection(address: PairAddress) =
            if (proxyReady) resolvedConnection else ProfileConnection.UNRESOLVED
        override fun prepare() { prepares.incrementAndGet() }
        override fun whenProfileReady(ready: () -> Unit): AutoCloseable? {
            requests.incrementAndGet()
            if (refused) return null
            if (proxyReady) { ready(); return AutoCloseable {} }
            waiting.add(ready); return AutoCloseable { waiting.remove(ready) }
        }
        fun deliverProxy() { proxyReady = true; waiting.toList().also { waiting.clear() }.forEach { it() } }
        override fun audioSettingsIntent() = Intent()
    }

    private class Disk : RuntimePersistence {
        private var bytes: ByteArray? = null
        override fun read() = bytes?.copyOf()
        override fun commit(bytes: ByteArray): Boolean { this.bytes = bytes.copyOf(); return true }
    }

    private class Fixture(warm: Boolean) : AutoCloseable {
        val context = SchedulingContext(RuntimeEnvironment.getApplication())
        val visible = AirPodsForegroundState().also { it.value = true }
        val pair = PairAddress("AA:BB:CC:DD:EE:77")
        val facts = ColdProfileFacts(warm)
        val registry = object : CompanionRegistryFacade {
            override fun hasFeature() = true
            override fun hasObservePermission() = true
            override fun isBonded(address: PairAddress) = true
            override fun ownAssociations() = emptyList<OwnedAssociation>() // zero Fluidify associations
            override fun start(registration: ObservationRegistration) = true
            override fun stop(registration: ObservationRegistration) = true
            override fun disassociate(association: OwnedAssociation) = true
        }
        val controller = AirPodsAndroidController(context, visible, { true },
            { error("FGS must not attach in this fixture") },
            factsOverride = facts, registryOverride = registry,
            exitHistoryOverride = ExitHistoryProvider { ExitHistory.Available(emptyList()) },
            runtimeDispatcher = Dispatchers.Unconfined, persistenceOverride = Disk())
        init {
            ReflectionHelpers.setField(controller, "migrationReady", true)
            controller.store.select(pair).getOrThrow()
            controller.store.recordSetup(pair, PairVerification("A3063", Build.FINGERPRINT, 1)).getOrThrow()
            // Opt-in exists, suppressed by an earlier transport failure (e.g. case close).
            controller.store.transaction {
                it.copy(pocMigrationCompleted = true, start = StartState(enabled = true, consentGeneration = 7,
                    suppressUntilExplicitResume = true, suppressReason = SuppressReason.START_FAILED)) to Unit
            }.getOrThrow()
        }
        override fun close() {
            runBlocking { controller.stopControls() }
            context.unregisterReceiver(ReflectionHelpers.getField(controller, "receiver"))
            ReflectionHelpers.getField<CoroutineScope>(controller, "processScope").cancel()
        }
    }

    /** One explicit tap while the proxy resolves 150 ms later, as on a real cold process. */
    private fun tapResume(f: Fixture): GateResult = runBlocking {
        val tap = async(Dispatchers.IO) { f.controller.enableOrResume() }
        delay(150); f.facts.deliverProxy()
        withTimeout(10_000) { tap.await() }
    }

    @Test fun firstExplicitResumeWithColdRuntimeProfileAdmitsAfterBoundedWait() = Fixture(warm = false).use { f ->
        val result = tapResume(f)
        assertTrue("B1: primo Riattiva con proxy runtime freddo ha dato $result (UI: cuffie connesse)",
            result is GateResult.Started)
        assertEquals(1, f.context.starts.get())
    }

    @Test fun deniedResumeDiagnosticNamesTheAdmissionCauseNotTheObservation() = Fixture(warm = false).use { f ->
        val result = tapResume(f)
        val failure = f.controller.runtime.value.failure
        if (result is GateResult.Denied) assertNotEquals(
            "B1: rifiuto ${result.reason} esposto in diagnostica come $failure",
            "COMPANION_ASSOCIATION_MISMATCH", failure)
    }

    @Test fun control_warmRuntimeProfileResumesWithoutAnyAssociation() = Fixture(warm = true).use { f ->
        val result = runBlocking { f.controller.enableOrResume() }
        assertTrue("controllo: con proxy pronto il foreground deve ammettere senza associazione: $result",
            result is GateResult.Started)
        assertEquals(1, f.context.starts.get())
    }

    private suspend fun awaitProfile(f: Fixture) = withTimeout(10_000) {
        while (f.facts.waiting.isEmpty()) delay(5)
    }

    @Test fun stopWhileTheProfileIsColdRetiresEvenAnAlreadyCopiedCallback() = Fixture(false).use { f -> runBlocking {
        val tap = async(Dispatchers.IO) { f.controller.enableOrResume() }
        awaitProfile(f)
        val late = f.facts.waiting.single()
        f.controller.stopControls()
        f.facts.deliverProxy(); late()
        assertTrue(tap.await() is GateResult.Denied)
        assertEquals(0, f.context.starts.get())
        assertTrue(f.controller.store.snapshot().getOrThrow().start.suppressUntilExplicitResume)
    } }

    @Test fun hideAndShowCannotReviveTheOldTap() = Fixture(false).use { f -> runBlocking {
        val tap = async(Dispatchers.IO) { f.controller.enableOrResume() }
        awaitProfile(f)
        f.visible.value = false; f.visible.value = true
        f.facts.deliverProxy()
        assertTrue(tap.await() is GateResult.Denied)
        assertEquals(0, f.context.starts.get())
    } }

    @Test fun anotherPairCannotBorrowTheProfileCallback() = Fixture(false).use { f -> runBlocking {
        val tap = async(Dispatchers.IO) { f.controller.enableOrResume() }
        awaitProfile(f)
        f.controller.selectPair(PairAddress("AA:BB:CC:DD:EE:88")).getOrThrow()
        f.facts.deliverProxy()
        assertTrue(tap.await() is GateResult.Denied)
        assertEquals(0, f.context.starts.get())
    } }

    @Test fun lostPermissionAtCompletionCannotStartTheOwner() = Fixture(false).use { f -> runBlocking {
        val tap = async(Dispatchers.IO) { f.controller.enableOrResume() }
        awaitProfile(f)
        f.facts.permission = false
        f.facts.deliverProxy()
        assertEquals(GateResult.Denied(GateReason.PERMISSION_DENIED), tap.await())
        assertEquals(0, f.context.starts.get())
        assertEquals("ADMISSION_PERMISSION_DENIED", f.controller.runtime.value.failure)
    } }

    @Test fun profileCompletionStillHasToReportARealConnection() = Fixture(false).use { f -> runBlocking {
        val tap = async(Dispatchers.IO) { f.controller.enableOrResume() }
        awaitProfile(f)
        f.facts.resolvedConnection = ProfileConnection.DISCONNECTED
        f.facts.deliverProxy()
        assertEquals(GateResult.Denied(GateReason.NOT_CONNECTED), tap.await())
        assertEquals(0, f.context.starts.get())
        assertEquals("ADMISSION_NOT_CONNECTED", f.controller.runtime.value.failure)
    } }

    @Test fun refusedRequestIsTerminalAndNamesItsActualCause() = Fixture(false).use { f -> runBlocking {
        f.facts.refused = true
        assertEquals(GateResult.Denied(GateReason.NOT_CONNECTED), f.controller.enableOrResume())
        assertEquals("ADMISSION_NOT_CONNECTED", f.controller.runtime.value.failure)
        f.facts.deliverProxy()
        assertEquals(0, f.context.starts.get())
        assertEquals(1, f.facts.requests.get())
    } }

    @Test fun timeoutClosesTheCallbackAndDoesNotRetry() = Fixture(false).use { f -> runBlocking {
        assertEquals(GateResult.Denied(GateReason.NOT_CONNECTED), f.controller.enableOrResume())
        assertTrue(f.facts.waiting.isEmpty())
        f.facts.deliverProxy()
        assertEquals(0, f.context.starts.get())
        assertEquals(1, f.facts.requests.get())
    } }

    @Test fun cancellationClosesTheWaitWithoutAnyAcquisition() = Fixture(false).use { f -> runBlocking {
        val tap = async(Dispatchers.IO) { f.controller.enableOrResume() }
        awaitProfile(f)
        tap.cancelAndJoin()
        assertTrue(f.facts.waiting.isEmpty())
        f.facts.deliverProxy()
        assertEquals(0, f.context.starts.get())
    } }

    @Test fun verificationAlsoWaitsForItsOwnColdProfile() = Fixture(false).use { f -> runBlocking {
        f.controller.setSetupVisible(true)
        val tap = async(Dispatchers.IO) { f.controller.beginVerification() }
        awaitProfile(f)
        f.facts.deliverProxy()
        assertTrue(tap.await() is GateResult.Started)
        assertEquals(1, f.context.starts.get())
    } }

    @Test fun leavingVerificationRetiresItsWaitEvenIfThePageReopens() = Fixture(false).use { f -> runBlocking {
        f.controller.setSetupVisible(true)
        val tap = async(Dispatchers.IO) { f.controller.beginVerification() }
        awaitProfile(f)
        f.controller.setSetupVisible(false); f.controller.setSetupVisible(true)
        f.facts.deliverProxy()
        assertTrue(tap.await() is GateResult.Denied)
        assertEquals(0, f.context.starts.get())
    } }
}
