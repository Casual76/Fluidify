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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

/** Synthetic transport; no firmware claims.
 * Expected bytes are transcribed from pinned LibrePods, independently of AacpCodec. */
@OptIn(ExperimentalCoroutinesApi::class)
class AirPodsBootstrapTest {
    /** Pinned AirPodsService.kt:2738-2741: handshake, flags and subscribe at once. 0x29 is not
     * part of this burst; it comes in the 200ms-step refresh that follows (:2743-2751). */
    private val expected = listOf(
        hex("00 00 04 00 01 00 02 00 00 00 00 00 00 00 00 00"),
        hex("04 00 04 00 4d 00 d7 00 00 00 00 00 00 00"),
        hex("04 00 04 00 0f 00 ff ff ff ff"),
    )
    private val extension = hex("04 00 04 00 29 00 00 ff ff ff ff ff ff ff")
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
            assertTrue("0x29 never in the first burst", transport.writes.none { it.getOrNull(4) == 0x29 })
            assertNoReceivedCapabilities(repository)
        } finally { repository.close(); scope.cancel() }
    }

    @Test fun subscribedIsPublishedOnlyAfterTheSubscribeWriteCompletes() = runBlocking<Unit> {
        val scope = TestScope(); val repository = AirPodsRepository(scope, AirPodsClock { 0L })
        val transport = Transport(blockAt = 3)
        try {
            connected(repository, transport); repository.bootstrap()
            assertTrue(transport.entered.await(2, TimeUnit.SECONDS))
            assertEquals(expected.take(2), transport.writes.toList())
            assertEquals(AacpStage.HANDSHAKE_SENT, repository.state.value.stage)
            assertNoReceivedCapabilities(repository)
            transport.release.countDown(); repository.await(AacpStage.SUBSCRIBED)
            assertEquals(expected, transport.writes.toList())
        } finally { repository.close(); scope.cancel() }
    }

    /** FIX-17: like pinned AACPManager.sendPacket (logs and returns false), a failed handshake or
     * flags write is recorded and the sequence continues; the generation stays alive and writable. */
    @Test fun aFailedHandshakeOrFlagsWriteKeepsTheSessionAlive() = runBlocking<Unit> {
        for (failAt in 1..2) {
            val scope = TestScope()
            val rows = java.util.Collections.synchronizedList(mutableListOf<QualificationEvent>())
            val repository = AirPodsRepository(scope, AirPodsClock { 0L },
                observer = AirPodsQualificationObserver { rows.add(it) })
            val transport = Transport(failAt = failAt)
            try {
                connected(repository, transport); val generation = repository.state.value.generation
                repository.bootstrap(); repository.await(AacpStage.SUBSCRIBED)
                assertEquals("failure at $failAt", expected.filterIndexed { i, _ -> i != failAt - 1 }, transport.writes.toList())
                assertEquals(generation, repository.state.value.generation)
                assertEquals(0, transport.closed.get())
                assertTrue(rows.any { it.phase == QualificationPhase.FAILED && it.step == QualificationStep.BOOTSTRAP })
                assertFalse(rows.any { it.step == QualificationStep.OWNER && it.phase == QualificationPhase.FAILED })
                // Still a live control channel.
                assertTrue(repository.setProperty(CommandProperty.CHIME_VOLUME, 40))
                withTimeout(2_000) { while (transport.writes.none { it.getOrNull(6) == 0x1f }) delay(1) }
                assertEquals(AacpStage.SUBSCRIBED, repository.state.value.stage)
            } finally { repository.close(); scope.cancel() }
        }
    }

    /** Only the subscribe write is required: if it fails the generation ends explicitly. */
    @Test fun aFailedSubscribeClosesExplicitlyWithoutTheSuffix() = runBlocking<Unit> {
        val scope = TestScope(); val repository = AirPodsRepository(scope, AirPodsClock { 0L })
        val transport = Transport(failAt = 3)
        try {
            connected(repository, transport); repository.bootstrap(); repository.await(AacpStage.ERROR)
            assertTrue(transport.closeComplete.await(2, TimeUnit.SECONDS))
            assertEquals(expected.take(2), transport.writes.toList())
            assertEquals(1, transport.closed.get())
            assertTrue(repository.state.value.diagnostic.startsWith("bootstrap subscribe"))
            repository.bootstrap()
            assertEquals(expected.take(2), transport.writes.toList())
            assertNoReceivedCapabilities(repository)
        } finally { repository.close(); scope.cancel() }
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

    @Test fun notificationRefreshUsesPinnedPacketsAndRetiredGenerationCannotContinueIt() = runBlocking<Unit> {
        val scope = TestScope(); val repository = AirPodsRepository(scope, AirPodsClock { scope.testScheduler.currentTime })
        val old = Transport(blockAt = 4)
        try {
            connected(repository, old); repository.bootstrap(); repository.await(AacpStage.SUBSCRIBED)
            // Drive owner delays while allowing each physical IO continuation to drain.
            withTimeout(2_000) {
                while (old.entered.count != 0L) { scope.runCurrent(); scope.advanceTimeBy(200); delay(1) }
            }
            assertEquals(expected, old.writes.toList())
            val retired = repository.state.value.generation
            repository.close()
            scope.advanceTimeBy(10_000); scope.runCurrent()
            val next = Transport()
            connected(repository, next)
            assertTrue(repository.state.value.generation > retired)
            repository.bootstrap(); repository.await(AacpStage.SUBSCRIBED)
            // Only the already-entered retry may complete after close, never flags/subscribe.
            withTimeout(2_000) { while (old.writes.size < 4) delay(1) }
            assertEquals(expected + listOf(expected[0]), old.writes.toList())
            assertEquals(expected, next.writes.toList())
        } finally { repository.close(); scope.cancel() }
    }

    @Test fun startupRefreshSendsTheExtensionThenRepeatsSubscriptionWithoutWritingSettings() = runBlocking<Unit> {
        val scope = TestScope(); val repository = AirPodsRepository(scope, AirPodsClock { scope.testScheduler.currentTime })
        val socket = Transport()
        try {
            connected(repository, socket); repository.bootstrap(); repository.await(AacpStage.SUBSCRIBED)
            withTimeout(3_000) {
                while (socket.writes.size < 10) { scope.runCurrent(); scope.advanceTimeBy(200); delay(1) }
            }
            assertEquals(expected + expected + listOf(extension) + expected, socket.writes.toList())
            assertEquals(3, socket.writes.count { it == expected[2] })
            assertEquals(1, socket.writes.count { it == extension })
            assertNoReceivedCapabilities(repository)
        } finally { repository.close(); scope.cancel() }
    }

    /** A refresh write that throws is recorded and the refresh goes on, like upstream. */
    @Test fun aFailedRefreshWriteDoesNotEndTheGeneration() = runBlocking<Unit> {
        val scope = TestScope(); val repository = AirPodsRepository(scope, AirPodsClock { scope.testScheduler.currentTime })
        val socket = Transport(failAt = 5) // the refresh's flags write
        try {
            connected(repository, socket); val generation = repository.state.value.generation
            repository.bootstrap(); repository.await(AacpStage.SUBSCRIBED)
            withTimeout(3_000) {
                while (socket.writes.size < 9) { scope.runCurrent(); scope.advanceTimeBy(200); delay(1) }
            }
            assertEquals(expected + listOf(expected[0], expected[2], extension) + expected, socket.writes.toList())
            assertEquals(generation, repository.state.value.generation)
            assertEquals(AacpStage.SUBSCRIBED, repository.state.value.stage)
            assertEquals(0, socket.closed.get())
        } finally { repository.close(); scope.cancel() }
    }
}
