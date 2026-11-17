// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.android

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import dev.lelonio.square.airpods.*
import dev.lelonio.square.airpods.protocol.*
import dev.lelonio.square.airpods.runtime.lifecycle.*
import dev.lelonio.square.airpods.runtime.storage.*
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
import java.util.concurrent.LinkedBlockingQueue

/** PREPARED, NOT EXECUTED. Real controller, gate, store and repository; synthetic platform facts.
 * Admission is acquired through real ingress, never a reflected ticket or production bypass.
 * No service attachment, Bluetooth socket construction, Android device or hardware receipt. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
class I5LongPressRuntimeTest {
    @Before fun processIdentity() { org.robolectric.shadows.ShadowProcess.setPid(41004) }

    private class CountingContext(base: Context) : ContextWrapper(base) {
        var starts = 0
        override fun checkSelfPermission(permission: String) = PackageManager.PERMISSION_GRANTED
        override fun startForegroundService(service: Intent): ComponentName {
            starts++
            return ComponentName(this, AirPodsControlService::class.java)
        }
    }
    private class Facts : BluetoothRuntimeFacts {
        override val adapter: BluetoothAdapter? = null
        var permission = true
        override fun permitted() = permission
        override fun bond(address: PairAddress) = BondFact.BONDED
        override fun connection(address: PairAddress) = ProfileConnection.CONNECTED
        override fun prepare() = Unit
        override fun audioSettingsIntent() = Intent()
        override fun whenProfileReady(ready: () -> Unit): AutoCloseable? = null
    }
    private class Persistence : RuntimePersistence {
        private var bytes: ByteArray? = null
        var markerCommits = 0
        var commits = 0
        var afterCommit: (() -> Unit)? = null
        override fun read() = bytes
        override fun commit(bytes: ByteArray): Boolean {
            if (AirPodsRuntimeCodec.decode(bytes).start.marker != null) markerCommits++
            this.bytes = bytes
            commits++
            afterCommit?.invoke()
            return true
        }
    }
    private class Transport : AacpTransport {
        private val queue = LinkedBlockingQueue<ByteArray>()
        val writes = java.util.Collections.synchronizedList(mutableListOf<List<Int>>())
        override fun connect() {}
        override fun readSdu(): ByteArray? = queue.take().takeIf { it.isNotEmpty() }
        @Volatile var failWrites = false
        override fun write(packet: ByteArray) { if (failWrites && (0..15).any { packet.contentEquals(AacpCodec.property(CommandProperty.STEM_CONFIG, it)) }) throw java.io.IOException("synthetic"); writes.add(packet.map { it.toInt() and 255 }) }
        override fun close() { queue.offer(byteArrayOf()) }
    }
    private class Fixture : AutoCloseable {
        val context = CountingContext(RuntimeEnvironment.getApplication())
        val visible = MutableStateFlow(true)
        val pair = PairAddress("AA:BB:CC:DD:EE:04")
        val facts = Facts()
        val persistence = Persistence()
        var mediaFactoryCalls = 0
        var syntheticTransportFactories = 0
        val registry = object : CompanionRegistryFacade {
            override fun hasFeature() = true
            override fun hasObservePermission() = true
            override fun isBonded(address: PairAddress) = true
            override fun ownAssociations() = listOf(OwnedAssociation(4, pair, context.packageName))
            override fun start(registration: ObservationRegistration) = true
            override fun stop(registration: ObservationRegistration) = true
            override fun disassociate(association: OwnedAssociation) = true
        }
        val controller = AirPodsAndroidController(context, visible, { true },
            { mediaFactoryCalls++; error("Service must never attach") },
            factsOverride = facts, registryOverride = registry,
            exitHistoryOverride = ExitHistoryProvider { ExitHistory.Available(emptyList()) },
            runtimeDispatcher = Dispatchers.Unconfined, persistenceOverride = persistence)
        val repository = ReflectionHelpers.getField<AirPodsRepository>(controller, "repository")
        init {
            // Existing test-only reflection marks completed migration, not entitlement/admission.
            ReflectionHelpers.setField(controller, "migrationReady", true)
            controller.store.select(pair).getOrThrow()
            controller.store.recordSetup(pair, PairVerification("A3063", Build.FINGERPRINT, 1)).getOrThrow()
            controller.store.transaction {
                it.copy(pocMigrationCompleted = true, start = StartState(enabled = true, consentGeneration = 7)) to Unit
            }.getOrThrow()
            preferences(HostSettings(headGestures = true, automationsEnabled = true))
        }
        fun data() = controller.store.snapshot().getOrThrow()
        fun preferences(host: HostSettings) { controller.store.configure(pair) { it.copy(host = host) }.getOrThrow() }
        suspend fun admit() {
            controller.refresh(StartIngress.RECEIVER)
            assertTrue(controller.hasAdmission())
            assertEquals(1, context.starts)
        }
        suspend fun ready(address: PairAddress? = null): Transport {
            val transport = Transport()
            suspend fun await(stage: AacpStage) = withTimeout(2_000) { controller.state.first { it.stage == stage } }
            repository.construct(address) { syntheticTransportFactories++; transport }; await(AacpStage.CONSTRUCTED)
            repository.connect(); await(AacpStage.CONNECTED)
            repository.handshake(); await(AacpStage.HANDSHAKE_SENT)
            repository.subscribe(); await(AacpStage.SUBSCRIBED)
            repository.receive(controller.state.value.generation,
                AacpEvent.Information(AirPodsIdentity("A3063", "Apple Inc.", "fixture")))
            return transport
        }
        fun assertNoAcquisition() {
            assertTrue(controller.runtime.value.productConformance)
            assertFalse(controller.hasAdmission())
            assertNull(ReflectionHelpers.getField<Any?>(controller, "ticket"))
            assertNull(ReflectionHelpers.getField<Any?>(controller, "transportLease"))
            assertNull(ReflectionHelpers.getField<Any?>(AirPodsTransportOwnership.process, "active"))
            assertEquals(0, context.starts)
            assertEquals(0, mediaFactoryCalls)
            assertEquals(0, syntheticTransportFactories)
            assertEquals(AacpStage.CLOSED, controller.state.value.stage)
            assertNull(data().start.marker)
            assertEquals(0, persistence.markerCommits)
        }
        override fun close() {
            runBlocking { controller.stopControls() }
            context.unregisterReceiver(ReflectionHelpers.getField(controller, "receiver"))
            ReflectionHelpers.getField<CoroutineScope>(controller, "processScope").cancel()
        }
    }

    private fun Fixture.rx(mask: Int, generation: Long = controller.state.value.generation) =
        repository.receive(generation, AacpEvent.Property(CommandProperty.STEM_CONFIG, PropertyValue.Number(mask)))
    private fun Fixture.pairSettings() = data().pairs.getValue(pair)
    private suspend fun Fixture.outcome(expected: CommandOutcome) = withTimeout(7_000) {
        controller.state.first { it.outcomes[CommandProperty.STEM_CONFIG] == expected }; Unit
    }
    private suspend fun Fixture.sent() = withTimeout(2_000) {
        controller.state.first { it.desired[CommandProperty.STEM_CONFIG]?.status == DesiredStatus.SENT }
    }

    @Test fun changingOneSideRequiresKnownOtherStemBits() = runBlocking {
        Fixture().use { f ->
            f.admit()
            val transport = f.ready(f.pair)
            assertNull(f.controller.state.value.propertyObserved(CommandProperty.STEM_CONFIG))
            val before = f.pairSettings()
            val accepted = f.controller.setLongPress(left = LongPressAction.DIGITAL_ASSISTANT)
            if (accepted) {
                f.sent()
                val overwritten = AacpCodec.property(CommandProperty.STEM_CONFIG, 8).map { it.toInt() and 255 }
                assertTrue("Unobserved base sent 0x08, clearing the other stem bits", transport.writes.contains(overwritten))
            }
            assertFalse("Writing the shared mask requires a current-generation observation", accepted)
            assertEquals(before, f.pairSettings())
            assertTrue(f.controller.state.value.pending.isEmpty())

            f.rx(5)
            assertTrue(f.controller.setLongPress(left = LongPressAction.DIGITAL_ASSISTANT))
            f.sent()
            val preserved = AacpCodec.property(CommandProperty.STEM_CONFIG, 13).map { it.toInt() and 255 }
            assertTrue("Writing 0x0d preserves single and triple press bits from RX 0x05",
                transport.writes.contains(preserved))

            val oldGeneration = f.controller.state.value.generation
            f.repository.close()
            val next = f.ready(f.pair)
            f.rx(7, oldGeneration)
            assertNull(f.controller.state.value.propertyObserved(CommandProperty.STEM_CONFIG))
            assertEquals(PropertyValue.Number(5),
                f.controller.state.value.cachedValues.observed[CommandProperty.STEM_CONFIG]?.value)
            val saved = f.pairSettings()
            assertFalse("A cached observation cannot authorize the next session's write",
                f.controller.setLongPress(right = LongPressAction.DIGITAL_ASSISTANT))
            assertEquals(saved, f.pairSettings())
            assertTrue(f.controller.state.value.pending.isEmpty())
            assertTrue(next.writes.none { it.getOrNull(6) == 0x39 })
        }
    }

    @Test fun masksZeroThroughSevenBothSidesAndBothChoicesPreserveLowBits() = runBlocking {
        for (mask in 0..7) Fixture().use { f ->
            f.admit(); f.ready(); f.rx(mask)
            for (leftSide in listOf(true, false)) for (choice in LongPressAction.entries) {
                val before = f.pairSettings()
                assertTrue(if (leftSide) f.controller.setLongPress(left = choice) else f.controller.setLongPress(right = choice))
                val saved = f.pairSettings()
                assertEquals(if (leftSide) choice else before.leftLongPress, saved.leftLongPress)
                assertEquals(if (!leftSide) choice else before.rightLongPress, saved.rightLongPress)
                val custom = saved.leftLongPress == LongPressAction.DIGITAL_ASSISTANT || saved.rightLongPress == LongPressAction.DIGITAL_ASSISTANT
                val wanted = mask or (if (custom) 8 else 0)
                assertEquals(wanted, (f.controller.state.value.desired.getValue(CommandProperty.STEM_CONFIG).requested as PropertyValue.Number).value)
                f.sent(); f.rx(wanted); f.outcome(CommandOutcome.OBSERVED_MATCH)
            }
        }
    }
    @Test fun longPressEntersFirstConfiguredModeWhenCurrentModeIsOutsideCycle() = runBlocking<Unit> {
        Fixture().use { f ->
            f.admit(); val transport = f.ready(f.pair)
            f.controller.store.configure(f.pair) { it.copy(leftLongPress = LongPressAction.DIGITAL_ASSISTANT) }.getOrThrow()
            val generation = f.controller.state.value.generation
            f.rx(8)
            f.repository.receive(generation, AacpEvent.Property(CommandProperty.LISTENING_MODE_CONFIGS, PropertyValue.Number(6)))
            fun pressRight() = ReflectionHelpers.callInstanceMethod<Unit>(f.controller, "handleLongPress",
                ReflectionHelpers.ClassParameter.from(StemSample::class.java, StemSample(8, 2)))
            f.repository.receive(generation, AacpEvent.Mode(ListeningMode.ANC))
            pressRight()
            withTimeout(2_000) { f.controller.state.first { it.desired[CommandProperty.MODE]?.status == DesiredStatus.SENT } }
            assertEquals(PropertyValue.Number(3), f.controller.state.value.desired[CommandProperty.MODE]?.requested)
            f.repository.receive(generation, AacpEvent.Mode(ListeningMode.TRANSPARENCY))
            f.repository.receive(generation, AacpEvent.Mode(ListeningMode.ADAPTIVE))
            pressRight()
            assertEquals("The beta.4 stem order enters at ANC before Transparency", PropertyValue.Number(2),
                f.controller.state.value.desired[CommandProperty.MODE]?.requested)
            withTimeout(2_000) { f.controller.state.first { it.desired[CommandProperty.MODE]?.status == DesiredStatus.SENT } }
            assertTrue(transport.writes.contains(AacpCodec.property(CommandProperty.MODE, 2).map { it.toInt() and 255 }))
        }
    }
    @Test fun concurrentSidesAndNewerChoiceAreAcceptedWhileUnconfirmed() = runBlocking {
        Fixture().use { f ->
            f.admit(); f.ready(); f.rx(3)
            val accepted = listOf(async(Dispatchers.Default) { f.controller.setLongPress(left = LongPressAction.DIGITAL_ASSISTANT) },
                async(Dispatchers.Default) { f.controller.setLongPress(right = LongPressAction.DIGITAL_ASSISTANT) }).awaitAll()
            assertEquals(2, accepted.count { it })
            assertEquals(LongPressAction.DIGITAL_ASSISTANT, f.pairSettings().leftLongPress)
            assertEquals(LongPressAction.DIGITAL_ASSISTANT, f.pairSettings().rightLongPress)
            assertTrue(f.controller.setLongPress(LongPressAction.CYCLE_NOISE_CONTROL_MODES, LongPressAction.CYCLE_NOISE_CONTROL_MODES))
            f.sent()
            assertEquals(PropertyValue.Number(3), f.controller.state.value.propertyEffective(CommandProperty.STEM_CONFIG)?.value)
        }
    }
    @Test fun immediateDispatchRejectionRollsBackOnlyTheTwoChoices() = runBlocking {
        Fixture().use { f ->
            f.admit(); f.ready(); f.rx(2)
            val saved = f.pairSettings()
            f.persistence.afterCommit = { f.persistence.afterCommit = null; f.repository.close() }
            assertFalse(f.controller.setLongPress(left = LongPressAction.DIGITAL_ASSISTANT))
            assertEquals(saved, f.pairSettings())
        }
    }
    @Test fun asyncSendFailureKeepsSavedIntentAndDoesNotConfirmOldReading() = runBlocking {
        Fixture().use { f ->
            f.admit(); val transport = f.ready(); f.rx(1); transport.failWrites = true
            assertTrue(f.controller.setLongPress(right = LongPressAction.DIGITAL_ASSISTANT))
            f.outcome(CommandOutcome.SEND_FAILED)
            assertEquals(LongPressAction.DIGITAL_ASSISTANT, f.pairSettings().rightLongPress)
            assertFalse(f.controller.state.value.pending.containsKey(CommandProperty.STEM_CONFIG))
        }
    }
    @Test fun silenceKeepsIntentAndLateRxReconciles() = runBlocking {
        Fixture().use { f ->
            f.admit(); f.ready(); f.rx(4)
            assertTrue(f.controller.setLongPress(left = LongPressAction.DIGITAL_ASSISTANT))
            f.sent()
            assertNull(f.controller.state.value.outcomes[CommandProperty.STEM_CONFIG])
            assertEquals(PropertyValue.Number(12), f.controller.state.value.propertyEffective(CommandProperty.STEM_CONFIG)?.value)
            assertEquals(PropertyValue.Number(4), f.controller.state.value.propertyObserved(CommandProperty.STEM_CONFIG)?.value)
            f.rx(12); f.outcome(CommandOutcome.OBSERVED_MATCH)
        }
    }
    @Test fun staleAdmissionIsRejectedBeforePersistenceAndReplacementDuringSaveRollsBack() = runBlocking {
        Fixture().use { f ->
            f.admit(); f.ready(); f.rx(4)
            val before = f.pairSettings(); val commits = f.persistence.commits
            f.facts.permission = false
            assertFalse(f.controller.setLongPress(left = LongPressAction.DIGITAL_ASSISTANT))
            assertEquals(commits, f.persistence.commits)
            f.facts.permission = true
            f.persistence.afterCommit = {
                f.persistence.afterCommit = null
                ReflectionHelpers.setField(f.controller, "ticket", null)
            }
            assertFalse(f.controller.setLongPress(left = LongPressAction.DIGITAL_ASSISTANT))
            assertEquals(before, f.pairSettings())
            assertTrue(f.controller.state.value.pending.isEmpty())
        }
    }
    @Test fun differentRxCloseStaleGenerationAndReconnectNeverReplaySavedIntent() = runBlocking {
        Fixture().use { f ->
            f.admit(); f.ready(); f.rx(5)
            assertTrue(f.controller.setLongPress(left = LongPressAction.DIGITAL_ASSISTANT))
            f.sent(); f.rx(5); f.outcome(CommandOutcome.OBSERVED_OTHER)
            assertTrue(f.controller.setLongPress(right = LongPressAction.DIGITAL_ASSISTANT))
            val old = f.controller.state.value.generation
            f.repository.close(); assertEquals(DesiredStatus.UNCERTAIN, f.controller.state.value.desired[CommandProperty.STEM_CONFIG]?.status)
            val saved = f.pairSettings(); val commits = f.persistence.commits
            assertFalse(f.controller.setLongPress(left = LongPressAction.CYCLE_NOISE_CONTROL_MODES))
            assertEquals(commits, f.persistence.commits)
            val transport = f.ready(); f.rx(13, old)
            assertNull(f.controller.state.value.propertyObserved(CommandProperty.STEM_CONFIG))
            assertTrue(f.controller.state.value.pending.isEmpty())
            assertEquals(saved, f.pairSettings())
            assertFalse(synchronized(transport.writes) { transport.writes.toList() }.any { it == AacpCodec.property(CommandProperty.STEM_CONFIG, 13).map { b -> b.toInt() and 255 } })
            f.controller.stopControls()
            assertFalse(f.controller.setLongPress(left = LongPressAction.DIGITAL_ASSISTANT))
        }
    }
}
