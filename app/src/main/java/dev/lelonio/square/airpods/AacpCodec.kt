// SPDX-License-Identifier: GPL-3.0-or-later
// Protocol derived from LibrePods contributors (2025), pinned source in LICENSES/LibrePods-AirPods.md.
package dev.lelonio.square.airpods

import dev.lelonio.square.airpods.protocol.*
import dev.lelonio.square.airpods.runtime.AirPodsRxCapture
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

enum class ListeningMode(val wire: Int) { OFF(1), ANC(2), TRANSPARENCY(3), ADAPTIVE(4) }
data class AirPodsIdentity(val modelNumber: String, val manufacturer: String, val firmware: String,
    val name: String? = null, val serialNumber: String? = null, val version2: String? = null,
    val hardwareRevision: String? = null, val updaterIdentifier: String? = null,
    val leftSerialNumber: String? = null, val rightSerialNumber: String? = null, val version3: String? = null) {
    val version1: String get() = firmware
    val modelDeclared: Set<Capability> get() = if (manufacturer == "Apple Inc.")
        AirPodsModels.find(modelNumber)?.capabilities ?: emptySet() else emptySet()
    val recognized: Boolean get() = manufacturer == "Apple Inc." && AirPodsModels.find(modelNumber) != null
    val modes: Set<ListeningMode> get() = if (Capability.LISTENING_MODE !in modelDeclared) emptySet()
        else ListeningMode.entries.filter { it != ListeningMode.ADAPTIVE || Capability.ADAPTIVE_AUDIO in modelDeclared }.toSet()
    val conversationAwareness get() = Capability.CONVERSATION_AWARENESS in modelDeclared
    override fun toString() = "AirPodsIdentity(model=$modelNumber, recognized=$recognized, received fields redacted)"
}
sealed interface AacpEvent {
    data class Property(val property: CommandProperty, val value: PropertyValue) : AacpEvent
    data class Ear(val sample: EarSample) : AacpEvent
    data class Stem(val sample: StemSample) : AacpEvent
    data class Head(val sample: HeadSample) : AacpEvent
    data class Mode(val value: ListeningMode) : AacpEvent
    data class ConversationSetting(val enabled: Boolean) : AacpEvent
    data class ConversationActivity(val rawStatus: Int) : AacpEvent
    data class Battery(val components: Map<Int, BatteryValue>) : AacpEvent
    data class Information(val identity: AirPodsIdentity) : AacpEvent
    /** Smart routing RX (0x0E audio source, 0x11 routing response, 0x2E connected devices, pinned
     * AACPManager.kt:507-559): another host is taking or holding the pair's audio. Only the opcode
     * survives; the payload names and addresses other people's devices and is never kept.
     * Upstream acts only on 0x11 (SetOwnershipToFalse/ShowNearbyUI) and on 0x0E naming a host
     * other than itself; a consumer that pauses should weigh [opcode] accordingly. */
    data class AudioMovedToOtherDevice(val opcode: Int) : AacpEvent
    data class Unknown(val opcode: Int?, val bytes: Int, val reason: String,
        val controlIdentifier: Int? = null, val retainedControlPayload: List<Int>? = null) : AacpEvent
}
data class BatteryValue(val percent: Int?, val status: Int)

/** Sanitized wire diagnostic. INFORMATION/name and unknown payloads never enter bytes. */
data class AacpRxFrame(val opcode: Int?, val controlIdentifier: Int?, val byteCount: Int, val bytes: List<Int>)
fun interface AirPodsRxDiagnostics {
    fun observe(generation: Long, frame: AacpRxFrame)
    companion object { val NONE = AirPodsRxDiagnostics { _, _ -> } }
}

/** AACP has no universal length field. Known fixed frames preserve their entire payload,
 * including zero-valued fields. Unknown opcodes resynchronize at a complete fixed envelope;
 * INFORMATION's opaque extension is deliberately never scanned for synthetic controls.
 * Truncated/malformed known frames cannot become an observation. */
