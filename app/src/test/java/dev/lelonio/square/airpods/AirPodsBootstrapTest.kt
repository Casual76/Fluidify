// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.*
import org.junit.Test

/** PREPARED, NOT EXECUTED by the writer. Synthetic transport; no firmware claims.
 * Expected bytes are transcribed from pinned LibrePods, independently of AacpCodec. */
class AirPodsBootstrapTest {
    private val expected = listOf(
        hex("00 00 04 00 01 00 02 00 00 00 00 00 00 00 00 00"),
        hex("04 00 04 00 4d 00 d7 00 00 00 00 00 00 00"),
        hex("04 00 04 00 0f 00 ff ff ff ff"),
        hex("04 00 04 00 29 00 00 ff ff ff ff ff ff ff"),
    )
    private fun hex(value: String) = value.split(" ").map { it.toInt(16) }

    private class Transport(val failAt: Int? = null, val blockAt: Int? = null) : AacpTransport {
        private val rx = LinkedBlockingQueue<ByteArray>()
        val writes = java.util.Collections.synchronizedList(mutableListOf<List<Int>>())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = AtomicInteger()
        val closeComplete = CountDownLatch(1)
        private var attempts = 0
        override fun connect() = Unit
        override fun readSdu(): ByteArray? = rx.take().takeIf { it.isNotEmpty() }
        override fun write(packet: ByteArray) {
            val attempt = ++attempts
            if (attempt == blockAt) { entered.countDown(); check(release.await(2, TimeUnit.SECONDS)) }
            if (attempt == failAt) throw IOException("synthetic write failure")
            writes.add(packet.map { it.toInt() and 255 })
        }
        override fun close() { closed.incrementAndGet(); release.countDown(); rx.offer(byteArrayOf()); closeComplete.countDown() }
    }
    private suspend fun AirPodsRepository.await(stage: AacpStage) =
        withTimeout(2_000) { state.first { it.stage == stage } }
    private suspend fun connected(repository: AirPodsRepository, transport: Transport) {
        repository.construct { transport }; repository.await(AacpStage.CONSTRUCTED)
        repository.connect(); repository.await(AacpStage.CONNECTED)
    }
    private fun assertNoReceivedCapabilities(repository: AirPodsRepository) {
        val state = repository.state.value
        assertNull(state.identityReceived); assertNull(state.deviceAnnounced)
        assertNull(state.mode); assertNull(state.conversation); assertNull(state.ears)
        assertNull(state.activePacketFormat)
        assertTrue(state.properties.isEmpty()); assertTrue(state.batteries.isEmpty())
        assertTrue(state.pending.isEmpty()); assertTrue(state.outcomes.isEmpty())
    }

    @Test fun connectedGenerationSendsPinnedSequenceOnceWithoutInventingRx() = runBlocking<Unit> {
        val scope = TestScope(); val repository = AirPodsRepository(scope, AirPodsClock { 0L })
        val transport = Transport()
        try {
            connected(repository, transport)
            repeat(20) { repository.bootstrap() }
            repository.await(AacpStage.SUBSCRIBED)
            repeat(20) { repository.bootstrap() }
            assertEquals(expected, transport.writes.toList())
            assertNoReceivedCapabilities(repository)
        } finally { repository.close(); scope.cancel() }
    }

    @Test fun subscribedIsPublishedOnlyAfterTheOpaqueFinalWriteCompletes() = runBlocking<Unit> {
        val scope = TestScope(); val repository = AirPodsRepository(scope, AirPodsClock { 0L })
        val transport = Transport(blockAt = 4)
        try {
            connected(repository, transport); repository.bootstrap()
            assertTrue(transport.entered.await(2, TimeUnit.SECONDS))
            assertEquals(expected.take(3), transport.writes.toList())
            assertEquals(AacpStage.HANDSHAKE_SENT, repository.state.value.stage)
            assertNoReceivedCapabilities(repository)
            transport.release.countDown(); repository.await(AacpStage.SUBSCRIBED)
            assertEquals(expected, transport.writes.toList())
        } finally { repository.close(); scope.cancel() }
    }

    @Test fun anyInitializationWriteFailureClosesWithoutSendingTheSuffix() = runBlocking<Unit> {
        for (failAt in 1..4) {
            val scope = TestScope(); val repository = AirPodsRepository(scope, AirPodsClock { 0L })
            val transport = Transport(failAt = failAt)
            try {
                connected(repository, transport); repository.bootstrap(); repository.await(AacpStage.ERROR)
                assertTrue(transport.closeComplete.await(2, TimeUnit.SECONDS))
                assertEquals("failure at $failAt", expected.take(failAt - 1), transport.writes.toList())
                assertEquals(1, transport.closed.get())
                repository.bootstrap()
                assertEquals(expected.take(failAt - 1), transport.writes.toList())
                assertNoReceivedCapabilities(repository)
            } finally { repository.close(); scope.cancel() }
        }
    }

    @Test fun closedBootstrapCannotSendItsSuffixOrMarkTheNextGenerationSubscribed() = runBlocking<Unit> {
        val scope = TestScope(); val repository = AirPodsRepository(scope, AirPodsClock { 0L })
        val old = Transport(blockAt = 1); val next = Transport()
        try {
            connected(repository, old); val retired = repository.state.value.generation
            repository.bootstrap(); assertTrue(old.entered.await(2, TimeUnit.SECONDS))
            repository.close() // interrupts the old write without taking writer's mutex
            connected(repository, next)
            assertTrue(repository.state.value.generation > retired)
            repository.bootstrap(); repository.await(AacpStage.SUBSCRIBED)
            assertEquals(expected.take(1), old.writes.toList())
            assertEquals(expected, next.writes.toList())
            assertNoReceivedCapabilities(repository)
        } finally { repository.close(); scope.cancel() }
    }
}
