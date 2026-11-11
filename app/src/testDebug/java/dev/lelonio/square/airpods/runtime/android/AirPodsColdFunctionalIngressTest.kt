// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.android

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.*
import android.content.pm.PackageManager
import dev.lelonio.square.airpods.AirPodsTransportOwnership
import dev.lelonio.square.airpods.runtime.AirPodsForegroundState
import dev.lelonio.square.airpods.runtime.lifecycle.*
import dev.lelonio.square.airpods.runtime.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowProcess
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** PREPARED, NOT EXECUTED. Real controller/C4/lease; fake Android facts and FGS scheduling.
 * FGS is never attached, so no socket/native factory, Bluetooth request or hardware is used.
 * No product conformance, companion opt-in, verification or automation is granted. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
class AirPodsColdFunctionalIngressTest {
    @Before fun identity() { ShadowProcess.setPid(41018) }

    private class TestContext(base: Context) : ContextWrapper(base) {
        val starts = AtomicInteger()
        override fun checkSelfPermission(permission: String) = PackageManager.PERMISSION_GRANTED
        override fun startForegroundService(service: Intent): ComponentName {
            starts.incrementAndGet()
            return ComponentName(this, AirPodsControlService::class.java)
        }
    }
    private class Facts : BluetoothRuntimeFacts {
        override val adapter: BluetoothAdapter? = null
        @Volatile var permission = true
        @Volatile var bond = BondFact.BONDED
        @Volatile var profile = ProfileConnection.UNRESOLVED
        var accepted = true
        var inlineCallback = false
        val entered = CountDownLatch(1)
        val requests = AtomicInteger()
        val prepares = AtomicInteger()
        val closes = AtomicInteger()
        val callbacks = CopyOnWriteArrayList<() -> Unit>()
        override fun permitted() = permission
        override fun bond(address: PairAddress) = bond
        override fun connection(address: PairAddress) = profile
        override fun prepare() { prepares.incrementAndGet() }
        override fun audioSettingsIntent() = Intent()
        override fun whenProfileReady(ready: () -> Unit): AutoCloseable? {
            requests.incrementAndGet()
            callbacks.add(ready) // deliberately retain after close/refusal to model a captured late callback
            if (inlineCallback) { profile = ProfileConnection.CONNECTED; ready() }
            entered.countDown()
            return if (accepted) AutoCloseable { closes.incrementAndGet() } else null
        }
        fun deliver(value: ProfileConnection = ProfileConnection.CONNECTED) {
            profile = value
            callbacks.forEach { it() }
        }
    }
    private class Registry : CompanionRegistryFacade {
        var observations = 0
        override fun hasFeature() = false
        override fun hasObservePermission() = false
        override fun isBonded(address: PairAddress) = true
        override fun ownAssociations() = emptyList<OwnedAssociation>()
        override fun start(registration: ObservationRegistration): Boolean { observations++; return false }
        override fun stop(registration: ObservationRegistration) = true
        override fun disassociate(association: OwnedAssociation) = false
    }
    private class Fixture : AutoCloseable {
        val context = TestContext(RuntimeEnvironment.getApplication())
        val pair = PairAddress("10:20:30:40:50:60") // synthetic identities, never incident identifiers
        val other = PairAddress("10:20:30:40:50:61")
        val visible = AirPodsForegroundState().apply { value = true }
        val facts = Facts()
        val registry = Registry()
        var now = 1L
        val permit = AirPodsFunctionalTestPermit.issue { now }
        @Volatile var history: ExitHistory = ExitHistory.Available(emptyList())
        @Volatile var historyHook: (() -> Unit)? = null
        val factoryCalls = AtomicInteger()
        val store = AirPodsRuntimeStore(object : RuntimePersistence {
            var bytes: ByteArray? = null
            override fun read() = bytes?.clone()
            override fun commit(bytes: ByteArray): Boolean { this.bytes = bytes.clone(); return true }
        })
        val owner = AirPodsAndroidController(context, visible, { false },
            { factoryCalls.incrementAndGet(); error("FGS must not attach in this fixture") }, permit, store,
            factsOverride = facts, registryOverride = registry,
            // Keep the visibility collector unstarted: hide/show must be caught by the source fence.
            exitHistoryOverride = ExitHistoryProvider {
                historyHook?.also { historyHook = null }?.invoke()
                history
            }, runtimeDispatcher = StandardTestDispatcher())
        init { runBlocking { owner.selectPair(pair).getOrThrow() } }
        fun data() = store.snapshot().getOrThrow()
        fun waitForRequest() { assertTrue("readiness was not requested", facts.entered.await(3, TimeUnit.SECONDS)) }
        fun noAcquisition() {
            assertEquals(0, context.starts.get())
            assertEquals(0, factoryCalls.get())
            assertNull(data().start.marker)
            assertNull(ReflectionHelpers.getField<Any?>(owner, "transportLease"))
            assertNull(AirPodsServiceOwnerRegistry.resolve())
            val probe = AirPodsTransportOwnership.process.reserve(AirPodsTransportOwnership.Owner.PRODUCTION)
            assertNotNull("pending/denied test must not reserve a transport lease", probe)
            probe?.close()
        }
        override fun close() { runBlocking { owner.disposeFunctionalTest() } }
    }

