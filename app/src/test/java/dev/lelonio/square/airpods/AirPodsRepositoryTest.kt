// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods

import dev.lelonio.square.airpods.protocol.*
import dev.lelonio.square.airpods.runtime.storage.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AirPodsRepositoryTest {
    private class FakeTransport(private val connectGate: CountDownLatch? = null, val writeGate: CountDownLatch? = null) : AacpTransport {
        val closed = AtomicInteger()
        val connectStarted = AtomicInteger()
        private val queue = LinkedBlockingQueue<ByteArray>()
        fun deliver(packet: ByteArray) { queue.put(packet) }
        override fun connect() { connectStarted.incrementAndGet(); connectGate?.await() }
        override fun readSdu(): ByteArray? = queue.take().takeIf { it.isNotEmpty() }
        val writes = java.util.Collections.synchronizedList(mutableListOf<List<Int>>())
        val inWrite = AtomicInteger()
        val maxWriters = AtomicInteger()
        val writesStarted = AtomicInteger()
        @Volatile var blockWrites = false
        @Volatile var failWrites = false
        override fun write(packet: ByteArray) {
            val count=inWrite.incrementAndGet(); maxWriters.updateAndGet { maxOf(it,count) }
            writesStarted.incrementAndGet()
            try {
                if (blockWrites) writeGate?.await()
                if (failWrites) throw java.io.IOException("synthetic failure")
                writes.add(packet.map { it.toInt() and 255 })
            } finally { inWrite.decrementAndGet() }
        }
        override fun close() { closed.incrementAndGet(); connectGate?.countDown(); writeGate?.countDown(); queue.offer(byteArrayOf()) }
    }
    private suspend fun AirPodsRepository.await(stage: AacpStage) = withTimeout(2_000) { state.first { it.stage == stage } }
    private suspend fun ready(repository: AirPodsRepository, socket: FakeTransport, pair: PairAddress? = null): Long {
        repository.construct(pair) { socket }; repository.await(AacpStage.CONSTRUCTED)
        repository.connect(); repository.await(AacpStage.CONNECTED)
        repository.handshake(); repository.await(AacpStage.HANDSHAKE_SENT)
        repository.subscribe(); repository.await(AacpStage.SUBSCRIBED)
        val generation = repository.state.value.generation
        repository.receive(generation, AacpEvent.Information(AirPodsIdentity("A3063", "Apple Inc.", "fixture")))
        return generation
    }
    private suspend fun AirPodsRepository.sent(property: CommandProperty) = withTimeout(2_000) {
        state.first { it.desired[property]?.status == DesiredStatus.SENT }
    }
    @Test fun unknownEarCodesRemainObservableWithoutInventingWornState() = runBlocking {
        val timers = TestScope()
        val recorded = java.util.Collections.synchronizedList(mutableListOf<QualificationEvent>())
        val repository = AirPodsRepository(timers, AirPodsClock { 100L },
            observer = AirPodsQualificationObserver { recorded.add(it) })
        try {
            val generation = ready(repository, FakeTransport())
            val packet = byteArrayOf(4, 0, 4, 0, 6, 0, 3, 0)
            val event = AacpCodec.decodeSdu(packet).single()
            repository.receive(generation, event)
            val sample = requireNotNull(repository.state.value.ears).value
            assertEquals(EarSample(3, 0), sample)
            assertEquals(EarState.UNKNOWN, sample.normalized)
            val row = recorded.single { it.step == QualificationStep.RX && it.flags == 6 }
            assertEquals(EarState.UNKNOWN.ordinal, row.value)
            assertEquals(listOf(3, 0), row.values)
            assertEquals(generation, row.generation)
        } finally { repository.close(); timers.cancel() }
    }
    @Test fun retiredEarGenerationCannotAddEvidenceToTheNextOwner() = runBlocking {
        val timers = TestScope()
        val recorded = java.util.Collections.synchronizedList(mutableListOf<QualificationEvent>())
        val repository = AirPodsRepository(timers, AirPodsClock { 100L },
            observer = AirPodsQualificationObserver { recorded.add(it) })
        try {
            val retired = ready(repository, FakeTransport())
            repository.close()
            val current = ready(repository, FakeTransport())
            assertTrue(current > retired)
            repository.receive(retired, AacpEvent.Ear(EarSample(0, 0)))
            assertNull(repository.state.value.ears)
            assertFalse(recorded.any { it.step == QualificationStep.RX && it.flags == 6 })
            repository.receive(current, AacpEvent.Ear(EarSample(0, 1)))
            val row = recorded.single { it.step == QualificationStep.RX && it.flags == 6 }
            assertEquals(current, row.generation)
            assertEquals(listOf(0, 1), row.values)
            assertEquals(EarState.LEFT.ordinal, row.value)
        } finally { repository.close(); timers.cancel() }
    }
    @Test fun documentedInCaseCodesKeepRawEvidenceAndAreNotUnknown() = runBlocking<Unit> {
        val timers = TestScope()
        val recorded = java.util.Collections.synchronizedList(mutableListOf<QualificationEvent>())
        val repository = AirPodsRepository(timers, AirPodsClock { 100L },
            observer = AirPodsQualificationObserver { recorded.add(it) })
        try {
            val generation = ready(repository, FakeTransport())
            // Independent received bytes; no outbound command encoder involved.
            val mono = byteArrayOf(4, 0, 4, 0, 6, 0, 2, 0)
            val none = byteArrayOf(4, 0, 4, 0, 6, 0, 2, 1)
            for (packet in listOf(mono, none))
                repository.receive(generation, AacpCodec.decodeSdu(packet).single())
            assertEquals(EarSample(2, 1), repository.state.value.ears?.value)
            assertEquals(EarState.NONE, repository.state.value.ears?.value?.normalized)
            val rows = recorded.filter { it.step == QualificationStep.RX && it.flags == 6 }
            assertEquals(listOf(EarState.RIGHT.ordinal, EarState.NONE.ordinal), rows.map { it.value })
            assertEquals(listOf(listOf(2, 0), listOf(2, 1)), rows.map { it.values })
        } finally { repository.close(); timers.cancel() }
    }
    @Test fun receivedStateIsSeparateFromIntentAndOtherControllerWins() = runBlocking {
        val timerScope = TestScope()
        val repository = AirPodsRepository(timerScope, AirPodsClock { timerScope.testScheduler.currentTime })
        try {
            val generation = ready(repository, FakeTransport())
            repository.receive(generation, AacpEvent.Mode(ListeningMode.TRANSPARENCY))
            assertTrue(repository.setMode(ListeningMode.ANC))
            repository.sent(CommandProperty.MODE)
            assertEquals(PropertyValue.Number(2), repository.state.value.propertyEffective(CommandProperty.MODE)?.value)
            assertEquals(ListeningMode.TRANSPARENCY, repository.state.value.mode?.value)
            // Unrelated activity never confirms configuration.
            assertTrue(repository.setConversation(true))
            repository.sent(CommandProperty.CONVERSATION)
            repository.receive(generation, AacpEvent.ConversationActivity(1))
            assertEquals(DesiredStatus.SENT, repository.state.value.desired[CommandProperty.CONVERSATION]?.status)
            repository.receive(generation, AacpEvent.Mode(ListeningMode.ADAPTIVE))
            assertEquals(CommandOutcome.OBSERVED_OTHER, repository.state.value.outcomes[CommandProperty.MODE])
            repository.receive(generation, AacpEvent.ConversationSetting(true))
            assertEquals(CommandOutcome.OBSERVED_MATCH, repository.state.value.outcomes[CommandProperty.CONVERSATION])
            assertTrue(repository.state.value.pending.isEmpty())
        } finally { repository.close(); timerScope.cancel() }
    }
    @Test fun silenceNeverRollsBackOrFailsAndLateStateStillWins() = runBlocking {
        val timers = TestScope()
        val repository = AirPodsRepository(timers, AirPodsClock { timers.testScheduler.currentTime })
        try {
            val generation = ready(repository, FakeTransport())
            assertTrue(repository.setMode(ListeningMode.ANC))
            repository.sent(CommandProperty.MODE)
            timers.runCurrent(); timers.advanceTimeBy(60_000); timers.runCurrent()
            assertNull(repository.state.value.outcomes[CommandProperty.MODE])
            assertEquals(DesiredStatus.SENT, repository.state.value.desired[CommandProperty.MODE]?.status)
            assertEquals(PropertyValue.Number(2), repository.state.value.propertyEffective(CommandProperty.MODE)?.value)
            assertNull(repository.state.value.propertyObserved(CommandProperty.MODE))
            repository.receive(generation, AacpEvent.Mode(ListeningMode.ANC))
            assertEquals(ListeningMode.ANC, repository.state.value.mode?.value)
            assertEquals(CommandOutcome.OBSERVED_MATCH, repository.state.value.outcomes[CommandProperty.MODE])
            repository.receive(generation, AacpEvent.Mode(ListeningMode.TRANSPARENCY))
            assertEquals(PropertyValue.Number(3), repository.state.value.propertyEffective(CommandProperty.MODE)?.value)
        } finally { repository.close(); timers.cancel() }
    }
    @Test fun previousGenerationCannotOverwriteNewSessionAndCloseUnblocksReader() = runBlocking {
        val timers = TestScope()
        val repository = AirPodsRepository(timers, AirPodsClock { timers.testScheduler.currentTime })
        val socket = FakeTransport()
        try {
            val generation = ready(repository, socket)
            repository.close()
            assertEquals(1, socket.closed.get())
            val next = ready(repository, FakeTransport())
            assertTrue(next > generation)
            repository.receive(generation, AacpEvent.Mode(ListeningMode.ANC))
            assertNull(repository.state.value.mode)
        } finally { repository.close(); timers.cancel() }
    }
    @Test fun tenSecondDeadlineClosesBlockingConnect() = runBlocking {
        val timers = TestScope()
        val repository = AirPodsRepository(timers, AirPodsClock { timers.testScheduler.currentTime })
        val socket = FakeTransport(CountDownLatch(1))
        try {
            repository.construct { socket }; repository.await(AacpStage.CONSTRUCTED)
            repository.connect(); repository.await(AacpStage.CONNECTING)
            // IO registers its deadline before entering connect; wait without advancing fake time.
            withTimeout(2_000) {
                while (socket.connectStarted.get() == 0) delay(1)
            }
            timers.runCurrent(); timers.advanceTimeBy(9_999); timers.runCurrent()
            assertEquals(0, socket.closed.get())
            timers.advanceTimeBy(1); timers.runCurrent()
            assertEquals(AacpStage.ERROR, repository.state.value.stage)
            assertEquals(1, socket.closed.get())
        } finally { repository.close(); timers.cancel() }
    }

    @Test fun all19PropertiesHaveIndependentIntentsAndOnlyRelevantRxReconciles() = runBlocking {
        val timers=TestScope();val repository=AirPodsRepository(timers,AirPodsClock { timers.testScheduler.currentTime })
        try {
            val g=ready(repository,FakeTransport())
            val controls=AirPodsProperties.controls.filterNot { it.internalDependency }
            for (spec in controls) {
                val v=if(spec.encoding==ControlEncoding.MODE_MASK) 3 else spec.range.first
                assertTrue(spec.catalogId,repository.setProperty(spec.property,v))
                assertNull(repository.state.value.propertyObserved(spec.property))
                assertTrue(repository.setProperty(spec.property,v)) // duplicate intent coalesces
            }
            withTimeout(2_000) { repository.state.first { it.pending.isEmpty() } }
            assertEquals(19,repository.state.value.desired.size)
            repository.receive(g,AacpEvent.Unknown(9,11,"synthetic malformed"))
            repository.receive(g,AacpEvent.ConversationActivity(1))
            assertTrue(repository.state.value.desired.values.all { it.status == DesiredStatus.SENT })
            for (spec in controls) {
                val v=if(spec.encoding==ControlEncoding.MODE_MASK) 3 else spec.range.first
                AacpCodec.decodeSdu(AacpCodec.property(spec.property,v)).forEach { repository.receive(g,it) }
                assertEquals(spec.catalogId,CommandOutcome.OBSERVED_MATCH,repository.state.value.outcomes[spec.property])
                assertEquals(PropertyValue.Number(v),repository.state.value.propertyObserved(spec.property)?.value)
            }
            assertTrue(repository.state.value.pending.isEmpty())
            assertNull(repository.state.value.deviceAnnounced)
        } finally { repository.close();timers.cancel() }
    }
    @Test fun candidateAndObservedAreSeparateAndOldIdentityCannotEnableWrites() = runBlocking {
        val timers=TestScope();val repository=AirPodsRepository(timers,AirPodsClock { 0 })
        try {
            val g=ready(repository,FakeTransport())
            assertTrue(repository.state.value.canAttemptWrite(CommandProperty.CHIME_VOLUME,PropertyValue.Number(50)))
            assertNull(repository.state.value.propertyObserved(CommandProperty.CHIME_VOLUME))
            repository.receive(g,AacpEvent.Mode(ListeningMode.ANC))
            assertNull(repository.state.value.propertyObserved(CommandProperty.CHIME_VOLUME))
            repository.receive(g,AacpEvent.Information(AirPodsIdentity("A9999","Apple Inc.","fixture")))
            assertFalse(repository.setMode(ListeningMode.ANC))
            repository.close()
            repository.receive(g,AacpEvent.Information(AirPodsIdentity("A3063","Apple Inc.","fixture")))
            assertFalse(repository.setProperty(CommandProperty.CHIME_VOLUME,50))
        } finally { repository.close();timers.cancel() }
    }
    @Test fun concurrentWritersAreSerializedAndCloseInterruptsBlockedWrite() = runBlocking {
        val timers=TestScope();val repository=AirPodsRepository(timers,AirPodsClock { 0 })
        val socket=FakeTransport(writeGate=CountDownLatch(1))
        try {
            ready(repository,socket)
            socket.blockWrites=true
            assertTrue(repository.setProperty(CommandProperty.CHIME_VOLUME,50))
            assertTrue(repository.setProperty(CommandProperty.MIC_MODE,1))
            withTimeout(2000) { while(socket.inWrite.get()==0) delay(1) }
            assertEquals(1,socket.maxWriters.get())
            repository.close()
            assertEquals(1,socket.closed.get())
            assertEquals(AacpStage.CLOSED,repository.state.value.stage)
            assertTrue(repository.state.value.pending.isEmpty())
            assertEquals(CommandOutcome.CLOSED,repository.state.value.outcomes[CommandProperty.MIC_MODE])
        } finally { repository.close();timers.cancel() }
    }
    @Test fun queuedWritersCompleteWithoutInterleavingPackets() = runBlocking {
        val timers=TestScope();val repository=AirPodsRepository(timers,AirPodsClock { 0 })
        val gate=CountDownLatch(1);val socket=FakeTransport(writeGate=gate)
        try {
            ready(repository,socket);socket.blockWrites=true
            assertTrue(repository.setProperty(CommandProperty.CHIME_VOLUME,50))
            withTimeout(2000) { while(socket.inWrite.get()==0) delay(1) }
            assertTrue(repository.setProperty(CommandProperty.MIC_MODE,1))
            assertTrue(repository.setConversation(true))
            // Give independent IO dispatches time to contend while the first platform write is blocked.
            delay(50)
            gate.countDown()
            withTimeout(2000) { while(socket.writes.size < 5) delay(1) }
            assertEquals(1,socket.maxWriters.get())
            val commands=synchronized(socket.writes) { socket.writes.filter { it.getOrNull(4)==9 } }
            assertEquals(3,commands.size)
            assertTrue(commands.all { it.size==11 })
        } finally { repository.close();timers.cancel() }
    }
    @Test fun trackingSwitchPairsStopWithActiveFormatAndFailureInvalidatesSession() = runBlocking {
        val timers=TestScope();val repository=AirPodsRepository(timers,AirPodsClock { 0 })
        val socket=FakeTransport()
        try {
            ready(repository,socket)
            assertTrue(repository.setHeadTracking(HeadPacketFormat.STANDARD,true))
            withTimeout(2000) { repository.state.first { it.activePacketFormat==HeadPacketFormat.STANDARD } }
            assertTrue(repository.setHeadTracking(HeadPacketFormat.ALTERNATE,true))
            withTimeout(2000) { repository.state.first { it.activePacketFormat==HeadPacketFormat.ALTERNATE } }
            val frames=synchronized(socket.writes) { socket.writes.filter { it.getOrNull(4)==0x17 } }
            assertEquals(listOf(
                AacpCodec.headTracking(HeadPacketFormat.STANDARD,true).map { it.toInt() and 255 },
                AacpCodec.headTracking(HeadPacketFormat.STANDARD,false).map { it.toInt() and 255 },
                AacpCodec.headTracking(HeadPacketFormat.ALTERNATE,true).map { it.toInt() and 255 }),frames)
            socket.failWrites=true
            assertTrue(repository.setHeadTracking(HeadPacketFormat.STANDARD,true))
            repository.await(AacpStage.ERROR)
            assertNull(repository.state.value.activePacketFormat)
            withTimeout(2000) { while(socket.closed.get()==0) delay(1) }
            assertEquals(1,socket.closed.get())
        } finally { repository.close();timers.cancel() }
    }
    @Test fun renameAndEqRemainEffectiveWithoutManufacturingRx() = runBlocking {
        val timers=TestScope();val repository=AirPodsRepository(timers,AirPodsClock { timers.testScheduler.currentTime })
        try {
            val g=ready(repository,FakeTransport())
            assertTrue(repository.rename("Cuffie ?"));assertTrue(repository.setEq(PropertyValue.Eq(true,0,50,100)))
            repository.sent(CommandProperty.NAME); repository.sent(CommandProperty.EQ)
            repository.receive(g,AacpEvent.Information(AirPodsIdentity("A3063","Apple Inc.","fixture",name="Cuffie ?")))
            assertEquals(CommandOutcome.OBSERVED_MATCH,repository.state.value.outcomes[CommandProperty.NAME])
            assertNull(repository.state.value.propertyObserved(CommandProperty.EQ))
            timers.runCurrent();timers.advanceTimeBy(5000);timers.runCurrent()
            assertNull(repository.state.value.outcomes[CommandProperty.EQ])
            assertEquals(PropertyValue.Eq(true,0,50,100), repository.state.value.propertyEffective(CommandProperty.EQ)?.value)
        } finally { repository.close();timers.cancel() }
    }

    @Test fun queuedRxBeforeWriteCannotConfirmAndLatestQueuedValueReplacesEarlierOnes() = runBlocking {
        val scope = TestScope(); val repository = AirPodsRepository(scope, AirPodsClock { 100 })
        val gate = CountDownLatch(1); val socket = FakeTransport(writeGate = gate)
        try {
            val g = ready(repository, socket)
            socket.blockWrites = true
            repository.setProperty(CommandProperty.CHIME_VOLUME, 10)
            withTimeout(2_000) { while (socket.inWrite.get() == 0) delay(1) }
            assertTrue(repository.setMode(ListeningMode.ANC))
            repository.receive(g, AacpEvent.Mode(ListeningMode.ANC))
            assertEquals(DesiredStatus.QUEUED, repository.state.value.desired[CommandProperty.MODE]?.status)
            assertNull(repository.state.value.outcomes[CommandProperty.MODE])
            assertTrue(repository.setMode(ListeningMode.OFF))
            assertTrue(repository.setMode(ListeningMode.ADAPTIVE))
            assertEquals(PropertyValue.Number(4), repository.state.value.propertyEffective(CommandProperty.MODE)?.value)
            gate.countDown()
            repository.sent(CommandProperty.MODE)
            val writes = synchronized(socket.writes) { socket.writes.filter { it.getOrNull(6) == 0x0d } }
            assertEquals(listOf(AacpCodec.mode(ListeningMode.ADAPTIVE).map { it.toInt() and 255 }), writes)
            assertEquals(1, socket.maxWriters.get())
        } finally { gate.countDown(); repository.close(); scope.cancel() }
    }

    @Test fun matchingRxBeforePhysicalWriteStillNeedsPostWriteRxForReconciliation() = runBlocking {
        val scope = TestScope(); val repository = AirPodsRepository(scope, AirPodsClock { 100 })
        val gate = CountDownLatch(1); val socket = FakeTransport(writeGate = gate)
        try {
            val g = ready(repository, socket); socket.blockWrites = true
            repository.setProperty(CommandProperty.CHIME_VOLUME, 10)
            withTimeout(2_000) { while (socket.inWrite.get() == 0) delay(1) }
            repository.setMode(ListeningMode.ANC)
            repository.receive(g, AacpEvent.Mode(ListeningMode.ANC))
            gate.countDown(); repository.sent(CommandProperty.MODE)
            assertEquals(DesiredStatus.SENT, repository.state.value.desired[CommandProperty.MODE]?.status)
            repository.receive(g, AacpEvent.Mode(ListeningMode.TRANSPARENCY))
            assertEquals(DesiredStatus.CONTRADICTED, repository.state.value.desired[CommandProperty.MODE]?.status)
            assertEquals(PropertyValue.Number(3), repository.state.value.propertyEffective(CommandProperty.MODE)?.value)
        } finally { gate.countDown(); repository.close(); scope.cancel() }
    }

    @Test fun lateEchoOfAIsConsumedOnceThenTheSameContraryValueWins() = runBlocking {
        val scope = TestScope(); val repository = AirPodsRepository(scope, AirPodsClock { 100 })
        try {
            val g = ready(repository, FakeTransport())
            repository.setMode(ListeningMode.ANC); repository.sent(CommandProperty.MODE)
            repository.setMode(ListeningMode.ADAPTIVE); repository.sent(CommandProperty.MODE)
            repository.receive(g, AacpEvent.Mode(ListeningMode.ANC))
            assertEquals(ListeningMode.ANC, repository.state.value.mode?.value) // truthful D2
            assertEquals(PropertyValue.Number(4), repository.state.value.propertyEffective(CommandProperty.MODE)?.value)
            assertEquals(DesiredStatus.SENT, repository.state.value.desired[CommandProperty.MODE]?.status)
            repository.receive(g, AacpEvent.Mode(ListeningMode.ANC))
            assertEquals(CommandOutcome.OBSERVED_OTHER, repository.state.value.outcomes[CommandProperty.MODE])
            assertEquals(PropertyValue.Number(2), repository.state.value.propertyEffective(CommandProperty.MODE)?.value)
            repository.receive(g, AacpEvent.Mode(ListeningMode.ADAPTIVE))
            assertEquals(PropertyValue.Number(4), repository.state.value.propertyEffective(CommandProperty.MODE)?.value)
        } finally { repository.close(); scope.cancel() }
    }

    @Test fun oldBooleanAfterEchoWindowContradictsWithoutSilenceRollback() = runBlocking {
        val scope = TestScope(); var now = 100L
        val repository = AirPodsRepository(scope, AirPodsClock { now })
        try {
            val g = ready(repository, FakeTransport())
            repository.setConversation(false); repository.sent(CommandProperty.CONVERSATION)
            repository.setConversation(true); repository.sent(CommandProperty.CONVERSATION)
            now += 1_001
            assertEquals(DesiredStatus.SENT, repository.state.value.desired[CommandProperty.CONVERSATION]?.status)
            assertEquals(PropertyValue.Number(1), repository.state.value.propertyEffective(CommandProperty.CONVERSATION)?.value)
            repository.receive(g, AacpEvent.ConversationSetting(false))
            assertEquals(CommandOutcome.OBSERVED_OTHER, repository.state.value.outcomes[CommandProperty.CONVERSATION])
            assertEquals(PropertyValue.Number(2), repository.state.value.propertyEffective(CommandProperty.CONVERSATION)?.value)
        } finally { repository.close(); scope.cancel() }
    }

    @Test fun threeModesExplainOnlyOneEchoPerEarlierSendWithinTheWindow() = runBlocking {
        val scope = TestScope(); var now = 100L
        val repository = AirPodsRepository(scope, AirPodsClock { now })
        try {
            val g = ready(repository, FakeTransport())
            for (mode in listOf(ListeningMode.ANC, ListeningMode.TRANSPARENCY, ListeningMode.ADAPTIVE)) {
                repository.setMode(mode); repository.sent(CommandProperty.MODE); now += 10
            }
            for (mode in listOf(ListeningMode.ANC, ListeningMode.TRANSPARENCY)) {
                repository.receive(g, AacpEvent.Mode(mode))
                assertEquals(DesiredStatus.SENT, repository.state.value.desired[CommandProperty.MODE]?.status)
                assertEquals(PropertyValue.Number(4), repository.state.value.propertyEffective(CommandProperty.MODE)?.value)
            }
            repository.receive(g, AacpEvent.Mode(ListeningMode.ANC))
            assertEquals(DesiredStatus.CONTRADICTED, repository.state.value.desired[CommandProperty.MODE]?.status)
            assertEquals(PropertyValue.Number(2), repository.state.value.propertyEffective(CommandProperty.MODE)?.value)
        } finally { repository.close(); scope.cancel() }
    }
    @Test fun booleanLateEchoInsideWindowIsConsumedAndTheNextOffWins() = runBlocking {
        val scope = TestScope(); var now = 10L
        val repository = AirPodsRepository(scope, AirPodsClock { now })
        try {
            val g = ready(repository, FakeTransport())
            repository.setConversation(false); repository.sent(CommandProperty.CONVERSATION)
            repository.setConversation(true); repository.sent(CommandProperty.CONVERSATION)
            now += 100
            repository.receive(g, AacpEvent.ConversationSetting(false))
            assertEquals(DesiredStatus.SENT, repository.state.value.desired[CommandProperty.CONVERSATION]?.status)
            assertEquals(false, repository.state.value.conversation?.value)
            assertEquals(PropertyValue.Number(1), repository.state.value.propertyEffective(CommandProperty.CONVERSATION)?.value)
            repository.receive(g, AacpEvent.ConversationSetting(false))
            assertEquals(DesiredStatus.CONTRADICTED, repository.state.value.desired[CommandProperty.CONVERSATION]?.status)
            assertEquals(PropertyValue.Number(2), repository.state.value.propertyEffective(CommandProperty.CONVERSATION)?.value)
        } finally { repository.close(); scope.cancel() }
    }

    @Test fun eventOverflowClosesGenerationExplicitlyInsteadOfDroppingOrBlockingReader() = runBlocking {
        val scope = TestScope(); val repository = AirPodsRepository(scope, AirPodsClock { 100 })
        val socket = FakeTransport(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        try {
            ready(repository, socket)
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                repository.events.collect { entered.complete(Unit); release.await() }
            }
            val frame = byteArrayOf(4, 0, 4, 0, 6, 0, 0, 0)
            socket.deliver(frame); withTimeout(2_000) { entered.await() }
            val g = repository.state.value.generation
            repeat(AirPodsRepository.EVENT_CAPACITY + 1) { socket.deliver(frame) }
            repository.await(AacpStage.ERROR)
            assertTrue(repository.state.value.generation > g)
            assertTrue(repository.state.value.diagnostic.contains("EVENT_BUFFER_OVERFLOW"))
            assertTrue(socket.closed.get() > 0)
            collector.cancelAndJoin()
        } finally { release.complete(Unit); repository.close(); scope.cancel() }
    }
    @Test fun captureRecordsPhysicalControlTxAndFollowingRxInTheSameRing() = runBlocking {
        val scope = TestScope(); val repository = AirPodsRepository(scope, AirPodsClock { 100 })
        val socket = FakeTransport(); val capture = dev.lelonio.square.airpods.runtime.AirPodsRawRxBuffer({ 100 })
        try {
            ready(repository, socket)
            dev.lelonio.square.airpods.runtime.AirPodsRxCapture.install(capture)
            assertTrue(repository.setConversation(true)); repository.sent(CommandProperty.CONVERSATION)
            socket.deliver(byteArrayOf(4, 0, 4, 0, 9, 0, 0x28, 2, 0, 0, 0))
            withTimeout(2_000) { repository.state.first { it.conversation?.value == false } }
            val rows = capture.export().lineSequence().filter { it.contains("op=9 id=28") }.toList()
            assertEquals(2, rows.size)
            assertTrue(rows[0].contains("TX op=9 id=28 len=4 bytes=01000000"))
            assertTrue(rows[1].contains("RX op=9 id=28 len=4 bytes=02000000"))
        } finally {
            dev.lelonio.square.airpods.runtime.AirPodsRxCapture.remove(capture)
            repository.close(); scope.cancel()
        }
    }

    @Test fun blockedConsumerDoesNotBlockReaderAndCoalescedHeadsKeepIngressTimestampAndOrder() = runBlocking {
        val scope = TestScope(); val now = java.util.concurrent.atomic.AtomicLong(100)
        val repository = AirPodsRepository(scope, AirPodsClock { now.get() }); val socket = FakeTransport()
        val release = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
        try {
            ready(repository, socket); repository.setHeadTracking(HeadPacketFormat.STANDARD, true)
            withTimeout(2_000) { repository.state.first { it.activePacketFormat != null } }
            val delivery = async(start = CoroutineStart.UNDISPATCHED) {
                repository.events.filter { it.value is AacpEvent.Head }.take(256).map {
                    entered.complete(Unit); release.await(); now.incrementAndGet()
                    (it.value as AacpEvent.Head).sample.orientation1 to it.atMs
                }.toList()
            }
            val sdu = (0 until 256).fold(ByteArray(0)) { bytes, i ->
                bytes + ByteArray(70).also { frame ->
                    byteArrayOf(4,0,4,0,0x17,0,0,0,0x10,0,0x44,0).copyInto(frame)
                    frame[43] = i.toByte()
                }
            }
            socket.deliver(sdu); withTimeout(2_000) { entered.await() }
            withTimeout(2_000) { repository.headSamples.first { it?.value?.orientation1 == 255 } }
            release.complete(Unit)
            val received = withTimeout(2_000) { delivery.await() }
            assertEquals((0 until 256).toList(), received.map { it.first })
            assertEquals(setOf(100L), received.map { it.second }.toSet())
        } finally { release.complete(Unit); repository.close(); scope.cancel() }
    }

    @Test fun rxDuringWriteIsNotOverwrittenByLocalSendCompletion() = runBlocking {
        val scope = TestScope(); val repository = AirPodsRepository(scope, AirPodsClock { 100 })
        val gate = CountDownLatch(1); val socket = FakeTransport(writeGate = gate)
        try {
            val g = ready(repository, socket); socket.blockWrites = true
            repository.setMode(ListeningMode.ANC)
            withTimeout(2_000) { while (socket.inWrite.get() == 0) delay(1) }
            repository.receive(g, AacpEvent.Mode(ListeningMode.TRANSPARENCY))
            gate.countDown()
            withTimeout(2_000) { repository.state.first { it.pending.isEmpty() } }
            assertEquals(DesiredStatus.CONTRADICTED, repository.state.value.desired[CommandProperty.MODE]?.status)
            assertEquals(PropertyValue.Number(3), repository.state.value.propertyEffective(CommandProperty.MODE)?.value)
        } finally { gate.countDown(); repository.close(); scope.cancel() }
    }

    @Test fun rxAtWriterAdmissionBeforeSocketCallIsNotAnEchoOfThatWrite() = runBlocking {
        val scope = TestScope()
        lateinit var repository: AirPodsRepository
        repository = AirPodsRepository(scope, AirPodsClock { 100 }, observer = AirPodsQualificationObserver { event ->
            if (event.step == QualificationStep.TX && event.phase == QualificationPhase.REQUESTED &&
                event.property == CommandProperty.MODE && event.flags == 0)
                repository.receive(event.generation, AacpEvent.Mode(ListeningMode.ANC))
        })
        try {
            ready(repository, FakeTransport())
            repository.setMode(ListeningMode.ANC); repository.sent(CommandProperty.MODE)
            assertEquals(PropertyValue.Number(2), repository.state.value.propertyObserved(CommandProperty.MODE)?.value)
            assertEquals(DesiredStatus.SENT, repository.state.value.desired[CommandProperty.MODE]?.status)
            assertNull(repository.state.value.outcomes[CommandProperty.MODE])
        } finally { repository.close(); scope.cancel() }
    }

    @Test fun failureOfAlreadyEnteredACannotRejectNewerQueuedB() = runBlocking {
        val scope = TestScope(); val repository = AirPodsRepository(scope, AirPodsClock { 100 })
        val gate = CountDownLatch(1); val socket = FakeTransport(writeGate = gate)
        try {
            ready(repository, socket); socket.blockWrites = true
            repository.setMode(ListeningMode.ANC)
            withTimeout(2_000) { while (socket.inWrite.get() == 0) delay(1) }
            repository.setMode(ListeningMode.ADAPTIVE)
            val latest = repository.state.value.desired.getValue(CommandProperty.MODE).sequence
            socket.failWrites = true; gate.countDown(); repository.await(AacpStage.ERROR)
            assertEquals(latest, repository.state.value.desired.getValue(CommandProperty.MODE).sequence)
            assertEquals(DesiredStatus.UNCERTAIN, repository.state.value.desired[CommandProperty.MODE]?.status)
            assertEquals(PropertyValue.Number(4), repository.state.value.propertyEffective(CommandProperty.MODE)?.value)
            assertEquals(CommandOutcome.CLOSED, repository.state.value.outcomes[CommandProperty.MODE])
            assertTrue(synchronized(socket.writes) { socket.writes.none { it.getOrNull(4) == 9 } })
        } finally { gate.countDown(); repository.close(); scope.cancel() }
    }

    @Test fun realSendFailureRetractsOnlyFailedIntentAndKeepsLastRx() = runBlocking {
        val scope = TestScope(); val repository = AirPodsRepository(scope, AirPodsClock { 100 })
        val socket = FakeTransport()
        try {
            val g = ready(repository, socket)
            repository.receive(g, AacpEvent.Mode(ListeningMode.TRANSPARENCY))
            socket.failWrites = true
            assertTrue(repository.setMode(ListeningMode.ANC))
            repository.await(AacpStage.ERROR)
            assertEquals(CommandOutcome.SEND_FAILED, repository.state.value.outcomes[CommandProperty.MODE])
            assertEquals(DesiredStatus.FAILED, repository.state.value.desired[CommandProperty.MODE]?.status)
            assertEquals(PropertyValue.Number(3), repository.state.value.propertyEffective(CommandProperty.MODE)?.value)
            assertFalse(repository.setMode(ListeningMode.ANC))
        } finally { repository.close(); scope.cancel() }
    }

    @Test fun disconnectAndFreshGenerationKeepUncertaintyWithoutReplayAndIgnoreOldRx() = runBlocking {
        val scope = TestScope(); val repository = AirPodsRepository(scope, AirPodsClock { 100 })
        val pair = PairAddress("02:00:00:00:00:71")
        try {
            val retired = ready(repository, FakeTransport(), pair)
            repository.setMode(ListeningMode.ADAPTIVE); repository.sent(CommandProperty.MODE)
            repository.close()
            assertEquals(DesiredStatus.UNCERTAIN, repository.state.value.desired[CommandProperty.MODE]?.status)
            val socket = FakeTransport(); val g = ready(repository, socket, pair)
            assertNull(repository.state.value.propertyObserved(CommandProperty.MODE))
            assertEquals(PropertyValue.Number(4), repository.state.value.propertyEffective(CommandProperty.MODE)?.value)
            repository.receive(retired, AacpEvent.Mode(ListeningMode.OFF))
            assertNull(repository.state.value.mode)
            assertTrue(synchronized(socket.writes) { socket.writes.none { it.getOrNull(4) == 9 } })
            repository.receive(g, AacpEvent.Mode(ListeningMode.TRANSPARENCY))
            assertEquals(PropertyValue.Number(3), repository.state.value.propertyEffective(CommandProperty.MODE)?.value)
        } finally { repository.close(); scope.cancel() }
    }

    @Test fun casePercentSurvivesNullAndRecreationButNeverBecomesLiveChargingOrAnotherPairsValue() = runBlocking {
        val scope = TestScope(); val store = AirPodsPairValueStore()
        val pair = PairAddress("02:00:00:00:00:71"); val other = PairAddress("02:00:00:00:00:72")
        val repository = AirPodsRepository(scope, AirPodsClock { 100 }, valueStore = store, wallClock = AirPodsClock { 1234 })
        val restored = AirPodsRepository(scope, AirPodsClock { 1 }, valueStore = store)
        try {
            val g = ready(repository, FakeTransport(), pair)
            repository.receive(g, AacpEvent.Battery(mapOf(8 to BatteryValue(0, 1))))
            repository.receive(g, AacpEvent.Battery(mapOf(8 to BatteryValue(null, 4))))
            assertNull(repository.state.value.liveBattery(8, 100)?.percent)
            assertEquals(SavedCasePercent(0, 1234), repository.state.value.cachedValues.casePercent)
            repository.setEq(PropertyValue.Eq(true, 20, 40, 60)); repository.sent(CommandProperty.EQ)
            withTimeout(2_000) { while (store.load(pair).getOrThrow().choices[CommandProperty.EQ]?.status != DesiredStatus.SENT) delay(1) }
            ready(restored, FakeTransport(), pair)
            assertEquals(SavedCasePercent(0, 1234), restored.state.value.cachedValues.casePercent)
            assertNull(restored.state.value.liveBattery(8, 1))
            assertNull(restored.state.value.propertyObserved(CommandProperty.EQ))
            assertEquals(PropertyValue.Eq(true, 20, 40, 60), restored.state.value.propertyEffective(CommandProperty.EQ)?.value)
            ready(restored, FakeTransport(), other)
            assertNull(restored.state.value.cachedValues.casePercent)
            assertEquals(PropertyValue.Eq(false, 50, 50, 50), restored.state.value.propertyEffective(CommandProperty.EQ)?.value)
        } finally { repository.close(); restored.close(); scope.cancel() }
    }

    @Test fun forgetCannotBeUndoneByDelayedCachePersistence() = runBlocking {
        val scope = TestScope(); val store = AirPodsPairValueStore()
        val repository = AirPodsRepository(scope, AirPodsClock { 100 }, valueStore = store)
        val pair = PairAddress("02:00:00:00:00:73")
        try {
            val g = ready(repository, FakeTransport(), pair)
            repository.receive(g, AacpEvent.Battery(mapOf(8 to BatteryValue(82, 2))))
            repository.setMode(ListeningMode.ADAPTIVE); repository.sent(CommandProperty.MODE)
            repository.forgetPairValues(pair).getOrThrow()
            repository.close()
            ready(repository, FakeTransport(), pair)
            assertEquals(AirPodsPairValues(), repository.state.value.cachedValues)
            assertTrue(repository.state.value.desired.isEmpty())
            assertEquals(AirPodsPairValues(), store.load(pair).getOrThrow())
        } finally { repository.close(); scope.cancel() }
    }

    @Test fun invalidWriteDoesNotEraseAnAcceptedIntentOrItsEffectiveValue() = runBlocking {
        val scope = TestScope(); val repository = AirPodsRepository(scope, AirPodsClock { 100 })
        try {
            ready(repository, FakeTransport())
            repository.setProperty(CommandProperty.AUTO_ANC_STRENGTH, 61)
            repository.sent(CommandProperty.AUTO_ANC_STRENGTH)
            val intent = repository.state.value.desired[CommandProperty.AUTO_ANC_STRENGTH]
            assertFalse(repository.setProperty(CommandProperty.AUTO_ANC_STRENGTH, 101))
            assertEquals(intent, repository.state.value.desired[CommandProperty.AUTO_ANC_STRENGTH])
            assertEquals(PropertyValue.Number(61), repository.state.value.propertyEffective(CommandProperty.AUTO_ANC_STRENGTH)?.value)
        } finally { repository.close(); scope.cancel() }
    }

    @Test fun legacyConstructWithoutPairCannotTransferCachedValuesToAnotherSession() = runBlocking {
        val scope = TestScope(); val repository = AirPodsRepository(scope, AirPodsClock { 100 })
        try {
            val g = ready(repository, FakeTransport())
            repository.receive(g, AacpEvent.Battery(mapOf(8 to BatteryValue(85, 2))))
            repository.setEq(PropertyValue.Eq(true, 1, 2, 3)); repository.sent(CommandProperty.EQ)
            repository.close(); ready(repository, FakeTransport())
            assertNull(repository.state.value.cachedValues.casePercent)
            assertTrue(repository.state.value.desired.isEmpty())
            assertEquals(PropertyValue.Eq(false, 50, 50, 50), repository.state.value.propertyEffective(CommandProperty.EQ)?.value)
        } finally { repository.close(); scope.cancel() }
    }

    @Test fun everyHeadSampleReachesOrderedEventsWithoutChangingGeneralUiState() = runBlocking {
        val scope = TestScope(); val repository = AirPodsRepository(scope, AirPodsClock { 100 })
        val socket = FakeTransport()
        try {
            ready(repository, socket); repository.setHeadTracking(HeadPacketFormat.STANDARD, true)
            withTimeout(2_000) { repository.state.first { it.activePacketFormat == HeadPacketFormat.STANDARD } }
            val before = repository.state.value
            val delivery = async(start = CoroutineStart.UNDISPATCHED) {
                repository.events.filter { it.value is AacpEvent.Head }.take(24).map {
                    delay(1) // deliberately slower than the socket producer
                    ((it.value as AacpEvent.Head).sample.orientation1)
                }.toList()
            }
            repeat(24) { i ->
                val frame = ByteArray(70)
                byteArrayOf(4,0,4,0,0x17,0,0,0,0x10,0,0x44,0).copyInto(frame)
                frame[43] = i.toByte(); socket.deliver(frame)
            }
            assertEquals((0 until 24).toList(), withTimeout(3_000) { delivery.await() })
            assertSame(before, repository.state.value)
            assertEquals(23, repository.headSamples.value?.value?.orientation1)
        } finally { repository.close(); scope.cancel() }
    }
}
