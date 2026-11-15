package dev.lelonio.square.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Runs only an in-memory request/consumer boundary. No engine, service, network or JNI. */
class I11ConfirmedTransferTest {
    @Test fun emittingToAServiceIsNotPositivePlaybackCompletion() = runBlocking {
        withTimeout(2_000) {
            val received = CompletableDeferred<RemoteConnect.TransferRequest.HereConfirmed>()
            val consumer = launch(start = CoroutineStart.UNDISPATCHED) {
                RemoteConnect.requests.collect { if (it is RemoteConnect.TransferRequest.HereConfirmed) received.complete(it) }
            }
            try {
                val result = async { RemoteConnect.requestHere({ true }, 1_000) }
                val request = received.await()
                assertTrue(request.done.isActive)
                assertFalse(result.isCompleted)
                request.done.complete(false) // no remote state / renderer did not start
                assertFalse(result.await())
            } finally { consumer.cancelAndJoin() }
        }
    }

    @Test fun actualConsumerAcquisitionAndCurrentAccountAllowSuccess() = runBlocking {
        withTimeout(2_000) {
            val consumer = launch(start = CoroutineStart.UNDISPATCHED) {
                RemoteConnect.requests.collect { if (it is RemoteConnect.TransferRequest.HereConfirmed) {
                    assertTrue(it.isCurrent())
                    it.done.complete(true) // the service has independently observed real playback
                } }
            }
            try { assertTrue(RemoteConnect.requestHere({ true }, 1_000)) }
            finally { consumer.cancelAndJoin() }
        }
    }

    @Test fun missingConsumerReturnsFalseWithoutTreatingBufferAsAcquisition() = runBlocking {
        assertFalse(RemoteConnect.requestHere({ true }, 20))
    }

    @Test fun deadlineClosesTheReceiptSoAQueuedTransferCannotRunLate() = runBlocking {
        withTimeout(2_000) {
            val received = CompletableDeferred<RemoteConnect.TransferRequest.HereConfirmed>()
            val consumer = launch(start = CoroutineStart.UNDISPATCHED) {
                RemoteConnect.requests.collect { if (it is RemoteConnect.TransferRequest.HereConfirmed) {
                    received.complete(it)
                    awaitCancellation()
                } }
            }
            try {
                val result = async { RemoteConnect.requestHere({ true }, 30) }
                val request = received.await()
                assertFalse(result.await())
                assertFalse(request.done.isActive)
                assertFalse(request.done.await())
                assertFalse(request.done.complete(true))
            } finally { consumer.cancelAndJoin() }
        }
    }

    @Test fun callerCancellationAlsoClosesTheConsumerReceipt() = runBlocking {
        withTimeout(2_000) {
            val received = CompletableDeferred<RemoteConnect.TransferRequest.HereConfirmed>()
            val consumer = launch(start = CoroutineStart.UNDISPATCHED) {
                RemoteConnect.requests.collect { if (it is RemoteConnect.TransferRequest.HereConfirmed) received.complete(it) }
            }
            try {
                val result = async { RemoteConnect.requestHere({ true }, 1_000) }
                val request = received.await()
                result.cancelAndJoin()
                assertFalse(request.done.isActive)
                assertFalse(request.done.await())
                assertFalse(request.done.complete(true))
            } finally { consumer.cancelAndJoin() }
        }
    }

    @Test fun aLatePositiveCompletionCannotAuthorizeAnOldAccount() = runBlocking {
        withTimeout(2_000) {
            var current = true
            val consumer = launch(start = CoroutineStart.UNDISPATCHED) {
                RemoteConnect.requests.collect { if (it is RemoteConnect.TransferRequest.HereConfirmed) {
                    current = false
                    assertFalse(it.isCurrent())
                    it.done.complete(true)
                } }
            }
            try { assertFalse(RemoteConnect.requestHere({ current }, 1_000)) }
            finally { consumer.cancelAndJoin() }
        }
    }
}
