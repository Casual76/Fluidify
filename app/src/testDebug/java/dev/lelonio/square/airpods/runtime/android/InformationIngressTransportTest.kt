// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.android

import android.app.Application
import dev.lelonio.square.airpods.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class InformationIngressTransportTest {
    private open class Transport(private val packet: ByteArray) : AacpTransport {
        override fun connect() {}
        override fun readSdu(): ByteArray? = packet
        override fun write(packet: ByteArray) { error("No TX in diagnostic tests") }
        override fun close() {}
    }

    @Test fun failingHookAndFailureRecorderCannotReplaceTheReceivedPacket() {
        val packet = AacpCodec.mode(ListeningMode.ANC)
        var failureMarker = false
        val transport = GuardedTransport(Transport(packet), AirPodsQualificationObserver {
            if (it.flags == 34) {
                failureMarker = true
                error("failure recorder unavailable")
            }
        }, onSdu = { error("diagnostic unavailable") })
        val received = transport.readSdu()
        assertSame(packet, received)
        assertTrue(failureMarker)
        assertEquals(listOf(AacpEvent.Mode(ListeningMode.ANC)), AacpCodec.decodeSdu(received!!))
    }

    @Test fun closeDuringBlockedReadExcludesTheLateDiagnosticCallback() {
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val packet = AacpCodec.mode(ListeningMode.ANC)
        var callbacks = 0
        val raw = object : Transport(packet) {
            override fun readSdu(): ByteArray {
                entered.countDown()
                check(closed.await(3, TimeUnit.SECONDS))
                return packet
            }
            override fun close() { closed.countDown() }
        }
        val transport = GuardedTransport(raw, onSdu = { callbacks++ })
        val outcome = AtomicReference<Result<ByteArray?>>()
        val reader = Thread { outcome.set(runCatching { transport.readSdu() }) }
        reader.start()
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            transport.close()
            reader.join(3_000)
            assertFalse("Read must terminate after transport close", reader.isAlive)
            assertSame(packet, outcome.get().getOrThrow())
            assertEquals(0, callbacks)
        } finally {
            closed.countDown()
            reader.join(3_000)
        }
    }

    @Test fun revokedAuthoritySkipsObservationWithoutChangingReadSemantics() {
        val packet = AacpCodec.mode(ListeningMode.TRANSPARENCY)
        var callbacks = 0
        val transport = GuardedTransport(Transport(packet), onSdu = { callbacks++ }, authorized = { false })
        assertSame(packet, transport.readSdu())
        assertEquals(0, callbacks)
        assertTrue(runCatching { transport.write(byteArrayOf(1)) }.isFailure)
    }

    @Test fun malformedInformationHasSymbolicReasonAndNoPrivateJournalBytes() {
        val evidence = DebugQualificationEvidence()
        val capture = DebugInformationIngress(evidence, { 100L })
        val raw = byteArrayOf(4, 0, 4, 0, 0x1d, 0, 1, 0) +
            "ignored\u0000PRIVATE_NAME_AND_SERIAL\u0000".toByteArray()
        capture.observe(7, raw)
        val exported = evidence.export()
        assertTrue(exported.contains("ingressOpcode=29"))
        assertTrue(exported.contains("ingressBytes=${raw.size}"))
        assertTrue(exported.contains("ingressReason=INCOMPLETE_OR_UNKNOWN_INFORMATION_LAYOUT"))
        assertTrue(exported.contains("inFlight=0"))
        assertFalse(exported.contains("PRIVATE_NAME_AND_SERIAL"))
        assertFalse(exported.contains("ignored"))
    }

    @Test fun failedPrivatePersistenceDoesNotKeepSensitiveCandidateInMemory() {
        val parent = kotlin.io.path.createTempDirectory("airpods-information-test").toFile()
        try {
            val blocker = File(parent, "parent-is-a-file").apply { writeText("blocked") }
            val capture = DebugInformationIngress(AirPodsQualificationObserver.NONE, { 100L })
            capture.observe(1, byteArrayOf(4, 0, 4, 0, 0x1d, 0) + "PRIVATE_MARKER".toByteArray())
            assertTrue(runCatching { capture.persistTo(File(blocker, "capture.bin")) }.isFailure)
            assertFalse(capture.drainPrivateRecord().toString(Charsets.ISO_8859_1).contains("PRIVATE_MARKER"))
            assertFalse(File(blocker, "capture.bin").exists())
        } finally {
            File(parent, "parent-is-a-file").delete()
            parent.delete()
        }
    }
}
