package dev.lelonio.square.playback

import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Prepared only: actual widget acquisition helper and actual service connection gate. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class I06LeasedControllerTest {
    @Test fun retirementIsAwaitedBeforeOneConnectionAndTheCommandKeepsItsLeaseThroughRelease() = runTest {
        val gate = PlaybackConnectionGate()
        val retiring = gate.beginRetirement()!!
        val events = mutableListOf<String>()
        var controller = "dying"
        val request = async {
            i06WithLeasedController(gate, 4_000,
                connect = { events += "connect:$controller"; Futures.immediateFuture(controller) },
                release = { assertTrue(gate.hasClients); events += "release" },
                isCurrent = { true },
            ) { target ->
                assertTrue(gate.hasClients)
                assertNull(gate.beginRetirement())
                events += "play:$target"
            }
        }
        runCurrent()
        assertTrue(events.isEmpty()); assertTrue(gate.hasClients)
        controller = "replacement"
        gate.finish(retiring); request.await()
        assertEquals(listOf("connect:replacement", "play:replacement", "release"), events)
        assertFalse(gate.hasClients)
    }

    @Test fun retirementTimeoutDoesNotBindLaterAndCompletesTheBroadcastOnce() = runTest {
        val gate = PlaybackConnectionGate()
        val retiring = gate.beginRetirement()!!
        var connections = 0
        var finished = 0
        var failures = 0
        val request = launch {
            try {
                i06WithLeasedController(gate, 50,
                    connect = { connections++; Futures.immediateFuture("player") },
                    release = {}, isCurrent = { true }) { fail("expired command executed") }
            } catch (_: Exception) { failures++ }
            finally { finished++ }
        }
        runCurrent(); advanceTimeBy(51); runCurrent(); request.join()
        assertEquals(1, failures); assertEquals(1, finished)
        assertEquals(0, connections); assertFalse(gate.hasClients)
        gate.finish(retiring); runCurrent()
        assertEquals(0, connections)
    }

    @Test fun cancelledBindingIsReleasedEvenBeforeTheFutureProducesAController() = runTest {
        val gate = PlaybackConnectionGate()
        val future = SettableFuture.create<String>()
        var releases = 0
        var commands = 0
        val request = launch {
            try {
                i06WithLeasedController(gate, 50, { future }, { releases++ }, { true }) { commands++ }
            } catch (_: Exception) { }
        }
        runCurrent(); advanceTimeBy(51); runCurrent(); request.join()
        assertTrue(future.isCancelled)
        assertEquals(1, releases); assertEquals(0, commands); assertFalse(gate.hasClients)
    }

    @Test fun accountChangeDuringConnectionCannotReceiveTheCommandAndStillReleases() = runTest {
        val gate = PlaybackConnectionGate()
        val future = SettableFuture.create<String>()
        var current = true
        var releases = 0
        var commands = 0
        val request = launch {
            try {
                i06WithLeasedController(gate, 4_000, { future }, { releases++ }, { current }) { commands++ }
            } catch (_: Exception) { }
        }
        runCurrent(); current = false; future.set("other-account"); request.join()
        assertEquals(0, commands); assertEquals(1, releases); assertFalse(gate.hasClients)
    }

    @Test fun connectionRejectionReleasesFutureAndLeaseWithoutRetry() = runTest {
        val gate = PlaybackConnectionGate()
        var attempts = 0
        var releases = 0
        val request = launch {
            try {
                i06WithLeasedController(gate, 4_000,
                    { attempts++; Futures.immediateFailedFuture<String>(IllegalStateException("rejected")) },
                    { releases++ }, { true }) { fail("rejected connection used") }
            } catch (_: Exception) { }
        }
        request.join()
        assertEquals(1, attempts); assertEquals(1, releases); assertFalse(gate.hasClients)
    }

    @Test fun asynchronousServiceAcknowledgementFinishesBeforeLeaseAndControllerAreReleased() = runTest {
        val gate = PlaybackConnectionGate()
        val acknowledgement = CompletableDeferred<String>()
        var released = false
        val request = async {
            i06WithLeasedController(gate, 4_000, { Futures.immediateFuture("player") },
                { released = true }, { true }) { acknowledgement.await() }
        }
        runCurrent()
        assertTrue(gate.hasClients); assertFalse(released); assertFalse(request.isCompleted)
        assertNull(gate.beginRetirement())
        acknowledgement.complete("applied")
        assertEquals("applied", request.await())
        assertTrue(released); assertFalse(gate.hasClients)
    }
}
