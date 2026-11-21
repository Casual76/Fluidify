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
    /** FIX-04 (replaces "silence never rolls back"): SENT lasts 5s without a matching RX, then
     * NOT_CONFIRMED with that outcome, and effective falls back to the last RX. A late RX still settles it. */
    @Test fun sentWithoutRxBecomesNotConfirmedAfterFiveSecondsAndFallsBackToObserved() = runBlocking {
        val timers = TestScope()
        val repository = AirPodsRepository(timers, AirPodsClock { timers.testScheduler.currentTime })
        try {
            val generation = ready(repository, FakeTransport())
            repository.receive(generation, AacpEvent.Mode(ListeningMode.TRANSPARENCY))
            assertTrue(repository.setMode(ListeningMode.ANC))
            repository.sent(CommandProperty.MODE)
            timers.runCurrent(); timers.advanceTimeBy(4_999); timers.runCurrent()
            assertEquals(DesiredStatus.SENT, repository.state.value.desired[CommandProperty.MODE]?.status)
            assertEquals(EffectiveProperty(PropertyValue.Number(2), EffectiveSource.DESIRED),
                repository.state.value.propertyEffective(CommandProperty.MODE))
            timers.advanceTimeBy(1); timers.runCurrent()
            assertEquals(DesiredStatus.NOT_CONFIRMED, repository.state.value.desired[CommandProperty.MODE]?.status)
            assertEquals(CommandOutcome.NOT_CONFIRMED, repository.state.value.outcomes[CommandProperty.MODE])
            assertEquals(EffectiveProperty(PropertyValue.Number(3), EffectiveSource.OBSERVED),
                repository.state.value.propertyEffective(CommandProperty.MODE))
            assertEquals(PropertyStatus.OBSERVED, repository.state.value.propertyView(CommandProperty.MODE)?.status)
            repository.receive(generation, AacpEvent.Mode(ListeningMode.ANC))
            assertEquals(ListeningMode.ANC, repository.state.value.mode?.value)
            assertEquals(DesiredStatus.CONFIRMED, repository.state.value.desired[CommandProperty.MODE]?.status)
            assertEquals(CommandOutcome.OBSERVED_MATCH, repository.state.value.outcomes[CommandProperty.MODE])
            repository.receive(generation, AacpEvent.Mode(ListeningMode.TRANSPARENCY))
            assertEquals(PropertyValue.Number(3), repository.state.value.propertyEffective(CommandProperty.MODE)?.value)
        } finally { repository.close(); timers.cancel() }
    }
    @Test fun notConfirmedWithoutAnyRxFallsBackToUnknownForASwitch() = runBlocking {
        val timers = TestScope()
        val repository = AirPodsRepository(timers, AirPodsClock { timers.testScheduler.currentTime })
        try {
            ready(repository, FakeTransport())
            assertTrue(repository.setProperty(CommandProperty.EAR_DETECTION_CONFIG, 2))
            repository.sent(CommandProperty.EAR_DETECTION_CONFIG)
            timers.runCurrent(); timers.advanceTimeBy(5_000); timers.runCurrent()
            assertEquals(CommandOutcome.NOT_CONFIRMED, repository.state.value.outcomes[CommandProperty.EAR_DETECTION_CONFIG])
            assertNull(repository.state.value.propertyEffective(CommandProperty.EAR_DETECTION_CONFIG))
            assertEquals(PropertyStatus.NOT_CONFIRMED, repository.state.value.propertyView(CommandProperty.EAR_DETECTION_CONFIG)?.status)
        } finally { repository.close(); timers.cancel() }
    }
    /** A deadline belongs to one send: a newer send of the same property restarts the window. */
    @Test fun aNewerSendIsNotExpiredByTheOlderDeadline() = runBlocking {
        val timers = TestScope()
        val repository = AirPodsRepository(timers, AirPodsClock { timers.testScheduler.currentTime })
        try {
            ready(repository, FakeTransport())
            repository.setMode(ListeningMode.ANC); repository.sent(CommandProperty.MODE)
            timers.runCurrent(); timers.advanceTimeBy(3_000); timers.runCurrent()
            repository.setMode(ListeningMode.ADAPTIVE)
            withTimeout(2_000) { repository.state.first { it.desired[CommandProperty.MODE]?.let { d ->
                d.status == DesiredStatus.SENT && d.requested == PropertyValue.Number(4) } == true } }
            timers.runCurrent(); timers.advanceTimeBy(2_500); timers.runCurrent()
            assertEquals(DesiredStatus.SENT, repository.state.value.desired[CommandProperty.MODE]?.status)
            assertNull(repository.state.value.outcomes[CommandProperty.MODE])
            timers.advanceTimeBy(2_500); timers.runCurrent()
            assertEquals(DesiredStatus.NOT_CONFIRMED, repository.state.value.desired[CommandProperty.MODE]?.status)
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

    @Test fun all20GenericPropertiesHaveIndependentIntentsAndOnlyRelevantRxReconciles() = runBlocking {
        val timers=TestScope();val repository=AirPodsRepository(timers,AirPodsClock { timers.testScheduler.currentTime })
        try {
            val g=ready(repository,FakeTransport())
            // HEARING_AID is a paired write with its own dedicated tests, not a generic scalar.
            val controls=AirPodsProperties.controls.filterNot { it.internalDependency || it.property == CommandProperty.HEARING_AID }
            for (spec in controls) {
                val v=if(spec.encoding==ControlEncoding.MODE_MASK) 3 else spec.range.first
                if (spec.property == CommandProperty.LISTENING_MODE_CONFIGS)
                    repository.receive(g, AacpEvent.Property(spec.property, PropertyValue.Number(6)))
                assertTrue(spec.catalogId,repository.setProperty(spec.property,v))
                val observed = repository.state.value.propertyObserved(spec.property)?.value
                assertEquals(if (spec.property == CommandProperty.LISTENING_MODE_CONFIGS) PropertyValue.Number(6) else null, observed)
                assertTrue(repository.setProperty(spec.property,v)) // FIX-02: a repeat is resent, never swallowed
            }
            withTimeout(2_000) { repository.state.first { it.pending.isEmpty() } }
            assertEquals(20,repository.state.value.desired.size)
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
    /** FIX-03 (replaces "old identity cannot enable writes"): an unknown model still writes, as in
     * pinned LibrePods; only a retired generation cannot. */
    @Test fun candidateAndObservedAreSeparateAndUnknownModelStillWritesButClosedCannot() = runBlocking {
        val timers=TestScope();val repository=AirPodsRepository(timers,AirPodsClock { 0 })
        val socket=FakeTransport()
        try {
            val g=ready(repository,socket)
            assertTrue(repository.state.value.canAttemptWrite(CommandProperty.CHIME_VOLUME,PropertyValue.Number(50)))
            assertNull(repository.state.value.propertyObserved(CommandProperty.CHIME_VOLUME))
            repository.receive(g,AacpEvent.Mode(ListeningMode.ANC))
            assertNull(repository.state.value.propertyObserved(CommandProperty.CHIME_VOLUME))
            repository.receive(g,AacpEvent.Information(AirPodsIdentity("A9999","Apple Inc.","fixture")))
            assertTrue(repository.setMode(ListeningMode.ADAPTIVE))
            repository.sent(CommandProperty.MODE)
            assertTrue(socket.writes.contains(AacpCodec.mode(ListeningMode.ADAPTIVE).map { it.toInt() and 255 }))
            assertFalse(repository.state.value.modelOffers(CommandProperty.MODE,PropertyValue.Number(4)))
            repository.close()
            repository.receive(g,AacpEvent.Information(AirPodsIdentity("A3063","Apple Inc.","fixture")))
            assertFalse(repository.setProperty(CommandProperty.CHIME_VOLUME,50))
        } finally { repository.close();timers.cancel() }
    }
    /** FIX-07 (replaces "cycle mask requires current-generation RX"): with no RX this session the
     * cycle is still written; the saved mask (else 0) is the base the toggle builds on. */
    @Test fun cycleMaskIsWrittenWithoutSessionRxAndTheSavedMaskIsTheBase() = runBlocking {
        val scope = TestScope(); val store = AirPodsPairValueStore()
        val pair = PairAddress("02:00:00:00:00:1A")
        val property = CommandProperty.LISTENING_MODE_CONFIGS
        store.save(pair, AirPodsPairValues(observed = mapOf(property to SavedProperty(PropertyValue.Number(12), 1)))).getOrThrow()
        val repository = AirPodsRepository(scope, AirPodsClock { 100 }, valueStore = store)
        val socket = FakeTransport()
        try {
            ready(repository, socket, pair)
            assertNull(repository.state.value.propertyObserved(property))
            assertEquals(EffectiveProperty(PropertyValue.Number(12), EffectiveSource.SAVED_OBSERVATION),
                repository.state.value.propertyEffective(property))
            assertEquals(12, repository.state.value.listeningModeMaskBase())
            val toggled = repository.state.value.listeningModeMaskBase() xor 2
            assertTrue(repository.setProperty(property, toggled))
            repository.sent(property)
            assertTrue(socket.writes.contains(AacpCodec.property(property, 14).map { it.toInt() and 255 }))
            // The next toggle builds on the mask in flight, not on the stale saved one.
            assertEquals(14, repository.state.value.listeningModeMaskBase())
            socket.deliver(AacpCodec.property(property, 7))
            withTimeout(2_000) { repository.state.first { it.propertyObserved(property) != null } }
            assertEquals(7, repository.state.value.listeningModeMaskBase())
        } finally { repository.close(); scope.cancel() }
    }
    @Test fun cycleMaskBaseWithNothingReceivedIsZeroLikeUpstream() = runBlocking {
        val scope = TestScope(); val repository = AirPodsRepository(scope, AirPodsClock { 100 })
        val socket = FakeTransport()
        try {
            ready(repository, socket)
            assertEquals(0, repository.state.value.listeningModeMaskBase())
            // A one-bud model without ADAPTIVE_AUDIO, Off bit set while ALLOW_OFF reads disabled:
            // offered or not, the requested cycle leaves (FIX-26).
            val g = repository.state.value.generation
            repository.receive(g, AacpEvent.Information(AirPodsIdentity("A2084", "Apple Inc.", "fixture")))
            repository.receive(g, AacpEvent.Property(CommandProperty.ALLOW_OFF_OPTION, PropertyValue.Number(2)))
            assertFalse(repository.state.value.modelOffers(CommandProperty.LISTENING_MODE_CONFIGS, PropertyValue.Number(9)))
            assertTrue(repository.setProperty(CommandProperty.LISTENING_MODE_CONFIGS, 9))
            assertTrue(repository.setMode(ListeningMode.OFF))
            assertTrue(repository.setMode(ListeningMode.ADAPTIVE))
            repository.sent(CommandProperty.MODE)
            withTimeout(2_000) { repository.state.first { it.pending.isEmpty() } }
            assertTrue(socket.writes.contains(AacpCodec.property(CommandProperty.LISTENING_MODE_CONFIGS, 9).map { it.toInt() and 255 }))
            assertTrue(socket.writes.contains(AacpCodec.mode(ListeningMode.ADAPTIVE).map { it.toInt() and 255 }))
        } finally { repository.close(); scope.cancel() }
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
    /** FIX-04 (replaces "rename and EQ remain effective without RX"): the name is confirmed by
     * INFORMATION; an EQ with no 0x63 RX within 5s is NOT_CONFIRMED and effective is no longer it. */
    @Test fun renameIsConfirmedByInformationAndUnconfirmedEqStopsWinningAfterFiveSeconds() = runBlocking {
        val timers=TestScope();val repository=AirPodsRepository(timers,AirPodsClock { timers.testScheduler.currentTime })
        try {
            val g=ready(repository,FakeTransport())
            assertTrue(repository.rename("Cuffie ?"));assertTrue(repository.setEq(PropertyValue.Eq(true,0,50,100)))
            repository.sent(CommandProperty.NAME); repository.sent(CommandProperty.EQ)
            repository.receive(g,AacpEvent.Information(AirPodsIdentity("A3063","Apple Inc.","fixture",name="Cuffie ?")))
            assertEquals(CommandOutcome.OBSERVED_MATCH,repository.state.value.outcomes[CommandProperty.NAME])
            assertNull(repository.state.value.propertyObserved(CommandProperty.EQ))
            assertEquals(PropertyValue.Eq(true,0,50,100), repository.state.value.propertyEffective(CommandProperty.EQ)?.value)
            timers.runCurrent();timers.advanceTimeBy(5000);timers.runCurrent()
            assertEquals(CommandOutcome.NOT_CONFIRMED,repository.state.value.outcomes[CommandProperty.EQ])
            assertEquals(CommandOutcome.OBSERVED_MATCH,repository.state.value.outcomes[CommandProperty.NAME])
            assertEquals(EffectiveProperty(PropertyValue.Eq(false,50,50,50), EffectiveSource.DEFAULT),
                repository.state.value.propertyEffective(CommandProperty.EQ))
            assertEquals(PropertyValue.Name("Cuffie ?"), repository.state.value.propertyEffective(CommandProperty.NAME)?.value)
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

    @Test fun sduReadBeforeWriteCannotContradictEvenWhenParsingFinishesLater() = runBlocking {
        val scope = TestScope()
        val now = java.util.concurrent.atomic.AtomicLong(100)
        val parsing = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val repository = AirPodsRepository(scope, AirPodsClock(now::get), rxDiagnostics = AirPodsRxDiagnostics { _, frame ->
            if (frame.controlIdentifier == 0x0d && parsing.complete(Unit)) {
                check(release.await(4, java.util.concurrent.TimeUnit.SECONDS))
            }
        })
        val socket = FakeTransport()
        try {
            ready(repository, socket)
            val packet = AacpCodec.property(CommandProperty.MODE, ListeningMode.TRANSPARENCY.wire)
            socket.deliver(packet)
            withTimeout(2_000) { parsing.await() }
            now.set(300)
            assertTrue(repository.setMode(ListeningMode.ANC))
            repository.sent(CommandProperty.MODE)
            release.countDown()
            val published = withTimeout(2_000) { repository.state.first { it.mode?.atMs == 100L } }
            assertEquals(DesiredStatus.SENT, published.desired[CommandProperty.MODE]?.status)
            assertEquals(PropertyValue.Number(2), published.propertyEffective(CommandProperty.MODE)?.value)
            socket.deliver(packet)
            val contrary = withTimeout(2_000) { repository.state.first { it.mode?.atMs == 300L } }
            assertEquals(DesiredStatus.CONTRADICTED, contrary.desired[CommandProperty.MODE]?.status)
            assertEquals(PropertyValue.Number(3), contrary.propertyEffective(CommandProperty.MODE)?.value)
        } finally { release.countDown(); repository.close(); scope.cancel() }
    }

    @Test fun oldSendMustNotMaskCurrentContraryRx() = runBlocking {
        val scope = TestScope()
        val now = java.util.concurrent.atomic.AtomicLong(100)
        val socket = FakeTransport()
        val repository = AirPodsRepository(scope, AirPodsClock { now.get() })
        try {
            ready(repository, socket)
            assertTrue(repository.setMode(ListeningMode.ANC))
            repository.sent(CommandProperty.MODE)
            now.set(120_100)
            assertTrue(repository.setMode(ListeningMode.ADAPTIVE))
            repository.sent(CommandProperty.MODE)
            now.incrementAndGet()
            socket.deliver(AacpCodec.mode(ListeningMode.ANC))
            val state = withTimeout(2_000) { repository.state.first { it.mode?.value == ListeningMode.ANC } }
            assertEquals("An older send cannot explain the contrary observation two minutes later",
                PropertyValue.Number(2), state.propertyEffective(CommandProperty.MODE)?.value)
            assertEquals(CommandOutcome.OBSERVED_OTHER, state.outcomes[CommandProperty.MODE])
        } finally { repository.close(); scope.cancel() }
    }

    @Test fun lateEchoOfAIsConsumedOnceThenTheSameContraryValueWins() = runBlocking {
        val scope = TestScope()
        val now = java.util.concurrent.atomic.AtomicLong(100)
        val repository = AirPodsRepository(scope, AirPodsClock { now.get() })
        try {
            val g = ready(repository, FakeTransport())
            repository.setMode(ListeningMode.ANC); repository.sent(CommandProperty.MODE)
            now.set(250)
            repository.setMode(ListeningMode.ADAPTIVE); repository.sent(CommandProperty.MODE)
            now.set(300)
            repository.receive(g, AacpEvent.Mode(ListeningMode.ANC))
            assertEquals(ListeningMode.ANC, repository.state.value.mode?.value) // truthful D2
            assertEquals(PropertyValue.Number(4), repository.state.value.propertyEffective(CommandProperty.MODE)?.value)
            assertEquals(DesiredStatus.SENT, repository.state.value.desired[CommandProperty.MODE]?.status)
            now.incrementAndGet()
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
            // FIX-04: an UNCERTAIN intent is kept as a record but no longer shown as the value.
            assertEquals(EffectiveProperty(PropertyValue.Number(3), EffectiveSource.DEFAULT),
                repository.state.value.propertyEffective(CommandProperty.MODE))
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
            // FIX-04: the restored choice stays UNCERTAIN on record, never the displayed value.
            assertEquals(DesiredStatus.UNCERTAIN, repository.state.value.desired[CommandProperty.MODE]?.status)
            assertEquals(EffectiveProperty(PropertyValue.Number(3), EffectiveSource.DEFAULT),
                repository.state.value.propertyEffective(CommandProperty.MODE))
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
            // FIX-04: a restored choice is kept (UNCERTAIN) but not displayed as the device's EQ.
            assertEquals(DesiredStatus.UNCERTAIN, restored.state.value.desired[CommandProperty.EQ]?.status)
            assertEquals(PropertyValue.Eq(true, 20, 40, 60), restored.state.value.desired[CommandProperty.EQ]?.requested)
            assertEquals(EffectiveProperty(PropertyValue.Eq(false, 50, 50, 50), EffectiveSource.DEFAULT),
                restored.state.value.propertyEffective(CommandProperty.EQ))
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

    private fun FakeTransport.count(packet: ByteArray) =
        synchronized(writes) { writes.count { it == packet.map { b -> b.toInt() and 255 } } }

    /** FIX-02: the same value twice is two physical writes, also after the first one was SENT. */
    @Test fun identicalSetPropertyTwiceWritesTwice() = runBlocking {
        val scope = TestScope(); val repository = AirPodsRepository(scope, AirPodsClock { 100 })
        val socket = FakeTransport()
        try {
            ready(repository, socket)
            assertTrue(repository.setMode(ListeningMode.ANC)); repository.sent(CommandProperty.MODE)
            assertEquals(1, socket.count(AacpCodec.mode(ListeningMode.ANC)))
            val first = repository.state.value.desired.getValue(CommandProperty.MODE).sequence
            assertTrue(repository.setMode(ListeningMode.ANC))
            withTimeout(2_000) { while (socket.count(AacpCodec.mode(ListeningMode.ANC)) < 2) delay(1) }
            assertTrue(repository.state.value.desired.getValue(CommandProperty.MODE).sequence > first)
            for (i in 0 until 2) {
                assertTrue(repository.setProperty(CommandProperty.EAR_DETECTION_CONFIG, 1))
                repository.sent(CommandProperty.EAR_DETECTION_CONFIG)
            }
            withTimeout(2_000) { while (socket.count(AacpCodec.property(CommandProperty.EAR_DETECTION_CONFIG, 1)) < 2) delay(1) }
        } finally { repository.close(); scope.cancel() }
    }

    /** FIX-03: a connected socket is the gate. Before INFORMATION, and before SUBSCRIBED, it leaves. */
    @Test fun writeBeforeInformationAndBeforeSubscribedLeaves() = runBlocking {
        val scope = TestScope(); val repository = AirPodsRepository(scope, AirPodsClock { 100 })
        val socket = FakeTransport()
        try {
            repository.construct { socket }; repository.await(AacpStage.CONSTRUCTED)
            assertFalse(repository.setProperty(CommandProperty.CHIME_VOLUME, 30))
            repository.connect(); repository.await(AacpStage.CONNECTED)
            assertNull(repository.state.value.identityReceived)
            assertTrue(repository.setProperty(CommandProperty.CHIME_VOLUME, 30))
            repository.sent(CommandProperty.CHIME_VOLUME)
            assertEquals(1, socket.count(AacpCodec.property(CommandProperty.CHIME_VOLUME, 30)))
            repository.handshake(); repository.await(AacpStage.HANDSHAKE_SENT)
            repository.subscribe(); repository.await(AacpStage.SUBSCRIBED)
            assertNull(repository.state.value.identityReceived)
            assertTrue(repository.setMode(ListeningMode.TRANSPARENCY)); repository.sent(CommandProperty.MODE)
            assertEquals(1, socket.count(AacpCodec.mode(ListeningMode.TRANSPARENCY)))
        } finally { repository.close(); scope.cancel() }
    }

    /** Range validation stays local and refuses: nothing is queued or written. */
    @Test fun outOfRangeValuesAreStillRefusedLocally() = runBlocking {
        val scope = TestScope(); val repository = AirPodsRepository(scope, AirPodsClock { 100 })
        val socket = FakeTransport()
        try {
            ready(repository, socket)
            val before = synchronized(socket.writes) { socket.writes.size }
            assertFalse(repository.setProperty(CommandProperty.AUTO_ANC_STRENGTH, 101))
            assertFalse(repository.setProperty(CommandProperty.MODE, 5))
            assertFalse(repository.setProperty(CommandProperty.MODE, 0))
            assertFalse(repository.setProperty(CommandProperty.LISTENING_MODE_CONFIGS, 2)) // one mode is no cycle
            assertFalse(repository.setProperty(CommandProperty.EAR_DETECTION_CONFIG, 0))
            assertFalse(repository.setProperty(CommandProperty.EQ, PropertyValue.Number(1)))
            assertFalse(repository.rename("a".repeat(256)))
            assertTrue(repository.state.value.pending.isEmpty())
            assertTrue(repository.state.value.desired.isEmpty())
            assertEquals(before, synchronized(socket.writes) { socket.writes.size })
        } finally { repository.close(); scope.cancel() }
    }

    /** FIX-04: before any RX or saved value a switch is unknown, never the table's ON. */
    @Test fun coldStartSwitchesWithoutRxAreUnknownAndNumbersKeepTheirStartingPosition() = runBlocking {
        val scope = TestScope(); val repository = AirPodsRepository(scope, AirPodsClock { 100 })
        try {
            val g = ready(repository, FakeTransport())
            val s = repository.state.value
            for (spec in AirPodsProperties.controls.filter { it.encoding == ControlEncoding.BOOLEAN_1_2 } +
                listOf(AirPodsProperties.spec(CommandProperty.HEARING_AID)!!))
                assertNull(spec.catalogId, s.propertyEffective(spec.property))
            assertEquals(EffectiveProperty(PropertyValue.Number(3), EffectiveSource.DEFAULT), s.propertyEffective(CommandProperty.MODE))
            assertEquals(EffectiveProperty(PropertyValue.Number(75), EffectiveSource.DEFAULT), s.propertyEffective(CommandProperty.CHIME_VOLUME))
            assertEquals(EffectiveProperty(PropertyValue.Number(50), EffectiveSource.DEFAULT), s.propertyEffective(CommandProperty.AUTO_ANC_STRENGTH))
            repository.receive(g, AacpEvent.Property(CommandProperty.EAR_DETECTION_CONFIG, PropertyValue.Number(2)))
            assertEquals(EffectiveProperty(PropertyValue.Number(2), EffectiveSource.OBSERVED),
                repository.state.value.propertyEffective(CommandProperty.EAR_DETECTION_CONFIG))
        } finally { repository.close(); scope.cancel() }
    }

    /** FIX-04(c): whatever the store held (even a SENT, or a status this build does not know),
     * a restored choice cannot beat this session's RX, and a saved RX is only a fallback. */
    @Test fun restoredStateNeverBeatsTheSessionsObservation() = runBlocking {
        val scope = TestScope()
        val pair = PairAddress("02:00:00:00:00:2C")
        val stored = java.util.concurrent.ConcurrentHashMap<PairAddress, ByteArray>()
        val persistence = object : PairValuePersistence {
            override fun read(pair: PairAddress) = stored[pair]
            override fun write(pair: PairAddress, bytes: ByteArray): Boolean { stored[pair] = bytes; return true }
            override fun remove(pair: PairAddress): Boolean { stored.remove(pair); return true }
        }
        // Hand-written store format v1: two choices, one with a status from a newer build.
        val raw = java.io.ByteArrayOutputStream().also { out ->
            java.io.DataOutputStream(out).use { d ->
                d.writeInt(1)
                d.writeInt(1); d.writeUTF("AUTOMATIC_CONNECTION_CONFIG"); d.writeByte(0); d.writeInt(2); d.writeLong(5)
                d.writeInt(2)
                d.writeUTF("EAR_DETECTION_CONFIG"); d.writeByte(0); d.writeInt(2); d.writeLong(5); d.writeUTF("SENT")
                d.writeUTF("MODE"); d.writeByte(0); d.writeInt(4); d.writeLong(5); d.writeUTF("SOME_FUTURE_STATUS")
                d.writeBoolean(false)
            }
        }.toByteArray()
        stored[pair] = raw
        val store = AirPodsPairValueStore(persistence)
        val loaded = store.load(pair).getOrThrow()
        assertEquals(DesiredStatus.UNCERTAIN, loaded.choices[CommandProperty.MODE]?.status)
        assertEquals(DesiredStatus.SENT, loaded.choices[CommandProperty.EAR_DETECTION_CONFIG]?.status)
        val repository = AirPodsRepository(scope, AirPodsClock { 100 }, valueStore = store)
        try {
            val g = ready(repository, FakeTransport(), pair)
            val s = repository.state.value
            assertTrue(s.desired.values.none { it.status in setOf(DesiredStatus.QUEUED, DesiredStatus.WRITING, DesiredStatus.SENT) })
            assertNull(s.propertyEffective(CommandProperty.EAR_DETECTION_CONFIG))
            assertEquals(EffectiveSource.SAVED_OBSERVATION, s.propertyEffective(CommandProperty.AUTOMATIC_CONNECTION_CONFIG)?.source)
            assertNull(s.requestedOrObserved(CommandProperty.MODE))
            repository.receive(g, AacpEvent.Property(CommandProperty.EAR_DETECTION_CONFIG, PropertyValue.Number(1)))
            repository.receive(g, AacpEvent.Property(CommandProperty.AUTOMATIC_CONNECTION_CONFIG, PropertyValue.Number(1)))
            repository.receive(g, AacpEvent.Mode(ListeningMode.ANC))
            for ((property, value) in listOf(CommandProperty.EAR_DETECTION_CONFIG to 1,
                CommandProperty.AUTOMATIC_CONNECTION_CONFIG to 1, CommandProperty.MODE to 2))
                assertEquals(property.name, EffectiveProperty(PropertyValue.Number(value), EffectiveSource.OBSERVED),
                    repository.state.value.propertyEffective(property))
        } finally { repository.close(); scope.cancel() }
    }
    @Test fun notConfirmedStatusRoundTripsThroughTheStore() {
        val store = AirPodsPairValueStore(); val pair = PairAddress("02:00:00:00:00:2D")
        val values = AirPodsPairValues(choices = mapOf(CommandProperty.MODE to
            SavedChoice(PropertyValue.Number(2), 7, DesiredStatus.NOT_CONFIRMED)))
        store.save(pair, values).getOrThrow()
        assertEquals(values, store.load(pair).getOrThrow())
        assertEquals(DesiredStatus.NOT_CONFIRMED, values.restoredChoices(4)[CommandProperty.MODE]?.status)
    }

    /** FIX-22(a): 0x2C carries the received enrolled byte and leaves with 0x33, as pinned upstream. */
    @Test fun hearingAidIsWrittenAsAPairWithTheReceivedEnrolledByte() = runBlocking {
        val scope = TestScope(); val repository = AirPodsRepository(scope, AirPodsClock { 100 })
        val socket = FakeTransport()
        fun hex(s: String) = s.split(" ").map { it.toInt(16) }
        try {
            val g = ready(repository, socket)
            // Nothing received: no enrolled byte to carry, so nothing is sent.
            assertNull(repository.state.value.hearingAidEnrolled())
            assertFalse(repository.setHearingAid(true))
            assertFalse(repository.setProperty(CommandProperty.HEARING_AID, HearingAidWire.pack(1, true)))
            assertTrue(repository.state.value.desired.isEmpty())
            socket.deliver(byteArrayOf(4, 0, 4, 0, 9, 0, 0x2c, 1, 2, 0, 0))
            withTimeout(2_000) { repository.state.first { it.hearingAidEnrolled() == 1 } }
            // Readers see the plain switch value; the enrolled byte stays internal.
            assertEquals(EffectiveProperty(PropertyValue.Number(2), EffectiveSource.OBSERVED),
                repository.state.value.propertyEffective(CommandProperty.HEARING_AID))
            assertEquals(PropertyValue.Number(2), repository.state.value.propertyObserved(CommandProperty.HEARING_AID)?.value)
            assertTrue(repository.state.value.canAttemptWrite(CommandProperty.HEARING_AID, PropertyValue.Number(1)))
            // A packed value carrying another enrolled byte is refused, through any path.
            assertFalse(repository.state.value.canAttemptWrite(CommandProperty.HEARING_AID, PropertyValue.Number(HearingAidWire.pack(2, true))))
            assertFalse(repository.setProperty(CommandProperty.HEARING_AID, HearingAidWire.pack(2, true)))
            assertTrue(repository.setHearingAid(true))
            withTimeout(2_000) { repository.state.first { it.pending.isEmpty() } }
            assertEquals(PropertyValue.Number(1), repository.state.value.propertyEffective(CommandProperty.HEARING_AID)?.value)
            // The generic path with a switch's 2 is the same pair, never a lone 0x2C.
            assertTrue(repository.setProperty(CommandProperty.HEARING_AID, 2))
            withTimeout(2_000) { repository.state.first { it.pending.isEmpty() } }
            assertTrue(repository.setProperty(CommandProperty.HPS_GAIN_SWIPE, 1))
            withTimeout(2_000) { repository.state.first { it.pending.isEmpty() } }
            val hearing = synchronized(socket.writes) { socket.writes.filter { it.getOrNull(4) == 9 && it[6] in setOf(0x2c, 0x33, 0x2f) } }
            assertEquals(listOf(
                hex("04 00 04 00 09 00 2c 01 01 00 00"), hex("04 00 04 00 09 00 33 01 00 00 00"),
                hex("04 00 04 00 09 00 2c 01 02 00 00"), hex("04 00 04 00 09 00 33 02 00 00 00"),
                hex("04 00 04 00 09 00 2f 01 00 00 00")), hearing)
            repository.receive(g, AacpEvent.Property(CommandProperty.HEARING_AID, PropertyValue.Number(HearingAidWire.pack(1, false))))
            assertEquals(CommandOutcome.OBSERVED_MATCH, repository.state.value.outcomes[CommandProperty.HEARING_AID])
        } finally { repository.close(); scope.cancel() }
    }
    @Test fun savedEnrolledByteIsUsedWhenThisSessionHasNotReceivedOne() = runBlocking {
        val scope = TestScope(); val store = AirPodsPairValueStore(); val pair = PairAddress("02:00:00:00:00:2E")
        store.save(pair, AirPodsPairValues(observed = mapOf(CommandProperty.HEARING_AID to
            SavedProperty(PropertyValue.Number(HearingAidWire.pack(0, true)), 1)))).getOrThrow()
        val repository = AirPodsRepository(scope, AirPodsClock { 100 }, valueStore = store)
        val socket = FakeTransport()
        try {
            ready(repository, socket, pair)
            assertEquals(0, repository.state.value.hearingAidEnrolled())
            assertEquals(EffectiveProperty(PropertyValue.Number(1), EffectiveSource.SAVED_OBSERVATION),
                repository.state.value.propertyEffective(CommandProperty.HEARING_AID))
            assertTrue(repository.setHearingAid(false))
            withTimeout(2_000) { repository.state.first { it.pending.isEmpty() } }
            assertEquals(1, socket.count(AacpCodec.property(CommandProperty.HEARING_AID, HearingAidWire.pack(0, false))))
        } finally { repository.close(); scope.cancel() }
    }

    /** FIX-23 (O2, RX only): the fact reaches state and events; no MAC or name reaches state,
     * qualification rows, diagnostics or the capture ring, and nothing is transmitted. */
    @Test fun smartRoutingRxIsAFactWithoutPayloadAndWithoutAnyTx() = runBlocking {
        val scope = TestScope()
        val rows = java.util.Collections.synchronizedList(mutableListOf<QualificationEvent>())
        val frames = java.util.Collections.synchronizedList(mutableListOf<AacpRxFrame>())
        val repository = AirPodsRepository(scope, AirPodsClock { 100 }, observer = AirPodsQualificationObserver { rows.add(it) },
            rxDiagnostics = AirPodsRxDiagnostics { _, frame -> frames.add(frame) })
        val socket = FakeTransport(); val capture = dev.lelonio.square.airpods.runtime.AirPodsRawRxBuffer({ 100 })
        try {
            ready(repository, socket)
            dev.lelonio.square.airpods.runtime.AirPodsRxCapture.install(capture)
            val writesBefore = synchronized(socket.writes) { socket.writes.size }
            val events = async(start = CoroutineStart.UNDISPATCHED) {
                repository.events.first { it.value is AacpEvent.AudioMovedToOtherDevice }
            }
            val mac = byteArrayOf(0x5a, 0x6b, 0x7c, 0x0d, 0x1e, 0x2f)
            socket.deliver(byteArrayOf(4, 0, 4, 0, 0x11, 0) + mac + "SetOwnershipToFalse Mac-of-someone".toByteArray())
            assertEquals(AacpEvent.AudioMovedToOtherDevice(0x11), withTimeout(2_000) { events.await() }.value)
            val s = withTimeout(2_000) { repository.state.first { it.audioMovedElsewhere != null } }
            assertEquals(Received(true, 100L, s.generation), s.audioMovedElsewhere)
            val text = s.toString() + rows.joinToString() + frames.joinToString() + capture.export()
            assertFalse(text.contains("someone")); assertFalse(text.contains("Mac-of"))
            assertFalse(text.lowercase().contains("5a6b7c")); assertFalse(text.contains("90, 107, 124"))
            val row = rows.single { it.step == QualificationStep.RX && it.flags == 10 }
            assertNull(row.value); assertNull(row.property); assertTrue(row.values.isEmpty())
            assertTrue(frames.single { it.opcode == 0x11 }.bytes.isEmpty())
            delay(50)
            assertEquals(writesBefore, synchronized(socket.writes) { socket.writes.size })
        } finally {
            dev.lelonio.square.airpods.runtime.AirPodsRxCapture.remove(capture)
            repository.close(); scope.cancel()
        }
    }

    /** FIX-17(c): a backlog of head samples drops the excess and the generation lives on. */
    @Test fun headSampleOverflowDropsSamplesWithoutEndingTheGeneration() = runBlocking {
        val scope = TestScope()
        val heads = AtomicInteger()
        val repository = AirPodsRepository(scope, AirPodsClock { 100 }, observer = AirPodsQualificationObserver {
            if (it.step == QualificationStep.RX && it.phase == QualificationPhase.OBSERVED && it.flags == 8) heads.incrementAndGet()
        })
        val socket = FakeTransport(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val collected = AtomicInteger()
        try {
            ready(repository, socket)
            val g = repository.state.value.generation
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                repository.events.collect { collected.incrementAndGet(); entered.complete(Unit); release.await() }
            }
            val total = AirPodsRepository.EVENT_CAPACITY + 64
            val frame = ByteArray(70).also { byteArrayOf(4,0,4,0,0x17,0,0,0,0x10,0,0x44,0).copyInto(it) }
            socket.deliver(frame); withTimeout(2_000) { entered.await() }
            socket.deliver((1 until total).fold(ByteArray(0)) { all, _ -> all + frame })
            withTimeout(3_000) { while (heads.get() < total) delay(1) }
            assertEquals(AacpStage.SUBSCRIBED, repository.state.value.stage)
            assertEquals(g, repository.state.value.generation)
            release.complete(Unit)
            // The one in hand plus a full buffer reach the collector; the excess was dropped.
            withTimeout(3_000) { while (collected.get() < 1 + AirPodsRepository.EVENT_CAPACITY) delay(1) }
            delay(20)
            assertEquals(1 + AirPodsRepository.EVENT_CAPACITY, collected.get())
            socket.deliver(AacpCodec.mode(ListeningMode.ANC))
            withTimeout(2_000) { repository.state.first { it.mode?.value == ListeningMode.ANC } }
            assertEquals(g, repository.state.value.generation)
            collector.cancelAndJoin()
        } finally { release.complete(Unit); repository.close(); scope.cancel() }
    }
}
