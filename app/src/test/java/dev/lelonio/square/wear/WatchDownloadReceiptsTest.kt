package dev.lelonio.square.wear

import dev.pampa.fluidify.wear.protocol.DownloadPlanAck
import dev.pampa.fluidify.wear.protocol.DownloadRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class WatchDownloadReceiptsTest {
    private val request = DownloadRequest("spotify:playlist:p", accountSession = "a-1", accountNamespace = "a", requestId = "plan")
    @Test fun transportSuccessWithoutApplicationReceiptIsNotDelivery() = runTest {
        val receipts = WatchDownloadReceipts { true }
        var sends = 0
        assertFalse(receipts.deliver(request) { sends++; true })
        assertEquals(2, sends)
    }
    @Test fun aLateReceiptCannotConfirmARequestUnderAnotherSession() = runTest {
        var current = true
        val receipts = WatchDownloadReceipts { current }
        val sent = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var delivered = true
        val job = launch { delivered = receipts.deliver(request) { sent.complete(Unit); release.await(); true } }
        sent.await()
        current = false
        receipts.onAck(DownloadPlanAck("plan", "a-1", "a", true))
        release.complete(Unit)
        job.join()
        assertFalse(delivered)
    }
    @Test fun receiptMustMatchIdNamespaceAndSessionAndCanArriveBeforeTransportReturns() = runTest {
        val receipts = WatchDownloadReceipts { true }
        val result = receipts.deliver(request) {
            receipts.onAck(DownloadPlanAck("other", "a-1", "a", true))
            receipts.onAck(DownloadPlanAck("plan", "b-1", "b", true))
            receipts.onAck(DownloadPlanAck("plan", "a-1", "a", true))
            true
        }
        assertTrue(result)
    }
}
