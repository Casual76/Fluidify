package dev.lelonio.square.playback

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaybackConnectionGateTest {
    @Test fun alreadyAcquiringClientPreventsIdleStopAcrossTheFinalFactsRead() = runTest {
        val gate = PlaybackConnectionGate()
        val binding = async { gate.withClient { awaitCancellation() } }
        runCurrent()
        assertTrue(gate.hasClients)
        assertNull(gate.beginRetirement())
        binding.cancel(); runCurrent()
        assertFalse(gate.hasClients)
        assertNotNull(gate.beginRetirement())
    }

    @Test fun watchArrivingAfterStopRequestCannotReadOrPlayDyingCachedPlayer() = runTest {
        val gate = PlaybackConnectionGate()
        val events = mutableListOf<String>()
        var cached = "old"
        val ending = gate.beginRetirement()!!
        val command = async { gate.withClient { events.add("play:$cached"); cached } }
        runCurrent()
        assertFalse(command.isCompleted)
        assertTrue(events.isEmpty())
        events.add("old-service:onDestroy")
        // The gate stays closed throughout asynchronous native shutdown, after onDestroy returns.
        runCurrent(); assertTrue(events.size == 1)
        cached = "replacement"
        events.add("native:closed")
        gate.finish(ending); runCurrent()
        assertEquals("replacement", command.await())
        assertEquals(listOf("old-service:onDestroy", "native:closed", "play:replacement"), events)
        assertFalse(gate.hasClients)
    }

    @Test fun stoppedActivityCancelsItsWaitingConnectionWithoutBindingLater() = runTest {
        val gate = PlaybackConnectionGate()
        val ending = gate.beginRetirement()!!
        var bindings = 0
        val opening = async { gate.withClient { bindings++ } }
        runCurrent()
        opening.cancel(); runCurrent()
        assertFalse(gate.hasClients)
        gate.finish(ending); runCurrent()
        assertEquals(0, bindings)
    }

    @Test fun staleTeardownCompletionCannotAdmitClientsIntoAnotherRetiringService() = runTest {
        val gate = PlaybackConnectionGate()
        val first = gate.beginRetirement()!!
        gate.finish(first)
        val second = gate.beginRetirement()!!
        var admitted = false
        val request = async { gate.withClient { admitted = true } }
        runCurrent()
        gate.finish(first); runCurrent()
        assertFalse(admitted)
        gate.finish(second); request.await()
        assertTrue(admitted)
        assertFalse(gate.hasClients)
    }
}