object AacpCodec {
    private val header = byteArrayOf(4, 0, 4, 0)
    private val BATTERY_COMPONENTS = setOf(2, 4, 8)
    private const val BATTERY_DISCONNECTED = 4
    /** Variable length, payload carries MACs and host names: decoded to an opcode only, and
     * deliberately absent from [capture]'s safe set so no byte reaches capture or diagnostics. */
    private val SMART_ROUTING_OPCODES = setOf(0x0e, 0x11, 0x2e)
    fun handshake() = byteArrayOf(0, 0, 4, 0, 1, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    fun subscribe() = header + byteArrayOf(0x0f, 0, -1, -1, -1, -1)
    fun featureFlags() = header + byteArrayOf(0x4d, 0, 0xd7.toByte(), 0, 0, 0, 0, 0, 0, 0)
    // Pinned AACPManager sends this opaque packet during initialization. Its firmware
    // semantics are not established here; TX is never evidence that EQ was enabled.
    fun bootstrapExtension() = header + byteArrayOf(0x29, 0, 0, -1, -1, -1, -1, -1, -1, -1)
    fun mode(value: ListeningMode) = control(0x0d, value.wire)
    fun conversation(enabled: Boolean) = control(0x28, if (enabled) 1 else 2)
    private fun control(id: Int, value: Int) = controlPayload(id, listOf(value))
    fun controlPayload(id: Int, payload: List<Int>): ByteArray {
        require(id in 0..255 && payload.size in 1..4 && payload.all { it in 0..255 })
        return header + byteArrayOf(9, 0, id.toByte()) + payload.map { it.toByte() }.toByteArray() + ByteArray(4-payload.size)
    }
    fun property(property: CommandProperty, value: Int): ByteArray {
        val spec = requireNotNull(AirPodsProperties.spec(property))
        return controlPayload(spec.wireId, spec.encode(value))
    }
    fun eq(value: PropertyValue.Eq) = header + byteArrayOf(0x63,0,5,0,1,if(value.enabled) 2 else 1,
        value.low.toByte(),value.mid.toByte(),value.high.toByte())
    fun rename(name: String): ByteArray {
        val bytes = name.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..255 && '\u0000' !in name && strictUtf8(bytes) == name)
        return header + byteArrayOf(0x1a,0,1,bytes.size.toByte(),0) + bytes
    }


    fun headTracking(format: HeadPacketFormat, start: Boolean): ByteArray = when {
        format == HeadPacketFormat.STANDARD && start -> hex("04 00 04 00 17 00 00 00 10 00 10 00 08 a1 02 42 0b 08 0e 10 02 1a 05 01 40 9c 00 00")
        format == HeadPacketFormat.STANDARD && !start -> hex("04 00 04 00 17 00 00 00 10 00 11 00 08 7e 10 02 42 0b 08 4e 10 02 1a 05 01 00 00 00 00")
        format == HeadPacketFormat.ALTERNATE && start -> hex("04 00 04 00 17 00 00 00 10 00 0f 00 08 73 42 0b 08 10 10 02 1a 05 01 40 9c 00 00")
        format == HeadPacketFormat.ALTERNATE && !start -> hex("04 00 04 00 17 00 00 00 10 00 0f 00 08 75 42 0b 08 10 10 02 1a 05 01 00 00 00 00")
        else -> error("Unknown tracking format")
    }
    private fun hex(s: String) = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    private fun fixedSize(opcode: Int) = when (opcode) {
        9 -> 11; 4 -> 22; 0x4b -> 10; 6, 0x19 -> 8; 0x63 -> 13; 0x17 -> 70; else -> null
    }
    private fun nextFixedFrame(bytes: ByteArray, from: Int): Int? = (from..bytes.size - 6).firstOrNull { i ->
        bytes[i] == 4.toByte() && bytes[i+1] == 0.toByte() && bytes[i+2] == 4.toByte() &&
            bytes[i+3] == 0.toByte() && bytes[i+5] == 0.toByte() &&
            fixedSize(bytes[i+4].toInt() and 255)?.let { bytes.size - i >= it } == true
    }
    private fun nextEnvelope(bytes: ByteArray, from: Int): Int? = (from..bytes.size - 6).firstOrNull { i ->
        bytes[i] == 4.toByte() && bytes[i+1] == 0.toByte() && bytes[i+2] == 4.toByte() &&
            bytes[i+3] == 0.toByte() && bytes[i+5] == 0.toByte()
    }

