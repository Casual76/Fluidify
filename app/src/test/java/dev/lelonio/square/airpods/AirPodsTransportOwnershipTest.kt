// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class AirPodsTransportOwnershipTest {
    private val diagnostic = AirPodsTransportOwnership.Owner.DIAGNOSTIC
    private val production = AirPodsTransportOwnership.Owner.PRODUCTION
    private class Transport : AacpTransport {
        val closes = AtomicInteger()
        var failClose = false
        var connectBody: () -> Unit = {}
        override fun connect() = connectBody()
        override fun readSdu(): ByteArray? = null
        override fun write(packet: ByteArray) = Unit
        override fun close() { closes.incrementAndGet(); check(!failClose) }
    }
    private fun CountDownLatch.arrived() { assertTrue("barrier timeout", await(3, TimeUnit.SECONDS)) }

    @Test fun diagnosticReservationDeniesProductionBeforeConstruction() {
        val owners = AirPodsTransportOwnership()
        val lease = owners.reserve(diagnostic)!!
        assertNull(owners.reserve(production))
        lease.close()
        assertNotNull(owners.reserve(production))
    }

    @Test fun productionReservationDeniesDiagnosticBeforeServiceAttach() {
        val owners = AirPodsTransportOwnership()
        val lease = owners.reserve(production)!!
        assertNull(owners.reserve(diagnostic))
        lease.close()
        assertNotNull(owners.reserve(diagnostic))
    }

    @Test fun cancelledQueuedFactoryCannotOpenAfterNewReservation() {
        val owners = AirPodsTransportOwnership()
        val old = owners.reserve(diagnostic)!!
        old.close()
        val next = owners.reserve(production)!!
        val opens = AtomicInteger()
        assertTrue(runCatching { old.construct { opens.incrementAndGet(); Transport() } }.isFailure)
        old.close()
        assertEquals(0, opens.get())
        assertNull(owners.reserve(diagnostic))
        assertFalse(next.released)
    }

    @Test fun cancelledInflightFactoryRetainsReservationUntilReturnedSocketCloses() {
        val owners = AirPodsTransportOwnership()
        val lease = owners.reserve(diagnostic)!!
        val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        val socket = Transport(); val outcome = AtomicReference<Result<AacpTransport>>()
        val worker = thread { outcome.set(runCatching { lease.construct { entered.countDown(); finish.arrived(); socket } }) }
        try {
            entered.arrived(); lease.close()
            assertFalse(lease.released)
            assertNull(owners.reserve(production))
        } finally { finish.countDown(); worker.join(4000) }
        assertFalse(worker.isAlive)
        assertTrue(outcome.get().isFailure)
        assertEquals(1, socket.closes.get())
        assertTrue(lease.released)
    }

    @Test fun successfulCloseDoesNotReleaseWhilePlatformConnectStillLives() {
        val owners = AirPodsTransportOwnership()
        val lease = owners.reserve(production)!!
        val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        val socket = Transport().apply { connectBody = { entered.countDown(); finish.arrived() } }
        val transport = lease.construct { socket }
        val outcome = AtomicReference<Result<Unit>>()
        val worker = thread { outcome.set(runCatching { transport.connect() }) }
        try {
            entered.arrived(); transport.close()
            assertEquals(1, socket.closes.get())
            assertNull(owners.reserve(diagnostic))
            assertFalse(lease.released)
        } finally { finish.countDown(); worker.join(4000) }
        assertFalse(worker.isAlive)
        assertTrue(outcome.get().isSuccess)
        assertTrue(lease.released)
    }

    @Test fun failedCloseRetainsOwnershipWithoutAutomaticRetry() {
        val owners = AirPodsTransportOwnership()
        val lease = owners.reserve(diagnostic)!!
        val socket = Transport().apply { failClose = true }
        val transport = lease.construct { socket }
        assertTrue(runCatching { transport.close() }.isFailure)
        assertNull(owners.reserve(production))
        assertFalse(lease.released)
        assertEquals(1, socket.closes.get())
        assertTrue(runCatching { transport.connect() }.isFailure)
        assertEquals(1, socket.closes.get())
        socket.failClose = false
        lease.close() // explicit cleanup, no retry loop
        assertTrue(lease.released)
    }

    @Test fun abandonedFactoryCloseFailureRetainsOwnership() {
        val owners = AirPodsTransportOwnership()
        val lease = owners.reserve(diagnostic)!!
        val socket = Transport().apply { failClose = true }
        assertTrue(runCatching { lease.construct { lease.close(); socket } }.isFailure)
        assertNull(owners.reserve(production))
        assertFalse(lease.released)
        socket.failClose = false; lease.close()
        assertTrue(lease.released)
    }

    @Test fun constructorFailureReleasesWithoutOpeningAnotherTransport() {
        val owners = AirPodsTransportOwnership()
        val lease = owners.reserve(diagnostic)!!
        val opens = AtomicInteger()
        assertTrue(runCatching { lease.construct { opens.incrementAndGet(); error("factory") } }.isFailure)
        assertTrue(lease.released)
        assertEquals(1, opens.get())
        assertNotNull(owners.reserve(production))
    }

    @Test fun staleTransportCloseCannotReleaseNextOwner() {
        val owners = AirPodsTransportOwnership()
        val old = owners.reserve(diagnostic)!!
        val socket = Transport()
        val transport = old.construct { socket }
        transport.close()
        val next = owners.reserve(production)!!
        assertTrue(next.generation > old.generation)
        transport.close(); old.close()
        assertEquals(1, socket.closes.get())
        assertFalse(next.released)
        assertNull(owners.reserve(diagnostic))
    }

    @Test fun oneLeaseCannotConstructTwice() {
        val owners = AirPodsTransportOwnership()
        val lease = owners.reserve(diagnostic)!!
        val opens = AtomicInteger()
        lease.construct { opens.incrementAndGet(); Transport() }
        assertTrue(runCatching { lease.construct { opens.incrementAndGet(); Transport() } }.isFailure)
        assertEquals(1, opens.get())
        lease.close()
    }

    @Test fun simultaneousReservationsHaveExactlyOneWinnerAndOneOpen() {
        val owners = AirPodsTransportOwnership()
        val ready = CountDownLatch(2); val go = CountDownLatch(1)
        val leases = java.util.Collections.synchronizedList(mutableListOf<AirPodsTransportOwnership.Lease>())
        val opens = AtomicInteger()
        val workers = listOf(diagnostic, production).map { owner -> thread {
            ready.countDown(); go.arrived()
            owners.reserve(owner)?.let { lease ->
                leases.add(lease)
                lease.construct { opens.incrementAndGet(); Transport() }
            }
        } }
        try {
            ready.arrived(); go.countDown()
            workers.forEach { it.join(4000); assertFalse(it.isAlive) }
            assertEquals(1, leases.size)
            assertEquals(1, opens.get())
        } finally { go.countDown(); workers.forEach { it.join(4000) }; leases.forEach { it.close() } }
    }
}
