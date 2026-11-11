// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods

import dev.lelonio.square.airpods.protocol.PropertyValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test

/** Prepared host tests. Invented packets, no Bluetooth or physical interpretation of raw values. */
class AirPodsUnknownEarEvidenceTest {
    private class InertTransport : AacpTransport {
        override fun connect() = error("No connection belongs to these observer tests")
        override fun readSdu(): ByteArray? = error("No reader belongs to these observer tests")
        override fun write(packet: ByteArray) = error("No TX belongs to these observer tests")
        override fun close() = Unit
    }

    private fun session(block: (AirPodsRepository, Long, MutableList<QualificationEvent>) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val rows = mutableListOf<QualificationEvent>()
        val repository = AirPodsRepository(scope, AirPodsClock { 100L }, Dispatchers.Unconfined,
            AirPodsQualificationObserver { rows.add(it) })
        try {
            repository.construct { InertTransport() }
            assertEquals(AacpStage.CONSTRUCTED, repository.state.value.stage)
            block(repository, repository.state.value.generation, rows)
        } finally { repository.close(); scope.cancel() }
    }

    private fun packet(first: Int, second: Int = 0, third: Int = 0, fourth: Int = 0) =
        byteArrayOf(4, 0, 4, 0, 9, 0, 0x0a, first.toByte(), second.toByte(), third.toByte(), fourth.toByte())

    private fun rx(rows: List<QualificationEvent>) = rows.filter { it.step == QualificationStep.RX }

    private fun unknown(row: QualificationEvent, expected: List<Int>) {
        assertEquals(QualificationPhase.OBSERVED, row.phase)
        assertEquals(0, row.flags)
        assertNull(row.property)
        assertNull(row.value)
        assertEquals(expected, row.values)
    }

    @Test fun rejectedEarRangeRetainsOnlyVersionIdAndFourBytesWithoutObservingConfiguration() = session { repository, generation, rows ->
        val event = AacpCodec.decodeSdu(packet(3)).single()
        assertTrue(event is AacpEvent.Unknown)
        repository.receive(generation, event)
        unknown(rx(rows).single(), listOf(1, 0x0a, 3, 0, 0, 0))
        assertFalse(CommandProperty.EAR_DETECTION_CONFIG in repository.state.value.properties)
        assertTrue(repository.state.value.pending.isEmpty())
        assertTrue(repository.state.value.outcomes.isEmpty())
        assertNull(repository.state.value.ears)
    }

    @Test fun rejectedPaddingCannotBePromotedFromItsFirstByteToOn() = session { repository, generation, rows ->
        val event = AacpCodec.decodeSdu(packet(1, third = 255)).single()
        assertTrue(event is AacpEvent.Unknown)
        repository.receive(generation, event)
        unknown(rx(rows).single(), listOf(1, 0x0a, 1, 0, 255, 0))
        assertNull(repository.state.value.properties[CommandProperty.EAR_DETECTION_CONFIG])
    }

    @Test fun otherControlIdentifiersNeverExportTheirPayload() = session { repository, generation, rows ->
        listOf(null, 0x01, 0x09, 0x1d, 0xfe).forEach { identifier ->
            repository.receive(generation, AacpEvent.Unknown(9, 11, "not an exported field", identifier, listOf(65, 66, 67, 68)))
        }
        assertEquals(5, rx(rows).size)
        rx(rows).forEach { unknown(it, emptyList()) }
        assertTrue(repository.state.value.properties.isEmpty())
    }

    @Test fun wrongOpcodeLengthOrRangeNeverExportPayload() = session { repository, generation, rows ->
        val events = listOf(
            AacpEvent.Unknown(0x1d, 11, "synthetic", 0x0a, listOf(1, 0, 0, 0)),
            AacpEvent.Unknown(null, 11, "synthetic", 0x0a, listOf(1, 0, 0, 0)),
            AacpEvent.Unknown(9, 11, "synthetic", 0x0a, null),
            AacpEvent.Unknown(9, 11, "synthetic", 0x0a, listOf(1, 0, 0)),
            AacpEvent.Unknown(9, 11, "synthetic", 0x0a, listOf(1, 0, 0, 0, 0)),
            AacpEvent.Unknown(9, 11, "synthetic", 0x0a, listOf(-1, 0, 0, 0)),
            AacpEvent.Unknown(9, 11, "synthetic", 0x0a, listOf(256, 0, 0, 0)),
        )
        events.forEach { repository.receive(generation, it) }
        assertEquals(events.size, rx(rows).size)
        rx(rows).forEach { unknown(it, emptyList()) }
    }

    @Test fun truncatedControlHasNoRetainedPayloadAndNoInventedConfig() = session { repository, generation, rows ->
        val event = AacpCodec.decodeSdu(packet(1).copyOf(9)).single()
        assertTrue(event is AacpEvent.Unknown)
        repository.receive(generation, event)
        unknown(rx(rows).single(), emptyList())
        assertTrue(repository.state.value.properties.isEmpty())
    }

    @Test fun validEarConfigurationStillUsesOnlyTheTypedPath() = session { repository, generation, rows ->
        listOf(1, 2).forEach { value ->
            val event = AacpCodec.decodeSdu(packet(value)).single()
            assertEquals(AacpEvent.Property(CommandProperty.EAR_DETECTION_CONFIG, PropertyValue.Number(value)), event)
            repository.receive(generation, event)
            val row = rx(rows).last()
            assertEquals(9, row.flags)
            assertEquals(CommandProperty.EAR_DETECTION_CONFIG, row.property)
            assertEquals(value, row.value)
            assertTrue(row.values.isEmpty())
            assertEquals(PropertyValue.Number(value), repository.state.value.properties[CommandProperty.EAR_DETECTION_CONFIG]?.value)
        }
    }

    @Test fun retiredGenerationCannotAddRejectedRxToTheNextSession() = session { repository, generation, rows ->
        repository.close()
        repository.construct { InertTransport() }
        val current = repository.state.value
        assertTrue(current.generation > generation)
        repository.receive(generation, AacpCodec.decodeSdu(packet(3)).single())
        assertTrue(rx(rows).isEmpty())
        assertEquals(current, repository.state.value)
    }
}