    private fun capture(frame: ByteArray, onFrame: ((AacpRxFrame) -> Unit)?) {
        val opcode = frame.getOrNull(4)?.toInt()?.and(255) ?: return
        val id = if (opcode == 9) frame.getOrNull(6)?.toInt()?.and(255) else null
        val safe = (opcode == 9 && id?.let(AirPodsProperties::wire) != null) || opcode in setOf(4, 0x63, 6, 0x19, 0x4b, 0x17)
        val payloadStart = (if (opcode == 9) 7 else 6).coerceAtMost(frame.size)
        // W2's process-local sink is inert until explicitly enabled. Private/unknown frame
        // bytes never cross this boundary; a zero-filled placeholder preserves length only.
        if (AirPodsRxCapture.enabled) AirPodsRxCapture.record(opcode, id, if (safe) frame.copyOfRange(payloadStart, frame.size)
            else ByteArray(frame.size - payloadStart))
        if (onFrame != null) runCatching {
            onFrame(AacpRxFrame(opcode, id, frame.size,
                if (safe && opcode != 0x17) frame.map { it.toInt() and 255 } else emptyList()))
        }
    }

    /** Physical write attempt, not an acknowledgement. Never copy rename/identity/opaque bytes. */
    internal fun captureTx(packet: ByteArray) {
        if (!AirPodsRxCapture.enabled) return
        val framed = packet.size >= 6 && packet.copyOfRange(0, 4).contentEquals(header)
        val opcode = if (framed) packet[4].toInt() and 255 else -1
        val id = if (opcode == 9) packet.getOrNull(6)?.toInt()?.and(255) else null
        val start = (if (opcode == 9) 7 else 6).coerceAtMost(packet.size)
        val safe = (opcode == 9 && id?.let(AirPodsProperties::wire) != null) || opcode == 0x63
        AirPodsRxCapture.recordTx(opcode, id, if (safe) packet.copyOfRange(start, packet.size)
            else ByteArray(packet.size - start))
    }

