// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference

/** One split wire frame, before semantic decoding. payload excludes AACP header, opcode and
 * control id. Never pass an SDU, identity, name, address or decoded object. No authority/effects.
 * Unknown/identity opcodes retain metadata only. Implementations must be bounded and nonthrowing.
 */
fun interface AirPodsRawRxSink {
    fun record(opcode: Int, id: Int?, payload: ByteArray)
}

/** W1 calls record() for each split frame. Inert until a session explicitly installs a capture. */
object AirPodsRxCapture : AirPodsRawRxSink {
    private val active = AtomicReference<AirPodsRawRxBuffer?>(null)
    val enabled: Boolean get() = active.get() != null
    override fun record(opcode: Int, id: Int?, payload: ByteArray) {
        val capture = active.get() ?: return
        runCatching { capture.record(opcode, id, payload) }
    }
    fun recordTx(opcode: Int, id: Int?, payload: ByteArray) {
        val capture = active.get() ?: return
        runCatching { capture.recordTx(opcode, id, payload) }
    }
    internal fun install(buffer: AirPodsRawRxBuffer) { active.set(buffer) }
    internal fun remove(buffer: AirPodsRawRxBuffer) { active.compareAndSet(buffer, null) }
}

/** Bounded memory only; exporting is off the RX path. Relative monotonic times only.
 * Payload allowlist deliberately excludes INFORMATION (0x1d), NAME (0x1a) and unknown frames.
 */
class AirPodsRawRxBuffer(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val capacity: Int = 512,
) : AirPodsRawRxSink {
    init { require(capacity in 1..4096) }
    private val origin = clock()
    private val records = ArrayDeque<String>()
    private var dropped = 0L
    private var stemConfigObserved = false
    @Synchronized override fun record(opcode: Int, id: Int?, payload: ByteArray) {
        if (opcode == 9 && id == 0x39 && payload.size == 4 && (payload[0].toInt() and 255) in 0..15 &&
            payload.drop(1).all { it == 0.toByte() }) stemConfigObserved = true
        append("RX", opcode, id, payload)
        if (opcode == 0x19 && payload.size == 2 && payload[0].toInt() == 8 &&
            payload[1].toInt() in 1..2 && !stemConfigObserved)
            add("${relativeTime()} NOTE LONG_PRESS_WITHOUT_OBSERVED_STEM_CONFIG")
    }
    @Synchronized fun recordTx(opcode: Int, id: Int?, payload: ByteArray) = append("TX", opcode, id, payload)
    private fun relativeTime() = (clock() - origin).coerceAtLeast(0)
    private fun append(direction: String, opcode: Int, id: Int?, payload: ByteArray) {
        val permitted = when (opcode) {
            0x09 -> id in CONTROL_IDS && payload.size <= 64
            0x63 -> payload.size <= 7
            else -> direction == "RX" && when (opcode) {
            0x04 -> payload.size <= 16
            0x06, 0x19 -> payload.size <= 2
            0x17 -> payload.size <= 128
            0x4b -> payload.size <= 4
            else -> false
            }
        }
        val hex = if (permitted) payload.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') } else "omitted"
        val safeId = if (opcode == 0x09) id?.takeIf { it in 0..255 } else null
        add("${relativeTime()} $direction op=${opcode.takeIf { it in 0..255 }?.toString(16) ?: "?"} id=${safeId?.toString(16) ?: "-"} len=${payload.size} bytes=$hex")
    }
    private fun add(line: String) {
        if (records.size == capacity) { records.removeFirst(); dropped++ }
        records.addLast(line)
    }
    @Synchronized fun export(): String = "relative_ms direction opcode control_id payload_length payload_hex; dropped=$dropped\n" + records.joinToString("\n") + "\n"
    private companion object {
        val CONTROL_IDS = setOf(0x01, 0x0a, 0x0d, 0x17, 0x18, 0x1a, 0x1b, 0x1f,
            0x20, 0x23, 0x24, 0x25, 0x26, 0x28, 0x2e, 0x34, 0x35, 0x37, 0x39, 0x3b)
    }
}
