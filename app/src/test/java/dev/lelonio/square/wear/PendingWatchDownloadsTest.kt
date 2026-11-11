package dev.lelonio.square.wear

import dev.pampa.fluidify.wear.protocol.DownloadRequest
import dev.pampa.fluidify.wear.protocol.WearCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PendingWatchDownloadsTest {
    private val a = DownloadRequest(owner = "spotify:playlist:a")
    private val b = DownloadRequest(owner = "spotify:playlist:b")

    /** Fake preferences retain exactly the existing JSON list across helper instances. */
    private class Fixture(private val scope: TestScope) {
        var account: PendingWatchDownloads.Scope? = PendingWatchDownloads.Scope("a", "a-1")
        var online = true
        val disk = mutableMapOf<String, String>()
        var encoded: String?
            get() = account?.namespace?.let { disk[it] }
            set(value) { if (value != null) disk[account!!.namespace] = value }
        var broadcast: suspend (DownloadRequest) -> Boolean = { false }
        var send: suspend (String, DownloadRequest) -> Boolean = { _, _ -> false }
        val attempted = mutableListOf<DownloadRequest>()
        val wire = mutableListOf<DownloadRequest>()

        fun newQueue() = PendingWatchDownloads(
            read = { disk[it] },
            write = { namespace, value -> disk[namespace] = value },
            broadcast = { wire += it; val request = it.copy(accountSession = null, accountNamespace = null, requestId = null); attempted += request; broadcast(request) },
            send = { node, value -> wire += value; val request = value.copy(accountSession = null, accountNamespace = null, requestId = null); attempted += request; send(node, request) },
            capture = { account },
            current = { it == account },
            online = { online && it == account },
            use = { captured, block -> if (captured == account) block() },
            io = StandardTestDispatcher(scope.testScheduler),
        )

        fun stored(): List<DownloadRequest> = encoded?.let {
            WearCodec.json.decodeFromString(ListSerializer(DownloadRequest.serializer()), it).map { value -> value.copy(requestId = null) }
        }.orEmpty()
    }

    @Test fun accountSwitchAndRestartRetainQueuesButNeverRebindAnotherAccountsRequest() = runTest {
        val f = Fixture(this)
        val queue = f.newQueue()
        queue.request(a)
        val savedA = f.encoded
        f.account = PendingWatchDownloads.Scope("b", "b-1")
        queue.accountChanged()
        assertEquals(0, queue.waiting.value)
        queue.request(b)
        f.attempted.clear()
        f.send = { _, _ -> true }
        queue.flush("watch")
        assertEquals(listOf(b), f.attempted)
        assertEquals(savedA, f.disk["a"])
        f.account = PendingWatchDownloads.Scope("a", "a-2")
        val restored = f.newQueue()
        restored.refresh()
        assertEquals(1, restored.waiting.value)
        restored.flush("watch")
        assertEquals("a-2", f.wire.last().accountSession)
        assertEquals("a", f.wire.last().accountNamespace)
        assertTrue(f.stored().isEmpty())
    }

    @Test fun lateTransportSuccessCannotDeleteAnotherAccountsHeadOrContinueOldBatch() = runTest {
        val f = Fixture(this)
        val queue = f.newQueue()
        queue.request(a)
        queue.request(b)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.send = { _, _ -> entered.complete(Unit); release.await(); true }
        f.attempted.clear()
        val flush = launch { queue.flush("watch") }
        entered.await()
        f.account = PendingWatchDownloads.Scope("b", "b-1")
        queue.accountChanged()
        release.complete(Unit)
        flush.join()
        assertEquals(listOf(a), f.attempted)
        assertEquals(0, queue.waiting.value)
        f.account = PendingWatchDownloads.Scope("a", "a-2")
        assertEquals(listOf(a, b), f.stored())
    }

    @Test fun localOnlyAuthorityKeepsTheQueueWithoutStartingTransport() = runTest {
        val f = Fixture(this)
        f.online = false
        val queue = f.newQueue()
        queue.request(a)
        queue.flush("watch")
        assertTrue(f.wire.isEmpty())
        assertEquals(listOf(a), f.stored())
        f.account = null
        queue.accountChanged()
        queue.refresh()
        assertEquals(0, queue.waiting.value)
    }

    @Test fun appendDuringSuspendedFlushSurvivesCompletionAndNewInstance() = runTest {
        val f = Fixture(this)
        val queue = f.newQueue()
        queue.request(a) // Offline broadcast; A is already durable.
        f.attempted.clear()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.send = { node, request ->
            assertEquals("watch", node)
            assertEquals(a, request)
            entered.complete(Unit)
            release.await()
            true
        }
        val flush = launch { queue.flush("watch") }
        entered.await()
        val append = launch { queue.request(b) }
        runCurrent()
        assertEquals(listOf(a, b), f.stored())
        assertEquals(2, queue.waiting.value)
        // B waits for A's send before trying its own (failed) broadcast: FIFO is preserved.
        release.complete(Unit)
        flush.join()
        append.join()
        assertEquals(listOf(a, b), f.attempted)
        assertEquals(listOf(b), f.stored())
        assertEquals(1, queue.waiting.value)
        val restored = f.newQueue()
        restored.refresh()
        assertEquals(1, restored.waiting.value)
        assertEquals(listOf(b), f.stored())
    }

    @Test fun twoFailedAppendsPersistBothWithoutOvertaking() = runTest {
        val f = Fixture(this)
        val queue = f.newQueue()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var first = true
        f.broadcast = {
            if (first) {
                first = false
                entered.complete(Unit)
                release.await()
            }
            false
        }
        val keep = launch { queue.request(a) }
        entered.await()
        val unkeep = a.copy(keep = false)
        val remove = launch { queue.request(unkeep) }
        runCurrent()
        assertEquals(listOf(a, unkeep), f.stored())
        release.complete(Unit)
        keep.join()
        remove.join()
        assertEquals(listOf(a, unkeep), f.stored())
        assertEquals(2, queue.waiting.value)
        assertTrue(f.attempted.all { it == a }) // Failed head blocks its opposite command.
        f.attempted.clear()
        f.send = { _, _ -> true }
        queue.flush("watch")
        assertEquals(listOf(a, unkeep), f.attempted)
        assertTrue(f.stored().isEmpty())
    }

    @Test fun simultaneousFlushesSendEachEntryOnceIncludingIdenticalRequests() = runTest {
        val f = Fixture(this)
        val queue = f.newQueue()
        queue.request(a)
        queue.request(a)
        queue.request(b)
        f.attempted.clear()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var first = true
        f.send = { _, _ ->
            if (first) {
                first = false
                entered.complete(Unit)
                release.await()
            }
            true
        }
        val one = launch { queue.flush("watch") }
        entered.await()
        val two = launch { queue.flush("watch") }
        runCurrent()
        release.complete(Unit)
        one.join()
        two.join()
        assertEquals(listOf(a, a, b), f.attempted)
        assertEquals(0, queue.waiting.value)
        assertTrue(f.stored().isEmpty())
    }

    @Test fun partialFailureCommitsSuccessAndRetainsFailedHeadAndSuffix() = runTest {
        val f = Fixture(this)
        val queue = f.newQueue()
        val unkeep = b.copy(keep = false)
        listOf(a, b, unkeep).forEach { queue.request(it) }
        f.attempted.clear()
        f.send = { _, request -> request == a }
        queue.flush("watch")
        assertEquals(listOf(a, b), f.attempted)
        assertEquals(listOf(b, unkeep), f.stored())
        assertEquals(2, queue.waiting.value)
        f.attempted.clear()
        f.send = { _, _ -> true }
        queue.flush("watch")
        assertEquals(listOf(b, unkeep), f.attempted)
        assertTrue(f.stored().isEmpty())
    }

    @Test fun cancellationDuringSecondSendKeepsOnlyUnconfirmedSuffix() = runTest {
        val f = Fixture(this)
        val queue = f.newQueue()
        queue.request(a)
        queue.request(b)
        val entered = CompletableDeferred<Unit>()
        f.send = { _, request ->
            if (request == b) {
                entered.complete(Unit)
                awaitCancellation()
            }
            true
        }
        val flush = launch { queue.flush("watch") }
        entered.await()
        flush.cancelAndJoin()
        assertEquals(listOf(b), f.stored())
        assertEquals(1, queue.waiting.value)
        val restored = f.newQueue()
        restored.refresh()
        assertEquals(1, restored.waiting.value)
        f.attempted.clear()
        f.send = { _, _ -> true }
        restored.flush("watch")
        assertEquals(listOf(b), f.attempted)
    }

    @Test fun cancellationAfterConfirmedSendStillCommitsRemoval() = runTest {
        val f = Fixture(this)
        val queue = f.newQueue()
        queue.request(a)
        f.send = { _, _ ->
            currentCoroutineContext().cancel()
            true
        }
        val flush = launch { queue.flush("watch") }
        flush.join()
        assertTrue(flush.isCancelled)
        assertTrue(f.stored().isEmpty())
        assertEquals(0, queue.waiting.value)
    }

    @Test fun cancelledBroadcastLeavesRequestDurable() = runTest {
        val f = Fixture(this)
        val queue = f.newQueue()
        val entered = CompletableDeferred<Unit>()
        f.broadcast = { entered.complete(Unit); awaitCancellation() }
        val append = launch { queue.request(a) }
        entered.await()
        append.cancelAndJoin()
        assertEquals(listOf(a), f.stored())
        assertEquals(1, queue.waiting.value)
        f.send = { _, _ -> true }
        queue.flush("watch") // Cancellation also released the transport gate.
        assertTrue(f.stored().isEmpty())
    }

    @Test fun refreshRacingAppendsAndFlushNeverPublishesStaleCount() = runTest {
        val f = Fixture(this)
        val queue = f.newQueue()
        queue.request(a)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.send = { _, _ -> entered.complete(Unit); release.await(); true }
        val flush = launch { queue.flush("watch") }
        entered.await()
        val append = launch { queue.request(b) }
        val refresh = launch { repeat(20) { queue.refresh() } }
        runCurrent()
        assertEquals(2, queue.waiting.value)
        release.complete(Unit)
        flush.join()
        append.join()
        refresh.join()
        assertEquals(f.stored().size, queue.waiting.value)
        assertEquals(listOf(b), f.stored())
    }

    @Test fun linkExceptionRetainsFailedRequestAndReleasesSendGate() = runTest {
        val f = Fixture(this)
        val queue = f.newQueue()
        queue.request(a)
        queue.request(b)
        f.send = { _, request ->
            if (request == b) error("link dropped")
            true
        }
        var failed = false
        try {
            queue.flush("watch")
        } catch (expected: IllegalStateException) {
            assertEquals("link dropped", expected.message)
            failed = true
        }
        assertTrue(failed)
        assertEquals(listOf(b), f.stored())
        assertEquals(1, queue.waiting.value)
        f.send = { _, _ -> true }
        queue.flush("watch")
        assertTrue(f.stored().isEmpty())
    }

    @Test fun cancelledAppendWaitingForSendGateStillSurvivesFlush() = runTest {
        val f = Fixture(this)
        val queue = f.newQueue()
        queue.request(a)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.send = { _, _ -> entered.complete(Unit); release.await(); true }
        val flush = launch { queue.flush("watch") }
        entered.await()
        val append = launch { queue.request(b) }
        runCurrent()
        append.cancelAndJoin()
        assertEquals(listOf(a, b), f.stored())
        release.complete(Unit)
        flush.join()
        assertEquals(listOf(b), f.stored())
        assertEquals(1, queue.waiting.value)
    }

    @Test fun concurrentAppendBurstRetainsEveryRequestExactlyOnce() = runTest {
        val f = Fixture(this)
        val queue = f.newQueue()
        val requests = (1..30).map { DownloadRequest(owner = "spotify:playlist:$it") }
        val jobs = requests.map { request -> launch { queue.request(request) } }
        jobs.forEach { it.join() }
        assertEquals(requests.size, queue.waiting.value)
        assertEquals(requests.toSet(), f.stored().toSet())
        assertEquals(requests.size, f.stored().size)
        val persistedOrder = f.stored()
        f.attempted.clear()
        f.send = { _, _ -> true }
        queue.flush("watch")
        assertEquals(persistedOrder, f.attempted)
        assertEquals(0, queue.waiting.value)
    }
}