    @JvmOverloads
    fun decodeSdu(sdu: ByteArray, onFrame: ((AacpRxFrame) -> Unit)? = null): List<AacpEvent> {
        val events = mutableListOf<AacpEvent>()
        var offset = 0
        while (offset < sdu.size) {
            var p = sdu.copyOfRange(offset, sdu.size)
            // Padding belongs to the envelope, not a reason to trim meaningful payload zeros.
            if (p.all { it == 0.toByte() }) break
            if (p[0] == 0.toByte()) {
                val next = nextFixedFrame(p, 1)
                if (next != null && p.take(next).all { it == 0.toByte() }) {
                    offset += next
                    continue
                }
            }
            // Upstream ControlCommand.fromByteArray explicitly accepts repeated AACP prefixes.
            // Only unwrap when the terminal opcode is a control, rather than treating payload as framing.
            var wrappers = 0
            while (p.size - wrappers >= 8 && p.copyOfRange(wrappers, wrappers + 4).contentEquals(header) &&
                p.copyOfRange(wrappers + 4, wrappers + 8).contentEquals(header)) wrappers += 4
            if (wrappers > 0 && p.getOrNull(wrappers + 4) == 9.toByte()) {
                offset += wrappers
                p = p.copyOfRange(wrappers, p.size)
            }
            fun unknown(reason: String) = AacpEvent.Unknown(p.getOrNull(4)?.toInt()?.and(255), p.size, reason)
            if (p.size < 6 || !p.copyOfRange(0, 4).contentEquals(header) || p[5] != 0.toByte()) {
                events += unknown("Unrecognized header or truncated SDU"); break
            }
            val opcode = p[4].toInt() and 255
            val size = when (opcode) {
                0x1d -> p.size // INFORMATION has an opaque, potentially header-looking extension.
                0x17 -> if (p.size < 70) 70 else nextEnvelope(p, 70) ?: p.size
                else -> fixedSize(opcode) ?: nextFixedFrame(p, 6) ?: p.size
            }
            if (p.size < size) { capture(p, onFrame); events += unknown("Truncated frame"); break }
            val frame = p.copyOf(size)
            capture(frame, onFrame)
            fun frameUnknown(reason: String) = AacpEvent.Unknown(opcode, frame.size, reason)
            val event: AacpEvent = when (frame[4].toInt() and 255) {
                9 -> {
                    val spec = AirPodsProperties.wire(frame[6].toInt() and 255)
                    val value = spec?.decode(frame.sliceArray(7..10).map { it.toInt() and 255 })
                    fun rejected() = AacpEvent.Unknown(9, frame.size, "Unknown or malformed control",
                        frame[6].toInt() and 255, frame.sliceArray(7..10).map { it.toInt() and 255 })
                    if (spec == null || value == null) rejected()
                    else when (spec.property) {
                        // A mode the enum cannot name stays a retained Unknown, never an exception.
                        CommandProperty.MODE -> ListeningMode.entries.firstOrNull { it.wire == value.value }
                            ?.let { AacpEvent.Mode(it) } ?: rejected()
                        CommandProperty.CONVERSATION -> AacpEvent.ConversationSetting(value.value == 1)
                        else -> AacpEvent.Property(spec.property, value)
                    }
                }
                6 -> AacpEvent.Ear(EarSample(frame[6].toInt() and 255, frame[7].toInt() and 255))
                0x19 -> if ((frame[6].toInt() and 255) in 5..8 && (frame[7].toInt() and 255) in 1..2)
                    AacpEvent.Stem(StemSample(frame[6].toInt(), frame[7].toInt())) else frameUnknown("Unknown stem event")
                0x63 -> {
                    val n = frame.map { it.toInt() and 255 }
                    if (n[6] != 5 || n[7] != 0 || n[8] != 1 || n[9] !in 1..2 || n.slice(10..12).any { it > 100 })
                        frameUnknown("Malformed EQ")
                    else AacpEvent.Property(CommandProperty.EQ, PropertyValue.Eq(n[9] == 2,n[10],n[11],n[12]))
                }
                // Pinned AACPManager.kt:486-496 forwards any tracking frame of at least 70 bytes;
                // the layout bytes 6..11 only classify it there. Offsets 43..54 are unchanged.
                0x17 -> if (frame.size < 70) frameUnknown("Truncated tracking frame") else {
                    fun signed(i: Int) = ((frame[i+1].toInt() shl 8) or (frame[i].toInt() and 255))
                    AacpEvent.Head(HeadSample(signed(43),signed(45),signed(47),signed(51),signed(53),frame[10].toInt()))
                }
                // Pinned Packets.kt:188-216 reads three [component, _, level, status] slots at fixed
                // offsets. A slot with an unknown component is dropped alone; any status is kept as
                // received. A level above 100 or a disconnected slot is no percentage (never 0%).
                4 -> {
                    val components = listOf(7, 12, 17).mapNotNull { i ->
                        val key = frame[i].toInt() and 255
                        val level = frame[i + 2].toInt() and 255
                        val status = frame[i + 3].toInt() and 255
                        if (key !in BATTERY_COMPONENTS) null
                        else key to BatteryValue(level.takeIf { it <= 100 && status != BATTERY_DISCONNECTED }, status)
                    }.toMap()
                    if (components.isEmpty()) frameUnknown("No known battery component") else AacpEvent.Battery(components)
                }
                // Pinned Packets.kt:242-244: status is byte 9 of the fixed frame, whatever precedes it.
                // Statuses without a known meaning reach consumers raw and are ignored there.
                0x4b -> AacpEvent.ConversationActivity(frame[9].toInt() and 255)
                0x1d -> parseInformation(frame) ?: frameUnknown("Incomplete or unknown information layout")
                in SMART_ROUTING_OPCODES -> AacpEvent.AudioMovedToOtherDevice(opcode)
                else -> frameUnknown("Unhandled opcode")
            }
            events += event
            offset += size
        }
        return events
    }

    private fun parseInformation(p: ByteArray): AacpEvent.Information? {
        // Skip the leading binary field and one string; preserve all eleven positional fields.
        // The complete known core is strict; later bytes are an opaque firmware extension.
        val data = p.copyOfRange(6, p.size)
        var i = data.indexOf(0.toByte())
        if (i < 0) return null
        i++ // first binary field terminator; preserve empty positional strings thereafter
        val strings = mutableListOf<String>()
        repeat(12) {
            val start = i
            while (i < data.size && data[i] != 0.toByte()) i++
            if (i == data.size) return null
            strings += strictUtf8(data.copyOfRange(start, i)) ?: return null
            i++
        }
        // The known terminated core ends at i. Observed firmware appends binary data here.
        // Consume that extension in this INFORMATION frame; never decode it or scan for headers.
        val fields = strings.drop(1)
        if (fields[1].isBlank() || fields[2] != "Apple Inc.") return null
        return AacpEvent.Information(AirPodsIdentity(fields[1], fields[2], fields[4], fields[0], fields[3],
            fields[5], fields[6], fields[7], fields[8], fields[9], fields[10]))
    }
    private fun strictUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) { null }
}
