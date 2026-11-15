package dev.lelonio.square.data

import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class RootConnectLeaseTest {
    @After fun reset() { SpotifyNetworkPolicy.setManual(false) }
    @Test fun accountAndReceiptRevocationFenceAQueuedConsumer() {
        SpotifyNetworkPolicy.setManual(false)
        var account = "a"
        var active = true
        var writes = 0
        val gate = Any()
        val lease = ConnectCommandLease(SpotifyNetworkPolicy.capture(), { account == "a" },
            { enter -> synchronized(gate) { enter() } }, requireNativeScope = false)
        lease.dispatch({ active }) { assertFalse(Thread.holdsLock(gate)); writes++ }
        active = false
        assertThrows(CancellationException::class.java) { lease.dispatch({ active }) { writes++ } }
        active = true; account = "b"
        assertThrows(CancellationException::class.java) { lease.dispatch({ active }) { writes++ } }
        assertEquals(1, writes)
    }
    @Test fun manualOnOffDoesNotRenewAnOldGestureButAFreshGestureWorks() {
        SpotifyNetworkPolicy.setManual(false)
        val old = ConnectCommandLease(SpotifyNetworkPolicy.capture(), { true }, { it() }, false)
        SpotifyNetworkPolicy.setManual(true); SpotifyNetworkPolicy.setManual(false)
        assertThrows(SpotifyNetworkPolicy.Offline::class.java) { old.dispatch { fail("old gesture") } }
        val fresh = ConnectCommandLease(SpotifyNetworkPolicy.capture(), { true }, { it() }, false)
        assertEquals("fresh", fresh.dispatch { "fresh" })
    }
}
