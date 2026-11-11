// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods

import android.os.SystemClock
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

/** Closed vocabulary only; never a device string or exception message. */
internal enum class DebugIngressReason(val code: Int) {
    UNKNOWN_REASON(0), UNRECOGNIZED_HEADER_OR_TRUNCATED_SDU(1), TRUNCATED_FRAME(2),
    UNKNOWN_OR_MALFORMED_CONTROL(3), UNKNOWN_STEM_EVENT(4), MALFORMED_EQ(5),
    UNKNOWN_TRACKING_LAYOUT(6), UNSUPPORTED_BATTERY_LAYOUT(7), UNKNOWN_CA_ACTIVITY_LAYOUT(8),
    INCOMPLETE_OR_UNKNOWN_INFORMATION_LAYOUT(9), UNHANDLED_OPCODE(10);
    companion object {
        fun fromCode(code: Int?) = entries.firstOrNull { it.code == code } ?: UNKNOWN_REASON
    }
}

/** Passive after explicit FUNCTIONAL_TEST Start. No disk, log or network on the read path.
 * The original SDU is never mutated; this class cannot accept an identity or transmit packets.
 * Only a structural INFORMATION candidate is copied, at a boundary reached without scanning.
 */
internal class DebugInformationIngress(
    private val observer: AirPodsQualificationObserver,
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val maxSdus: Int = 128,
    private val maxBytes: Int = 16_384,
    private val windowMs: Long = 60_000,
) {
    init { require(maxSdus in 1..128 && maxBytes in 1..16_384 && windowMs in 1..60_000) }
    private val started = now()
    private var accepting = true
    private var seen = 0
    private var stopReason = 0 // 0 explicit close; 1 SDU budget; 2 time budget
    private var selected: ByteArray? = null
    private var selectedGeneration = -1L
    private var selectedSequence = -1
    private var selectedOffset = -1
    private var candidateSeen = 0

    @Synchronized fun disarm() { accepting = false }

    @Synchronized fun observe(generation: Long, sdu: ByteArray) {
        if (!accepting) return
        if (seen >= maxSdus || now() - started !in 0 until windowMs) {
            accepting = false
            stopReason = if (seen >= maxSdus) 1 else 2
            emit(QualificationEvent(QualificationStep.RX, QualificationPhase.UNCONFIRMED,
                generation, flags = 32, values = listOf(1, seen, stopReason)))
            return
        }
        val sequence = ++seen
        val location = candidateBoundary(sdu)
        val decoded = AacpCodec.decodeSdu(sdu)
        if (location != null) {
            candidateSeen++
            val (offset, _) = location
            if (selected == null && sdu.size - offset <= maxBytes) {
                selected = sdu.copyOfRange(offset, sdu.size)
                selectedGeneration = generation
                selectedSequence = sequence
                selectedOffset = offset
            }
        }
        val prefix = prefixAt(sdu, 0)
        val opcode = if (prefix && sdu.size >= 6) u(sdu[4]) else -1
        val framing = when {
            sdu.size < 6 -> 3
            !prefix -> 4
            sdu[5] != 0.toByte() -> 2
            else -> 1
        }
        // flags=30, schema=1: sequence,size,firstOpcode,framing,candidateOffset,
        // candidateBytes,leadingWrappers,capturedSequence. No payload-derived strings.
        emit(QualificationEvent(QualificationStep.RX, QualificationPhase.OBSERVED,
            generation, value = decoded.count { it is AacpEvent.Unknown }, flags = 30,
            values = listOf(1, sequence, sdu.size, opcode, framing,
                location?.first ?: -1, location?.let { sdu.size - it.first } ?: 0,
                location?.second ?: 0, selectedSequence)))
        // At most one reason row per SDU; value above preserves the total Unknown count.
        decoded.withIndex().firstOrNull { it.value is AacpEvent.Unknown }?.let { (index, event) ->
            if (event is AacpEvent.Unknown) {
                // Invalid framing has no trustworthy opcode: never publish an arbitrary byte.
                val reason = reasonCode(event.reason)
                emit(QualificationEvent(QualificationStep.RX, QualificationPhase.OBSERVED,
                    generation, flags = 31, values = listOf(1, sequence, index,
                        if (reason == 1) -1 else event.opcode ?: -1, event.bytes, reason)))
            }
        }
    }

    private fun emit(event: QualificationEvent) { runCatching { observer.record(event) } }

    /** Fixed envelope bytes plus fixed-frame lengths from the unchanged codec.
     * Repeated prefixes are inspected only at a known frame start. This does NOT authorize
     * their parsing as INFORMATION. Variable/unknown frames stop this walk immediately.
     */
    private fun candidateBoundary(sdu: ByteArray): Pair<Int, Int>? {
        var offset = 0
        while (offset + 6 <= sdu.size && prefixAt(sdu, offset)) {
            var wrappers = 0
            while (prefixAt(sdu, offset + wrappers) && prefixAt(sdu, offset + wrappers + 4)) wrappers += 4
            val terminal = offset + wrappers
            if (terminal + 6 > sdu.size) return null
            val opcode = u(sdu[terminal + 4])
            if (opcode == 0x1d) return offset to wrappers / 4
            if (sdu[terminal + 5] != 0.toByte() || wrappers > 0 && opcode != 9) return null
            val size = when (opcode) {
                9 -> 11; 4 -> 22; 0x4b -> 10; 6, 0x19 -> 8; 0x63 -> 13
                else -> return null
            }
            if (terminal + size > sdu.size) return null
            offset = terminal + size
        }
        return null
    }

    private fun prefixAt(bytes: ByteArray, at: Int) = at >= 0 && at + 4 <= bytes.size &&
        bytes[at] == 4.toByte() && bytes[at + 1] == 0.toByte() &&
        bytes[at + 2] == 4.toByte() && bytes[at + 3] == 0.toByte()
    private fun u(value: Byte) = value.toInt() and 255
    private fun reasonCode(reason: String) = when (reason) {
        "Unrecognized header or truncated SDU" -> 1
        "Truncated frame" -> 2
        "Unknown or malformed control" -> 3
        "Unknown stem event" -> 4
        "Malformed EQ" -> 5
        "Unknown tracking layout" -> 6
        "Unsupported battery layout" -> 7
        "Unknown CA activity layout" -> 8
        "Incomplete or unknown information layout" -> 9
        "Unhandled opcode" -> 10
        else -> 0
    }

    /** Private binary, never returned by the sanitized journal export.
     * Magic FIP1, start/end elapsed, SDU count, stop reason, candidate count,
     * generation, selected SDU sequence, offset, byte count, exact candidate bytes.
     * A zero-byte record replaces stale captures when this session saw no candidate.
     */
    @Synchronized internal fun drainPrivateRecord(): ByteArray {
        accepting = false
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { out ->
            out.writeInt(0x46495031)
            out.writeLong(started); out.writeLong(now())
            out.writeInt(seen); out.writeInt(stopReason); out.writeInt(candidateSeen)
            out.writeLong(selectedGeneration)
            out.writeInt(selectedSequence); out.writeInt(selectedOffset)
            out.writeInt(selected?.size ?: 0)
            selected?.let(out::write)
        }
        selected?.fill(0); selected = null
        return output.toByteArray()
    }

    /** Invoke after transport teardown, on IO, for the explicit session only.
     * App-private noBackupFilesDir destination; no share intent or automatic upload.
     */
    fun persistTo(file: File) {
        val bytes = drainPrivateRecord()
        val atomic = AtomicFile(file)
        var stream: java.io.FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        } finally { bytes.fill(0) }
    }
}
