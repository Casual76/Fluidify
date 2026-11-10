// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.android

import dev.lelonio.square.airpods.*
import dev.lelonio.square.airpods.runtime.nextTileListeningMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue

/** PREPARED / NOT EXECUTED. Exercises the production observer with the real repository and
 * selector. Only admission and runtime invalidation are injected; no Android/controller or
 * hardware authorization is claimed by this fixture. Transport carries no physical traffic. */
@OptIn(ExperimentalCoroutinesApi::class)
class AirPodsTileObservationTest {
    private class Transport : AacpTransport {
        private val reads = LinkedBlockingQueue<ByteArray>()
        override fun connect() {}
        override fun readSdu(): ByteArray? = reads.take().takeIf { it.isNotEmpty() }
        override fun write(packet: ByteArray) {}
        override fun close() { reads.offer(byteArrayOf()) }
    }

    private class Fixture(val timers: TestScope) {
        val mainThread = Thread.currentThread()
        val repository = AirPodsRepository(timers, AirPodsClock { timers.testScheduler.currentTime })
        val runtime = MutableStateFlow(AndroidRuntimeState(active = true))
        var admitted = true
        var resolutions = 0
        var subscriptions = 0
        var activeSubscriptions = 0
        val updates = mutableListOf<Boolean>()
        val source = TileAvailabilitySource(
            flow {
                subscriptions++; activeSubscriptions++
                try { emitAll(combine(repository.state, runtime) { _, _ -> Unit }) }
                finally { activeSubscriptions-- }
            },
            // Actual shared selector, not a reimplementation of mode/pending policy.
            { admitted && repository.state.value.nextTileListeningMode() != null },
        )
        val observer = TileAvailabilityObservation(
            { resolutions++; source },
            { available ->
                assertSame(mainThread, Thread.currentThread())
                updates.add(available); true
            },
        )
        suspend fun ready() {
            suspend fun await(stage: AacpStage) = withTimeout(2_000) { repository.state.first { it.stage == stage } }
            repository.construct { Transport() }; await(AacpStage.CONSTRUCTED)
            repository.connect(); await(AacpStage.CONNECTED)
            repository.handshake(); await(AacpStage.HANDSHAKE_SENT)
            repository.subscribe(); await(AacpStage.SUBSCRIBED)
            rx(AacpEvent.Information(AirPodsIdentity("A3063", "Apple Inc.", "fixture")))
            rx(AacpEvent.Mode(ListeningMode.ANC))
        }
        fun rx(event: AacpEvent) = repository.receive(repository.state.value.generation, event)
        fun drain() = timers.runCurrent()
        fun start() { observer.start(); drain() }
        fun click() {
            val next = requireNotNull(repository.state.value.nextTileListeningMode())
            assertTrue(repository.setMode(next))
            observer.refresh() // the same immediate read used by Tile.onClick, no start()
            drain()
        }
        fun close() { observer.destroy(); repository.close(); timers.cancel(); drain() }
    }

    private fun scenario(block: suspend Fixture.() -> Unit) = runBlocking {
        val timers = TestScope()
        Dispatchers.setMain(StandardTestDispatcher(timers.testScheduler))
        val f = Fixture(timers)
        try { f.ready(); f.block() }
        finally { f.close(); Dispatchers.resetMain() }
    }

    @Test fun listeningSurvivesPendingAndMatchingRxWithoutReopen() = scenario {
        start(); click()
        val requested = repository.state.value.pending.getValue(CommandProperty.MODE).value
        rx(AacpEvent.Mode(ListeningMode.entries.single { it.wire == requested })); drain()
        assertEquals(CommandOutcome.OBSERVED_MATCH, repository.state.value.outcomes[CommandProperty.MODE])
        assertEquals(listOf(true, false, true), updates)
        assertEquals(1, subscriptions)
    }

    @Test fun listeningSurvivesRealPendingTimeoutWithoutReopen() = scenario {
        start(); click()
        timers.advanceTimeBy(5_000); drain()
        assertEquals(CommandOutcome.NOT_CONFIRMED, repository.state.value.outcomes[CommandProperty.MODE])
        assertEquals(listOf(true, false, true), updates)
        assertEquals(ListeningMode.ANC, repository.state.value.mode?.value)
    }

    @Test fun runtimeRevocationInvalidatesAvailabilityWithoutProtocolRx() = scenario {
        start()
        withContext(Dispatchers.Default) {
            admitted = false
            runtime.value = AndroidRuntimeState(failure = "REVOKED")
        }
        drain()
        assertEquals(listOf(true, false), updates)
    }

    @Test fun repositoryDisconnectInvalidatesAvailability() = scenario {
        start(); repository.close(); drain()
        assertEquals(listOf(true, false), updates)
    }

    @Test fun repeatedStartsClicksAndUnrelatedRxKeepOneCollectorAndDistinctPresentation() = scenario {
        start(); observer.start(); observer.start(); drain()
        rx(AacpEvent.Mode(ListeningMode.ANC)); drain()
        rx(AacpEvent.ConversationActivity(1)); drain()
        runtime.value = runtime.value.copy(failure = "UNRELATED"); drain()
        assertEquals(listOf(true), updates)
        click(); observer.refresh(); drain()
        assertEquals(listOf(true, false), updates)
        assertEquals(1, resolutions)
        assertEquals(1, subscriptions)
        assertEquals(1, activeSubscriptions)
    }

    @Test fun stopAndDestroyCancelQueuedCallbacksAndAllowOnlyFreshListening() = scenario {
        start()
        click()
        val requested = repository.state.value.pending.getValue(CommandProperty.MODE).value
        rx(AacpEvent.Mode(ListeningMode.entries.single { it.wire == requested }))
        observer.stop() // RX is queued on main, then listening ends before it can render.
        drain()
        assertEquals(listOf(true, false), updates)
        assertEquals(0, activeSubscriptions)
        start()
        assertEquals(listOf(true, false, true), updates)
        repository.close() // queue another change, destroy before delivery
        observer.destroy(); drain()
        observer.start(); observer.refresh(); drain()
        assertEquals(listOf(true, false, true), updates)
        assertEquals(0, activeSubscriptions)
        assertEquals(2, subscriptions)
    }

    @Test fun absentOwnerNeverSubscribesOrAcquiresAndCannotRestartAfterDestroy() {
        val updates = mutableListOf<Boolean>()
        var lookups = 0
        val observer = TileAvailabilityObservation({ lookups++; null }, { updates.add(it); true },
            StandardTestDispatcher())
        observer.start(); observer.start(); observer.refresh()
        observer.stop(); observer.refresh()
        observer.destroy(); observer.start()
        assertEquals(1, lookups)
        assertEquals(listOf(false), updates)
    }
}
