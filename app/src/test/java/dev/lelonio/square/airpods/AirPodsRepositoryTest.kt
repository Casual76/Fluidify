// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods

import dev.lelonio.square.airpods.protocol.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AirPodsRepositoryTest {
    private class FakeTransport(private val connectGate: CountDownLatch? = null, val writeGate: CountDownLatch? = null) : AacpTransport {
        val closed = AtomicInteger()
        val connectStarted = AtomicInteger()
        private val queue = LinkedBlockingQueue<ByteArray>()
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
    private suspend fun ready(repository: AirPodsRepository, socket: FakeTransport): Long {
        repository.construct { socket }; repository.await(AacpStage.CONSTRUCTED)
        repository.connect(); repository.await(AacpStage.CONNECTED)
        repository.handshake(); repository.await(AacpStage.HANDSHAKE_SENT)
        repository.subscribe(); repository.await(AacpStage.SUBSCRIBED)
        val generation = repository.state.value.generation
        repository.receive(generation, AacpEvent.Information(AirPodsIdentity("A3063", "Apple Inc.", "fixture")))
        return generation
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
    @Test fun receivedStateIsSeparateFromPendingAndOtherControllerWins() = runBlocking {
        val timerScope = TestScope()
        val repository = AirPodsRepository(timerScope, AirPodsClock { timerScope.testScheduler.currentTime })
        try {
            val generation = ready(repository, FakeTransport())
            repository.receive(generation, AacpEvent.Mode(ListeningMode.TRANSPARENCY))
            assertTrue(repository.setMode(ListeningMode.ANC))
            assertFalse(repository.setMode(ListeningMode.ADAPTIVE))
            assertEquals(ListeningMode.TRANSPARENCY, repository.state.value.mode?.value)
            // Unrelated activity never confirms configuration.
            assertTrue(repository.setConversation(true))
            repository.receive(generation, AacpEvent.ConversationActivity(1))
            assertTrue(CommandProperty.CONVERSATION in repository.state.value.pending)
            repository.receive(generation, AacpEvent.Mode(ListeningMode.ADAPTIVE))
            assertEquals(CommandOutcome.OBSERVED_OTHER, repository.state.value.outcomes[CommandProperty.MODE])
            repository.receive(generation, AacpEvent.ConversationSetting(true))
            assertEquals(CommandOutcome.OBSERVED_MATCH, repository.state.value.outcomes[CommandProperty.CONVERSATION])
            assertTrue(repository.state.value.pending.isEmpty())
        } finally { repository.close(); timerScope.cancel() }
    }
    @Test fun fiveSecondTimeoutIsNotRejectionAndLateStateStillWins() = runBlocking {
        val timers = TestScope()
        val repository = AirPodsRepository(timers, AirPodsClock { timers.testScheduler.currentTime })
        try {
            val generation = ready(repository, FakeTransport())
            assertTrue(repository.setMode(ListeningMode.ANC))
            timers.runCurrent(); timers.advanceTimeBy(4_999); timers.runCurrent()
            assertTrue(CommandProperty.MODE in repository.state.value.pending)
            timers.advanceTimeBy(1); timers.runCurrent()
            assertEquals(CommandOutcome.NOT_CONFIRMED, repository.state.value.outcomes[CommandProperty.MODE])
            repository.receive(generation, AacpEvent.Mode(ListeningMode.ANC))
            assertEquals(ListeningMode.ANC, repository.state.value.mode?.value)
            assertEquals(CommandOutcome.NOT_CONFIRMED, repository.state.value.outcomes[CommandProperty.MODE])
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

    @Test fun all19PropertiesHaveIndependentPendingAndOnlyRelevantRxConfirms() = runBlocking {
        val timers=TestScope();val repository=AirPodsRepository(timers,AirPodsClock { timers.testScheduler.currentTime })
        try {
            val g=ready(repository,FakeTransport())
            val controls=AirPodsProperties.controls.filterNot { it.internalDependency }
            for (spec in controls) {
                val v=if(spec.encoding==ControlEncoding.MODE_MASK) 3 else spec.range.first
                assertTrue(spec.catalogId,repository.setProperty(spec.property,v))
                assertNull(repository.state.value.propertyObserved(spec.property))
                assertFalse(repository.setProperty(spec.property,v))
            }
            assertEquals(19,repository.state.value.pending.size)
            repository.receive(g,AacpEvent.Unknown(9,11,"synthetic malformed"))
            repository.receive(g,AacpEvent.ConversationActivity(1))
            assertEquals(19,repository.state.value.pending.size)
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
    @Test fun renameAndEqRequireRxAndHaveIndependentTimeouts() = runBlocking {
        val timers=TestScope();val repository=AirPodsRepository(timers,AirPodsClock { timers.testScheduler.currentTime })
        try {
            val g=ready(repository,FakeTransport())
            assertTrue(repository.rename("Cuffie ?"));assertTrue(repository.setEq(PropertyValue.Eq(true,0,50,100)))
            repository.receive(g,AacpEvent.Information(AirPodsIdentity("A3063","Apple Inc.","fixture",name="Cuffie ?")))
            assertEquals(CommandOutcome.OBSERVED_MATCH,repository.state.value.outcomes[CommandProperty.NAME])
            assertNull(repository.state.value.propertyObserved(CommandProperty.EQ))
            timers.runCurrent();timers.advanceTimeBy(5000);timers.runCurrent()
            assertEquals(CommandOutcome.NOT_CONFIRMED,repository.state.value.outcomes[CommandProperty.EQ])
        } finally { repository.close();timers.cancel() }
    }
}
