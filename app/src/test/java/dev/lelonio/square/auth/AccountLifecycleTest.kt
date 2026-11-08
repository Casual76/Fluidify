package dev.lelonio.square.auth

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AccountLifecycleTest {
    @Test fun logoutWaitsForNativeStartAndDownloadReturnBeforeShutdownAndNextAccount() = runBlocking {
        val lifecycle = AccountLifecycle()
        val events = mutableListOf<String>()
        val nativeEntered = CompletableDeferred<Unit>()
        val nativeReturn = CompletableDeferred<Unit>()
        val downloadEntered = CompletableDeferred<Unit>()
        val downloadReturn = CompletableDeferred<Unit>()
        val ticketA = lifecycle.generation
        val connectA = launch { lifecycle.runCurrent(ticketA) {
            events += "start-A"; nativeEntered.complete(Unit); nativeReturn.await()
            // A late native success is not allowed to restore/autoplay A.
            if (lifecycle.current(ticketA)) events += "restore-A"
        } }
        nativeEntered.await(); lifecycle.invalidate()
        val logout = launch { lifecycle.exclusive {
            events += "join-download"; downloadEntered.complete(Unit); downloadReturn.await()
            events += "shutdown-A"
        } }
        yield(); assertFalse(logout.isCompleted)
        nativeReturn.complete(Unit); downloadEntered.await()
        assertFalse(events.contains("shutdown-A"))
        downloadReturn.complete(Unit); logout.join(); connectA.join()
        lifecycle.activate()
        lifecycle.runCurrent(lifecycle.generation) { events += "start-B" }
        assertEquals(listOf("start-A", "join-download", "shutdown-A", "start-B"), events)
    }

    @Test fun queuedOldStartAndCallbackStayInvalidAfterNewLogin() = runBlocking {
        val lifecycle = AccountLifecycle(); val old = lifecycle.generation
        lifecycle.invalidate(); lifecycle.invalidate(); lifecycle.activate()
        var called = false
        lifecycle.runCurrent(old) { called = true }
        assertFalse(called)
        lifecycle.runCurrent(lifecycle.generation) { called = true }
        assertTrue(called)
    }

    @Test fun cancellationIsPropagatedAndReleasesOwnership() = runBlocking {
        val lifecycle = AccountLifecycle(); val entered = CompletableDeferred<Unit>()
        val operation = launch { lifecycle.runCurrent(lifecycle.generation) {
            entered.complete(Unit); awaitCancellation()
        } }
        entered.await(); operation.cancelAndJoin()
        assertTrue(operation.isCancelled)
        var acquired = false
        lifecycle.exclusive { acquired = true }
        assertTrue(acquired)
    }
}
