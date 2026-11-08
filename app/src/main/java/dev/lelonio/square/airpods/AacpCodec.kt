// SPDX-License-Identifier: GPL-3.0-or-later
// Protocol derived from LibrePods contributors (2025), pinned source in LICENSES/LibrePods-AirPods.md.
package dev.lelonio.square.airpods

import dev.lelonio.square.airpods.protocol.*
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
    data class Unknown(val opcode: Int?, val bytes: Int, val reason: String,
        val controlIdentifier: Int? = null, val retainedControlPayload: List<Int>? = null) : AacpEvent
}
data class BatteryValue(val percent: Int?, val status: Int)

/** AACP has no universal length field. Consume complete L2CAP SDUs, never scan payload for headers.
 * Known fixed frames may share an SDU. Unknown/variable frames consume the remaining SDU.
 * A truncated frame produces diagnostics only; it cannot confirm a command or manufacture state. */
object AacpCodec {
    private val header = byteArrayOf(4, 0, 4, 0)
    fun handshake() = byteArrayOf(0, 0, 4, 0, 1, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    fun subscribe() = header + byteArrayOf(0x0f, 0, -1, -1, -1, -1)
    fun featureFlags() = header + byteArrayOf(0x4d, 0, 0xd7.toByte(), 0, 0, 0, 0, 0, 0, 0)
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

    fun decodeSdu(sdu: ByteArray): List<AacpEvent> {
        val events = mutableListOf<AacpEvent>()
        var offset = 0
        while (offset < sdu.size) {
            var p = sdu.copyOfRange(offset, sdu.size)
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
            val size = when (p[4].toInt() and 255) { 9 -> 11; 4 -> 22; 0x4b -> 10; 6, 0x19 -> 8; 0x63 -> 13; else -> p.size }
            if (p.size < size) { events += unknown("Truncated frame"); break }
            val frame = p.copyOf(size)
            val event: AacpEvent = when (frame[4].toInt() and 255) {
                9 -> {
                    val spec = AirPodsProperties.wire(frame[6].toInt() and 255)
                    val value = spec?.decode(frame.sliceArray(7..10).map { it.toInt() and 255 })
                    if (spec == null || value == null) AacpEvent.Unknown(9, frame.size, "Unknown or malformed control",
                        frame[6].toInt() and 255, frame.sliceArray(7..10).map { it.toInt() and 255 })
                    else when (spec.property) {
                        CommandProperty.MODE -> AacpEvent.Mode(ListeningMode.entries.first { it.wire == value.value })
                        CommandProperty.CONVERSATION -> AacpEvent.ConversationSetting(value.value == 1)
                        else -> AacpEvent.Property(spec.property, value)
                    }
                }
                6 -> AacpEvent.Ear(EarSample(frame[6].toInt() and 255, frame[7].toInt() and 255))
                0x19 -> if ((frame[6].toInt() and 255) in 5..8 && (frame[7].toInt() and 255) in 1..2)
                    AacpEvent.Stem(StemSample(frame[6].toInt(), frame[7].toInt())) else unknown("Unknown stem event")
                0x63 -> {
                    val n = frame.map { it.toInt() and 255 }
                    if (n[6] != 5 || n[7] != 0 || n[8] != 1 || n[9] !in 1..2 || n.slice(10..12).any { it > 100 })
                        unknown("Malformed EQ")
                    else AacpEvent.Property(CommandProperty.EQ, PropertyValue.Eq(n[9] == 2,n[10],n[11],n[12]))
                }
                0x17 -> {
                    if (frame.size < 70 || !frame.sliceArray(6..9).contentEquals(byteArrayOf(0,0,0x10,0)) ||
                        (frame[10].toInt() and 255) !in 0x44..0x45 || frame[11] != 0.toByte()) unknown("Unknown tracking layout")
                    else {
                        fun signed(i: Int) = ((frame[i+1].toInt() shl 8) or (frame[i].toInt() and 255))
                        AacpEvent.Head(HeadSample(signed(43),signed(45),signed(47),signed(51),signed(53),frame[10].toInt()))
                    }
                }
                4 -> {
                    val components = listOf(7, 12, 17).associate { i ->
                        val level = frame[i + 2].toInt() and 255
                        val status = frame[i + 3].toInt() and 255
                        (frame[i].toInt() and 255) to BatteryValue(level.takeIf { it <= 100 && status in setOf(1,2,5) }, status)
                    }
                    if (frame[6] != 3.toByte() || components.size != 3 || components.keys.any { it !in setOf(2, 4, 8) }) unknown("Unsupported battery layout")
                    else AacpEvent.Battery(components)
                }
                0x4b -> if (frame[6] == 2.toByte() && frame[7] == 0.toByte() && (frame[9].toInt() and 255) in setOf(1,2,6,8,9))
                    AacpEvent.ConversationActivity(frame[9].toInt() and 255) else unknown("Unknown CA activity layout")
                0x1d -> parseInformation(frame) ?: unknown("Incomplete or unknown information layout")
                else -> unknown("Unhandled opcode")
            }
            events += event
            offset += size
        }
        return events
    }

    private fun parseInformation(p: ByteArray): AacpEvent.Information? {
        // Match upstream parseInformationPacket: skip first binary field then one string;
        // require all eleven terminated fields so partial information never verifies a pair.
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
        if (data.drop(i).any { it != 0.toByte() }) return null
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
