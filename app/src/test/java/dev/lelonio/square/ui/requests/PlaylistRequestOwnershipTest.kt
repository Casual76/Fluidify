package dev.lelonio.square.ui.requests

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Exercises the same ticket guard used by VM progress, metadata, success and error. NOT EXECUTED. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistRequestOwnershipTest {
    @Test fun suspendedAThenOfflineBRejectsEveryOldPublication() = runTest {
        val owner = RequestOwnership()
        var visible = "A initial"
        val oldA = owner.begin()
        val gate = CompletableDeferred<Unit>()
        val request = launch {
            gate.await() // intentionally not cancelled: models a non-cooperative reader
            for (kind in listOf("progress", "success", "error", "metadata")) {
                try { oldA.ensureCurrent(); visible = "A $kind" }
                catch (_: CancellationException) { /* rejected by actual guard */ }
            }
        }
        runCurrent()
        owner.invalidate() // offline open invalidates before immediate publication
        visible = "B offline"
        gate.complete(Unit)
        request.join()
        assertEquals("B offline", visible)
    }

    @Test fun oldAAfterAToBToAIsRejectedEvenWithSameUri() {
        val owner = RequestOwnership()
        val firstA = owner.begin()
        owner.begin() // B
        val latestA = owner.begin()
        assertFalse(firstA.isCurrent())
        assertTrue(latestA.isCurrent())
        try { firstA.ensureCurrent(); fail("old generation accepted") }
        catch (_: CancellationException) { }
        latestA.ensureCurrent()
    }
    @Test fun cancelledJobCannotPublishWithoutAnotherOpen() {
        val owner = RequestOwnership()
        val ticket = owner.begin()
        val job = Job()
        ticket.bind(job)
        job.cancel()
        assertFalse(ticket.isCurrent())
        try { ticket.ensureCurrent(); fail("cancelled job accepted") }
        catch (_: CancellationException) { }
    }

}
