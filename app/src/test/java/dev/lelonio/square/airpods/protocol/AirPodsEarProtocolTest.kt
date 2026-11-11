// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.protocol

import dev.lelonio.square.airpods.AacpCodec
import dev.lelonio.square.airpods.AacpEvent
import org.junit.Assert.*
import org.junit.Test

/** PREPARED, NOT EXECUTED. Synthetic bytes from pinned AAP Definitions, not radio captures. */
class AirPodsEarProtocolTest {
    private fun frame(first: Int, second: Int) = byteArrayOf(4, 0, 4, 0, 6, 0, first.toByte(), second.toByte())
    private fun decode(first: Int, second: Int) =
        (AacpCodec.decodeSdu(frame(first, second)).single() as AacpEvent.Ear).sample

    @Test fun everyDocumentedInEarOutOfEarAndInCaseCombinationKeepsRawBytes() {
        // Keys are primary/secondary wire positions, not a proven physical left/right mapping.
        val cases = mapOf(
            (0 to 0) to EarState.BOTH, (0 to 1) to EarState.LEFT, (0 to 2) to EarState.LEFT,
            (1 to 0) to EarState.RIGHT, (2 to 0) to EarState.RIGHT,
            (1 to 1) to EarState.NONE, (1 to 2) to EarState.NONE,
            (2 to 1) to EarState.NONE, (2 to 2) to EarState.NONE,
        )
        for ((raw, normalized) in cases) {
            val sample = decode(raw.first, raw.second)
            assertEquals(raw.toString(), normalized, sample.normalized)
            assertEquals(raw.first, sample.firstRaw); assertEquals(raw.second, sample.secondRaw)
        }
    }

    @Test fun undocumentedSensorCodesRemainUnknownEvenWhenTheOtherSensorIsInEar() {
        // Exhaust the byte domain. No future or undocumented value is promoted to not-in-ear.
        for (unknown in 3..255) for (known in 0..2) {
            assertEquals(EarState.UNKNOWN, decode(unknown, known).normalized)
            assertEquals(EarState.UNKNOWN, decode(known, unknown).normalized)
        }
        assertEquals(EarState.UNKNOWN, EarSample(-1, 0).normalized)
        assertEquals(EarState.UNKNOWN, EarSample(0, 256).normalized)
    }

    @Test fun truncatedEarSduNeverBecomesAnInCaseOrRemovalSample() {
        val full = frame(2, 1)
        for (length in 1 until full.size)
            assertTrue(AacpCodec.decodeSdu(full.copyOf(length)).all { it is AacpEvent.Unknown })
        assertTrue(AacpCodec.decodeSdu(byteArrayOf()).isEmpty())
        val events = AacpCodec.decodeSdu(frame(2, 0) + frame(2, 1))
        assertEquals(listOf(EarState.RIGHT, EarState.NONE), events.map { (it as AacpEvent.Ear).sample.normalized })
    }
}