    @Test fun firstColdTestWaitsThenAdmitsOnceWithoutAnotherActionOrCompanionOptIn() = runBlocking {
        Fixture().use { f ->
            val result = async { f.owner.beginFunctionalTest() }
            // beginFunctionalTest dispatches to IO; let async enter before blocking on the fixture latch.
            yield(); f.waitForRequest()
            assertFalse(result.isCompleted); f.noAcquisition()
            val consent = f.data().start.consentGeneration
            assertFalse(f.data().pairs.getValue(f.pair).host.automationsEnabled)
            assertFalse(f.data().pairs.getValue(f.pair).host.fluidifyBackgroundEnabled)
            f.facts.deliver(); f.facts.deliver()
            assertTrue(withTimeout(3_000) { result.await() } is GateResult.Started)
            f.facts.deliver()
            assertEquals(1, f.context.starts.get()); assertTrue(f.owner.hasAdmission())
            assertEquals(consent, f.data().start.consentGeneration)
            assertEquals(1, f.facts.requests.get()); assertEquals(1, f.facts.closes.get())
            assertEquals(0, f.facts.prepares.get()); assertEquals(0, f.factoryCalls.get())
            assertEquals(0, f.registry.observations); assertNull(f.data().pairs.getValue(f.pair).verification)
            assertFalse(f.owner.runtime.value.productConformance)
        }
    }
    @Test fun readyProfileUsesTheExistingGateWithoutSubscribing() = runBlocking {
        Fixture().use { f ->
            f.facts.profile = ProfileConnection.CONNECTED
            assertTrue(f.owner.beginFunctionalTest() is GateResult.Started)
            assertEquals(0, f.facts.requests.get()); assertEquals(1, f.context.starts.get())
        }
    }
    @Test fun admittedTestRetainsAuthorityAcrossNewForegroundHandoffAndExplicitBackground() = runBlocking {
        Fixture().use { f ->
            f.facts.profile = ProfileConnection.CONNECTED
            assertTrue(f.owner.beginFunctionalTest() is GateResult.Started)
            val consent = f.data().start.consentGeneration
            assertNull(ReflectionHelpers.getField<Any?>(f.owner, "pendingFunctionalStart"))
            f.visible.invalidateForeground()
            assertTrue(f.owner.hasAdmission()); assertTrue(f.owner.functionalIngressCurrent())
            // Exercise the real permit/current-ticket contract without simulating setup RX/UI success.
            assertTrue(f.permit.handoff(f.visible.value))
            f.visible.invalidateForeground(); f.visible.value = false
            assertTrue(f.owner.hasAdmission()); assertTrue(f.owner.functionalIngressCurrent())
            f.visible.value = true
            assertTrue(f.permit.armBackground(AirPodsFunctionalTestPermit.MIN_WINDOW, f.visible.value))
            f.visible.value = false
            assertTrue(f.owner.hasAdmission()); assertTrue(f.owner.functionalIngressCurrent())
            assertEquals(consent, f.data().start.consentGeneration)
            assertEquals(1, f.context.starts.get()); assertEquals(0, f.factoryCalls.get())
        }
    }
    @Test fun stopSelectionRevocationForgetAndHideShowRetireOriginalIngress() = runBlocking {
        val changes: List<suspend (Fixture) -> Unit> = listOf(
            { it.owner.stopControls(); Unit },
            { it.owner.selectPair(it.other).getOrThrow() },
            { it.permit.revoke() },
            { it.owner.forget(); Unit },
            { it.visible.value = false; it.visible.value = true },
            { it.visible.invalidateForeground() },
            { it.now += AirPodsFunctionalTestPermit.MAX_WINDOW },
            { it.owner.disposeFunctionalTest() },
            { it.store.transaction { data -> data.copy(start = data.start.copy(consentGeneration = data.start.consentGeneration + 1)) to Unit }.getOrThrow() },
        )
        changes.forEach { change -> Fixture().use { f ->
            val result = async { f.owner.beginFunctionalTest() }
            yield(); f.waitForRequest(); change(f)
            val consentAfterChange = f.data().start.consentGeneration
            f.facts.deliver(); f.facts.deliver()
            assertTrue(withTimeout(3_000) { result.await() } is GateResult.Denied)
            f.noAcquisition()
            assertEquals(consentAfterChange, f.data().start.consentGeneration)
            assertEquals(1, f.facts.closes.get())
        } }
    }
    @Test fun stopCancelsWaitEvenIfNoProfileCallbackEverArrives() = runBlocking {
        Fixture().use { f ->
            val result = async { f.owner.beginFunctionalTest() }
            yield(); f.waitForRequest(); f.owner.stopControls()
            assertTrue(withTimeout(1_000) { result.await() } is GateResult.Denied)
            f.noAcquisition(); assertEquals(1, f.facts.closes.get())
        }
    }
    @Test fun readinessRechecksPermissionBondAndActualConnectionBeforeAcquisition() = runBlocking {
        listOf(GateReason.PERMISSION_DENIED, GateReason.NOT_BONDED, GateReason.NOT_CONNECTED).forEach { reason ->
            Fixture().use { f ->
                val result = async { f.owner.beginFunctionalTest() }
                yield(); f.waitForRequest()
                when (reason) {
                    GateReason.PERMISSION_DENIED -> f.facts.permission = false
                    GateReason.NOT_BONDED -> f.facts.bond = BondFact.NONE
                    else -> Unit
                }
                f.facts.deliver(if (reason == GateReason.NOT_CONNECTED) ProfileConnection.DISCONNECTED else ProfileConnection.CONNECTED)
                assertEquals(GateResult.Denied(reason), withTimeout(3_000) { result.await() })
                f.noAcquisition()
            }
        }
    }
    @Test fun permissionCallbackCancelsPendingStartWithoutAcquiring() = runBlocking {
        Fixture().use { f ->
            val result = async { f.owner.beginFunctionalTest() }
            yield(); f.waitForRequest(); f.facts.permission = false; f.owner.permissionsChanged()
            assertTrue(withTimeout(1_000) { result.await() } is GateResult.Denied)
            f.facts.deliver(); f.noAcquisition()
        }
    }
    @Test fun newUserRequestedExitAndHistoryFailureAreReconciledAfterReadiness() = runBlocking {
        listOf(false, true).forEach { failed -> Fixture().use { f ->
            val result = async { f.owner.beginFunctionalTest() }
            yield(); f.waitForRequest()
            val consent = f.data().start.consentGeneration
            f.history = if (failed) ExitHistory.QueryFailed else ExitHistory.Available(listOf(
                OwnProcessExit(ExitKey(System.currentTimeMillis() + 60_000, 41018, Application.getProcessName(), 10), ExitCause.USER_REQUESTED)))
            f.facts.deliver()
            assertEquals(GateResult.Denied(if (failed) GateReason.HISTORY_FAILED else GateReason.SUPPRESSED),
                withTimeout(3_000) { result.await() })
            f.noAcquisition(); assertEquals(consent, f.data().start.consentGeneration)
            if (!failed) assertTrue(f.data().start.suppressUntilExplicitResume)
        } }
    }
    @Test fun hideShowOrCancellationWhileC4QueryIsBlockedStillCannotAcquire() = runBlocking {
        listOf(false, true).forEach { cancel -> Fixture().use { f ->
            val result = async { f.owner.beginFunctionalTest() }
            yield(); f.waitForRequest()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.historyHook = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
            try {
                f.facts.deliver()
                assertTrue("final C4 query did not enter", entered.await(3, TimeUnit.SECONDS))
                if (cancel) result.cancel() else { f.visible.value = false; f.visible.value = true }
            } finally { release.countDown() }
            if (cancel) {
                withTimeout(3_000) { result.join() }; assertTrue(result.isCancelled)
            } else assertTrue(withTimeout(3_000) { result.await() } is GateResult.Denied)
            f.noAcquisition(); assertEquals(1, f.facts.closes.get())
        } }
    }
    @Test fun refusedRequestAndEvenInlineCallbackCannotAdmitOrRetry() = runBlocking {
        listOf(false, true).forEach { inline -> Fixture().use { f ->
            f.facts.accepted = false; f.facts.inlineCallback = inline
            assertEquals(GateResult.Denied(GateReason.NOT_CONNECTED), withTimeout(1_000) { f.owner.beginFunctionalTest() })
            val consent = f.data().start.consentGeneration
            f.facts.deliver(); f.noAcquisition()
            assertEquals(1, f.facts.requests.get()); assertEquals(0, f.facts.prepares.get())
            assertEquals(consent, f.data().start.consentGeneration)
        } }
    }
    @Test fun acceptedInlineCallbackClosesSubscriptionAndAdmitsOnce() = runBlocking {
        Fixture().use { f ->
            f.facts.inlineCallback = true
            assertTrue(f.owner.beginFunctionalTest() is GateResult.Started)
            assertEquals(1, f.context.starts.get()); assertEquals(1, f.facts.closes.get())
        }
    }
    @Test fun onlyANewExplicitActionCanRetryAndOldCallbackCannotWakeIt() = runBlocking {
        Fixture().use { f ->
            f.facts.accepted = false
            assertEquals(GateResult.Denied(GateReason.NOT_CONNECTED), f.owner.beginFunctionalTest())
            val refusedCallback = f.facts.callbacks.single()
            f.noAcquisition()
            f.facts.accepted = true
            val result = async { f.owner.beginFunctionalTest() }
            withTimeout(3_000) { while (f.facts.requests.get() != 2) delay(1) }
            val consent = f.data().start.consentGeneration
            refusedCallback()
            assertFalse(result.isCompleted); f.noAcquisition()
            f.facts.deliver()
            assertTrue(withTimeout(3_000) { result.await() } is GateResult.Started)
            assertEquals(1, f.context.starts.get()); assertEquals(2, f.facts.requests.get())
            assertEquals(consent, f.data().start.consentGeneration)
        }
    }
    @Test fun terminalUnresolvedNotificationCannotSelfRequestOrAcquire() = runBlocking {
        Fixture().use { f ->
            val result = async { f.owner.beginFunctionalTest() }
            yield(); f.waitForRequest(); f.facts.deliver(ProfileConnection.UNRESOLVED)
            assertEquals(GateResult.Denied(GateReason.NOT_CONNECTED), withTimeout(3_000) { result.await() })
            f.facts.deliver(); f.noAcquisition()
            assertEquals(1, f.facts.requests.get()); assertEquals(0, f.facts.prepares.get())
        }
    }
    @Test fun timeoutUnsubscribesAndLateCallbackCannotStartOrCreateConsent() = runBlocking {
        Fixture().use { f ->
            val result = async { f.owner.beginFunctionalTest() }
            yield(); f.waitForRequest()
            val consent = f.data().start.consentGeneration
            assertEquals(GateResult.Denied(GateReason.NOT_CONNECTED), withTimeout(8_000) { result.await() })
            assertEquals(1, f.facts.closes.get())
            f.facts.deliver(); f.noAcquisition()
            assertEquals(consent, f.data().start.consentGeneration)
            assertEquals(1, f.facts.requests.get())
        }
    }
    @Test fun callerCancellationUnsubscribesAndLateCallbackHasNoAuthority() = runBlocking {
        Fixture().use { f ->
            val result = async { f.owner.beginFunctionalTest() }
            yield(); f.waitForRequest(); result.cancelAndJoin()
            assertTrue(result.isCancelled); assertEquals(1, f.facts.closes.get())
            val consent = f.data().start.consentGeneration
            f.facts.deliver(); f.noAcquisition()
            assertEquals(consent, f.data().start.consentGeneration)
        }
    }
}
