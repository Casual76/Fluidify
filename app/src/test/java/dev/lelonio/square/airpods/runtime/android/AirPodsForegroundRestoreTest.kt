// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.android

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import dev.lelonio.square.airpods.AacpStage
import dev.lelonio.square.airpods.AirPodsTransportOwnership
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

/** SOURCE ONLY: prepared, not compiled or run. Real controller, gate, store, lifetime and
 * process lease; fake Bluetooth/profile/history/persistence/FGS scheduling. No service attach,
 * media port, transport, radio, OEM lifecycle or hardware conformance proof. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
class AirPodsForegroundRestoreTest {
    @Before fun processIdentity() { org.robolectric.shadows.ShadowProcess.setPid(41039) }

    private class SchedulingContext(base: Context) : ContextWrapper(base) {
        val starts = AtomicInteger()
        var beforeStart: (() -> Unit)? = null
        override fun checkSelfPermission(permission: String) = PackageManager.PERMISSION_GRANTED
        override fun startForegroundService(service: Intent): ComponentName {
            beforeStart?.invoke()
            starts.incrementAndGet()
            return ComponentName(this, AirPodsControlService::class.java)
        }
    }
    private class Facts : BluetoothRuntimeFacts {
        override val adapter: BluetoothAdapter? = null
        @Volatile var permission = true
        @Volatile var bond = BondFact.BONDED
        @Volatile var profile = ProfileConnection.CONNECTED
        @Volatile var accepted = true
        @Volatile var inlineCallback = false
        val callbacks = CopyOnWriteArrayList<() -> Unit>()
        val closed = AtomicInteger()
        override fun permitted() = permission
        override fun bond(address: PairAddress) = bond
        override fun connection(address: PairAddress) = profile
        override fun prepare() = Unit
        override fun whenProfileReady(ready: () -> Unit): AutoCloseable? {
            callbacks.add(ready)
            if (inlineCallback) { profile = ProfileConnection.CONNECTED; ready() }
            return if (accepted) AutoCloseable { closed.incrementAndGet() } else null
        }
        override fun audioSettingsIntent() = Intent()
    }
    private class Disk : RuntimePersistence {
        private var bytes: ByteArray? = null
        @Volatile var failCommit = false
        var nextMarkerCommit: (() -> Unit)? = null
        override fun read() = bytes?.copyOf()
        override fun commit(bytes: ByteArray): Boolean {
            if (failCommit) return false
            if (AirPodsRuntimeCodec.decode(bytes).start.marker?.clean == false) {
                nextMarkerCommit?.also { nextMarkerCommit = null }?.invoke()
            }
            this.bytes = bytes.copyOf()
            return true
        }
    }
    private class Fixture : AutoCloseable {
        val context = SchedulingContext(RuntimeEnvironment.getApplication())
        val visible = AirPodsForegroundState().also { it.value = true }
        val pair = PairAddress("AA:BB:CC:DD:EE:39")
        val facts = Facts()
        val disk = Disk()
        @Volatile var entitled = true
        @Volatile var history: ExitHistory = ExitHistory.Available(emptyList())
        val historyQueries = AtomicInteger()
        val mediaFactories = AtomicInteger()
        val observations = AtomicInteger()
        val registry = object : CompanionRegistryFacade {
            override fun hasFeature() = true
            override fun hasObservePermission() = true
            override fun isBonded(address: PairAddress) = facts.bonded(address)
            override fun ownAssociations() = listOf(OwnedAssociation(39, pair, context.packageName))
            override fun start(registration: ObservationRegistration): Boolean { observations.incrementAndGet(); return true }
            override fun stop(registration: ObservationRegistration) = true
            override fun disassociate(association: OwnedAssociation) = true
        }
        val controller = AirPodsAndroidController(context, visible, { entitled },
            { mediaFactories.incrementAndGet(); error("FGS must not attach in this fixture") },
            factsOverride = facts, registryOverride = registry,
            exitHistoryOverride = ExitHistoryProvider { historyQueries.incrementAndGet(); history },
            runtimeDispatcher = Dispatchers.Unconfined, persistenceOverride = disk)
        init {
            // Completed migration only, without creating an admission/ticket/lease by reflection.
            ReflectionHelpers.setField(controller, "migrationReady", true)
            controller.store.select(pair).getOrThrow()
            controller.store.recordSetup(pair, PairVerification("A3063", Build.FINGERPRINT, 1)).getOrThrow()
            controller.store.transaction {
                it.copy(pocMigrationCompleted = true, start = StartState(enabled = true, consentGeneration = 7)) to Unit
            }.getOrThrow()
            context.beforeStart = {
                assertTrue(historyQueries.get() > 0)
                val start = data().start
                assertEquals(7L, start.consentGeneration)
                assertFalse(start.suppressUntilExplicitResume)
                assertNotNull(start.marker)
                assertFalse(start.marker!!.clean)
            }
        }
        fun data() = controller.store.snapshot().getOrThrow()
        fun start(change: (StartState) -> StartState) {
            controller.store.transaction { it.copy(start = change(it.start)) to Unit }.getOrThrow()
        }
        fun oldMarker() = ProcessMarker("previous-process", 39, Application.getProcessName(), 100)
        fun userExit() = OwnProcessExit(ExitKey(200, 39, Application.getProcessName(), 10), ExitCause.USER_REQUESTED)
        fun assertNoAcquisition() {
            assertEquals(0, context.starts.get())
            assertFalse(controller.hasAdmission())
            assertNull(ReflectionHelpers.getField<Any?>(controller, "ticket"))
            assertNull(ReflectionHelpers.getField<Any?>(controller, "transportLease"))
            assertEquals(0, mediaFactories.get())
            assertEquals(0, observations.get())
            assertEquals(AacpStage.CLOSED, controller.state.value.stage)
            assertTrue(controller.state.value.pending.isEmpty())
            assertTrue(controller.state.value.properties.isEmpty())
        }
        override fun close() {
            disk.failCommit = false
            context.beforeStart = null
            runBlocking { controller.stopControls() }
            context.unregisterReceiver(ReflectionHelpers.getField(controller, "receiver"))
            ReflectionHelpers.getField<CoroutineScope>(controller, "processScope").cancel()
        }
    }
    private suspend fun awaitSubscription(f: Fixture, count: Int = 1) {
        withTimeout(2_000) { while (f.facts.callbacks.size < count) delay(1) }
    }

    @Test fun ordinaryOpeningRestoresExistingConsentThroughTheRealGateExactlyOnce() = runBlocking {
        Fixture().use { f ->
            val consent = f.data().start
            f.controller.appOpened()
            assertTrue(f.controller.hasAdmission())
            assertEquals(1, f.context.starts.get())
            repeat(3) { f.controller.appOpened(); f.controller.permissionsChanged() }
            f.controller.refresh(StartIngress.RECEIVER)
            assertEquals(1, f.context.starts.get())
            assertEquals(consent.consentGeneration, f.data().start.consentGeneration)
            assertEquals(consent.explicitlyAcknowledged, f.data().start.explicitlyAcknowledged)
            assertNull(f.data().start.lastExplicitlyAcknowledged)
            assertEquals(0, f.mediaFactories.get())
            assertEquals(0, f.observations.get())
            assertTrue(f.controller.state.value.pending.isEmpty())
        }
    }

    @Test fun settledOrdinaryDisconnectCanRestoreButPassiveReturnsStillCannot() = runBlocking {
        Fixture().use { f ->
            f.controller.appOpened()
            val firstLease = ReflectionHelpers.getField<AirPodsTransportOwnership.Lease>(f.controller, "transportLease")
            f.facts.profile = ProfileConnection.DISCONNECTED
            f.controller.refresh(StartIngress.RECEIVER)
            assertTrue(firstLease.released)
            assertFalse(f.controller.hasAdmission())
            assertTrue(f.data().start.marker!!.clean)
            assertEquals(7L, f.data().start.consentGeneration)
            assertFalse(f.data().start.suppressUntilExplicitResume)
            f.facts.profile = ProfileConnection.CONNECTED
            f.visible.value = false; f.visible.value = true
            repeat(3) { f.controller.permissionsChanged(); f.controller.refresh(StartIngress.APP_OPEN, allowAcquisition = false) }
            assertEquals(1, f.context.starts.get())
            assertFalse(f.controller.hasAdmission())
            f.controller.appOpened()
            assertTrue(f.controller.hasAdmission())
            assertEquals(2, f.context.starts.get())
            assertNotSame(firstLease, ReflectionHelpers.getField<Any?>(f.controller, "transportLease"))
            assertEquals(7L, f.data().start.consentGeneration)
            assertTrue(f.controller.state.value.pending.isEmpty())
            assertEquals(0, f.mediaFactories.get())
        }
    }

    @Test fun positiveEntitlementDoesNotTurnMereVisibilityOrPermissionReturnIntoAcquisition() = runBlocking {
        Fixture().use { f ->
            val before = f.data()
            repeat(3) {
                f.visible.value = false; f.visible.value = true
                f.controller.permissionsChanged()
                f.controller.refresh(StartIngress.APP_OPEN, allowAcquisition = false)
            }
            f.assertNoAcquisition()
            assertEquals(before, f.data())
            f.controller.appOpened() // positive control under identical platform/consent facts
            assertEquals(1, f.context.starts.get())
        }
    }

    @Test fun openingCannotGrantConsentOrBypassStopOrDisable() = runBlocking {
        for (reason in listOf<SuppressReason?>(null, SuppressReason.USER_STOP, SuppressReason.DISABLED)) Fixture().use { f ->
            if (reason == null) f.start { it.copy(enabled = false) }
            else f.controller.stopControls(reason)
            val before = f.data().start
            f.controller.appOpened()
            f.assertNoAcquisition()
            assertEquals(before, f.data().start)
        }
    }

    @Test fun userRequestedExitIsConsumedAndSuppressedWithoutAcknowledgement() = runBlocking {
        Fixture().use { f ->
            val exit = f.userExit()
            f.history = ExitHistory.Available(listOf(exit))
            repeat(3) { f.controller.appOpened() }
            f.assertNoAcquisition()
            val start = f.data().start
            assertTrue(start.suppressUntilExplicitResume)
            assertEquals(SuppressReason.USER_REQUESTED_EXIT, start.suppressReason)
            assertEquals(listOf(exit.key), start.consumedExits)
            assertTrue(start.explicitlyAcknowledged.isEmpty())
            assertEquals(7L, start.consentGeneration)
            assertNull(start.marker)
        }
    }

    @Test fun unexplainedOldProcessMarkerRequiresExplicitResume() = runBlocking {
        for (cause in listOf<ExitCause?>(null, ExitCause.UNKNOWN)) Fixture().use { f ->
            f.start { it.copy(marker = f.oldMarker()) }
            f.history = ExitHistory.Available(if (cause == null) emptyList() else
                listOf(OwnProcessExit(ExitKey(200, 39, Application.getProcessName(), 0), cause)))
            f.controller.appOpened()
            f.assertNoAcquisition()
            assertEquals(SuppressReason.UNCLEAN_PROCESS, f.data().start.suppressReason)
            assertTrue(f.data().start.suppressUntilExplicitResume)
            assertFalse(f.data().start.marker!!.clean)
        }
    }

    @Test fun reliableOtherExitExplainsOldMarkerButDoesNotAcknowledgeItAsConsent() = runBlocking {
        Fixture().use { f ->
            f.start { it.copy(marker = f.oldMarker()) }
            f.history = ExitHistory.Available(listOf(OwnProcessExit(
                ExitKey(200, 39, Application.getProcessName(), 3), ExitCause.RELIABLE_OTHER)))
            f.controller.appOpened()
            assertEquals(1, f.context.starts.get())
            assertFalse(f.data().start.suppressUntilExplicitResume)
            assertEquals(7L, f.data().start.consentGeneration)
            assertTrue(f.data().start.explicitlyAcknowledged.isEmpty())
            assertNotEquals("previous-process", f.data().start.marker!!.epoch)
        }
    }

    @Test fun historyFailureUnavailableAndPersistenceFailureDoNotAcquireOrWaitForProfile() = runBlocking {
        for (history in listOf(ExitHistory.QueryFailed, ExitHistory.Unavailable)) Fixture().use { f ->
            f.history = history; f.facts.profile = ProfileConnection.UNRESOLVED
            f.controller.appOpened(); f.assertNoAcquisition()
            assertTrue(f.facts.callbacks.isEmpty())
            assertNull(f.data().start.marker)
        }
        Fixture().use { f ->
            f.disk.failCommit = true
            f.controller.appOpened(); f.assertNoAcquisition()
            assertNull(f.data().start.marker)
        }
    }

    @Test fun productSetupPermissionBondAndConnectionRemainRequired() = runBlocking {
        for (block in 0..5) Fixture().use { f ->
            when (block) {
                0 -> f.entitled = false
                1 -> f.controller.store.transaction {
                    it.copy(pairs = it.pairs + (f.pair to it.pairs.getValue(f.pair).copy(verification = null))) to Unit
                }.getOrThrow()
                2 -> f.controller.store.recordSetup(f.pair, PairVerification("A3063", "wrong-build", 1)).getOrThrow()
                3 -> f.facts.permission = false
                4 -> f.facts.bond = BondFact.BONDING
                5 -> f.facts.profile = ProfileConnection.DISCONNECTED
            }
            f.controller.appOpened(); f.assertNoAcquisition()
            assertTrue(f.facts.callbacks.isEmpty())
        }
    }

    @Test fun coldProfileCompletionBelongsToOneOpeningAndDuplicatesCannotStartAnotherOwner() = runBlocking {
        Fixture().use { f ->
            f.facts.profile = ProfileConnection.UNRESOLVED
            val opening = async(Dispatchers.Default) { f.controller.appOpened() }
            awaitSubscription(f)
            f.assertNoAcquisition()
            f.facts.profile = ProfileConnection.CONNECTED
            repeat(3) { f.facts.callbacks.single().invoke() }
            withTimeout(2_000) { opening.await() }
            assertEquals(1, f.context.starts.get())
            assertTrue(f.controller.hasAdmission())
            assertEquals(1, f.facts.closed.get())
        }
    }

    @Test fun lateProfileCallbackAfterStopPairChangeOrPermissionReturnCannotAcquire() = runBlocking {
        for (invalidate in 0..2) Fixture().use { f ->
            f.facts.profile = ProfileConnection.UNRESOLVED
            val opening = async(Dispatchers.Default) { f.controller.appOpened() }
            awaitSubscription(f)
            when (invalidate) {
                0 -> f.controller.stopControls()
                1 -> f.controller.selectPair(PairAddress("AA:BB:CC:DD:EE:40")).getOrThrow()
                2 -> f.controller.permissionsChanged()
            }
            f.facts.profile = ProfileConnection.CONNECTED
            f.facts.callbacks.single().invoke()
            withTimeout(2_000) { opening.await() }
            f.assertNoAcquisition()
            assertEquals(1, f.facts.closed.get())
        }
    }

    @Test fun foregroundFalseTrueBeforeProfileDeliveryCannotReuseTheOldOpening() = runBlocking {
        Fixture().use { f ->
            f.facts.profile = ProfileConnection.UNRESOLVED
            val opening = async(Dispatchers.Default) { f.controller.appOpened() }
            awaitSubscription(f)
            f.visible.value = false; f.visible.value = true
            f.facts.profile = ProfileConnection.CONNECTED
            f.facts.callbacks.single().invoke()
            withTimeout(2_000) { opening.await() }
            f.assertNoAcquisition()
            f.controller.appOpened()
            assertEquals(1, f.context.starts.get())
        }
    }

    @Test fun changedConsentOrPermissionBeforeProfileDeliveryIsRechecked() = runBlocking {
        for (change in 0..2) Fixture().use { f ->
            f.facts.profile = ProfileConnection.UNRESOLVED
            val opening = async(Dispatchers.Default) { f.controller.appOpened() }
            awaitSubscription(f)
            when (change) {
                0 -> f.start { it.copy(consentGeneration = 8) }
                1 -> f.facts.permission = false
                2 -> f.history = ExitHistory.Available(listOf(f.userExit()))
            }
            f.facts.profile = ProfileConnection.CONNECTED
            f.facts.callbacks.single().invoke()
            withTimeout(2_000) { opening.await() }
            f.assertNoAcquisition()
        }
    }

    @Test fun refusedProfileRequestWinsEvenIfThePlatformCalledBackInline() = runBlocking {
        Fixture().use { f ->
            f.facts.profile = ProfileConnection.UNRESOLVED
            f.facts.accepted = false; f.facts.inlineCallback = true
            f.controller.appOpened()
            f.assertNoAcquisition()
            repeat(3) { f.facts.callbacks.single().invoke() }
            assertEquals(0, f.context.starts.get())
        }
    }

    @Test fun cancellationRemovesReadinessWithoutCreatingAReplayableOpening() = runBlocking {
        Fixture().use { f ->
            f.facts.profile = ProfileConnection.UNRESOLVED
            val opening = async(Dispatchers.Default) { f.controller.appOpened() }
            awaitSubscription(f)
            opening.cancelAndJoin()
            assertEquals(1, f.facts.closed.get())
            f.facts.profile = ProfileConnection.CONNECTED
            f.facts.callbacks.single().invoke()
            f.assertNoAcquisition()
        }
    }

    @Test fun receiverWinningDuringProfileWaitKeepsExactlyOneOwner() = runBlocking {
        Fixture().use { f ->
            f.facts.profile = ProfileConnection.UNRESOLVED
            val opening = async(Dispatchers.Default) { f.controller.appOpened() }
            awaitSubscription(f)
            f.facts.profile = ProfileConnection.CONNECTED
            f.controller.refresh(StartIngress.RECEIVER)
            assertEquals(1, f.context.starts.get())
            f.facts.callbacks.single().invoke()
            withTimeout(2_000) { opening.await() }
            assertEquals(1, f.context.starts.get())
            assertTrue(f.controller.hasAdmission())
        }
    }

    @Test fun profileTimeoutClosesReadinessAndLateDeliveryCannotRetryTheOpening() = runBlocking {
        Fixture().use { f ->
            f.facts.profile = ProfileConnection.UNRESOLVED
            val opening = async(Dispatchers.Default) { f.controller.appOpened() }
            awaitSubscription(f)
            withTimeout(7_000) { opening.await() }
            assertEquals(1, f.facts.closed.get())
            f.facts.profile = ProfileConnection.CONNECTED
            f.facts.callbacks.single().invoke()
            f.assertNoAcquisition()
            assertNull(f.data().start.marker)
            f.controller.appOpened() // another real opening is a new, independently gated action
            assertEquals(1, f.context.starts.get())
        }
    }

    @Test fun backgroundAtMarkerCommitCannotScheduleTheService() = runBlocking {
        Fixture().use { f ->
            f.disk.nextMarkerCommit = { f.visible.value = false; f.visible.value = true }
            f.controller.appOpened()
            f.assertNoAcquisition()
            assertFalse(f.data().start.suppressUntilExplicitResume)
            assertTrue(f.data().start.marker!!.clean)
            // The retired opening did not consume the connection attempt or consent.
            f.controller.appOpened()
            assertEquals(1, f.context.starts.get())
            assertEquals(7L, f.data().start.consentGeneration)
        }
    }
}
