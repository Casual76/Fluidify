// SPDX-License-Identifier: GPL-3.0-or-later
// Derived from LibrePods contributors (2025), AACPManager.kt and active Settings screens,
// pinned 89356c08cea3db0b6b54792d9329d09a87cc85fa. See implementation/protocol/REPORT.md.
package dev.lelonio.square.airpods.protocol

import dev.lelonio.square.airpods.CommandProperty
import dev.lelonio.square.airpods.AirPodsIdentity

sealed interface PropertyValue {
    data class Number(val value: Int) : PropertyValue
    data class Eq(val enabled: Boolean, val low: Int, val mid: Int, val high: Int) : PropertyValue {
        init { require(listOf(low, mid, high).all { it in 0..100 }) }
    }
    data class Name(val value: String) : PropertyValue {
        override fun toString() = "Name(redacted)"
    }
}
/** [HEARING_AID_PAIR] is 0x2C's two bytes `[enrolled, active]`, packed by [HearingAidWire]. */
enum class ControlEncoding { SCALAR, BOOLEAN_1_2, INVERTED_PERCENT, CHIME, CALL_ASSIGNMENT, MODE_MASK, STEM_MASK, HEARING_AID_PAIR }

/** 0x2C carries `[enrolled, active]` (pinned HearingAidScreen.kt:115-124,209-218). Internally (RX
 * events, stored RX, intents, persistence) one Number holds both: the enrolled byte in bits 8..15,
 * exactly as last received; active (1 on, 2 off) in bits 0..7. Readers of the state
 * (propertyObserved, propertyEffective) see only the plain 1/2 every other switch uses, and a
 * write may pass 1/2: the enrolled byte always comes from RX, nothing here can make one up. */
object HearingAidWire {
    fun pack(enrolled: Int, enabled: Boolean): Int {
        require(enrolled in 0..255) { "Invalid enrolled byte" }
        return (enrolled shl 8) or (if (enabled) 1 else 2)
    }
    fun enrolled(value: Int): Int = (value shr 8) and 0xFF
    fun enabled(value: Int): Boolean = (value and 0xFF) == 1
    /** The switch value a reader sees: 1 on, 2 off. */
    fun shown(value: PropertyValue): PropertyValue =
        (value as? PropertyValue.Number)?.let { PropertyValue.Number(it.value and 0xFF) } ?: value
}

data class PropertySpec(
    val property: CommandProperty, val catalogId: String, val wireId: Int, val group: String,
    val range: IntRange, val options: Map<Int, String> = emptyMap(),
    val encoding: ControlEncoding = ControlEncoding.SCALAR, val capability: Capability? = null,
    val internalDependency: Boolean = false, val debounceMs: Long = 0,
) {
    fun accepts(value: Int) = when (encoding) {
        ControlEncoding.MODE_MASK -> value in range && Integer.bitCount(value) >= 2
        ControlEncoding.HEARING_AID_PAIR -> value in 0..0xFFFF && (value and 0xFF) in 1..2
        else -> value in range
    }
    /** Receiving one/zero selected modes is not permission to write an invalid cycle. */
    fun acceptsReceived(value: Int) = when (encoding) {
        ControlEncoding.MODE_MASK -> value in 0..15
        ControlEncoding.HEARING_AID_PAIR -> accepts(value)
        else -> value in range
    }
    /** Visibility only: which controls the UI offers for a model. Never a reason to refuse a write. */
    fun candidate(identity: AirPodsIdentity) = identity.recognized &&
        (capability == null || capability in identity.modelDeclared)
    fun encode(value: Int): List<Int> {
        require(accepts(value)) { "Invalid property range" }
        return when (encoding) {
            ControlEncoding.INVERTED_PERCENT -> listOf(100 - value)
            ControlEncoding.CHIME -> listOf(value, 0x50)
            ControlEncoding.CALL_ASSIGNMENT -> listOf(0, value)
            ControlEncoding.HEARING_AID_PAIR -> listOf(HearingAidWire.enrolled(value), value and 0xFF)
            else -> listOf(value)
        }
    }
    /** Pinned AACPManager reads the four value bytes and its screens look only at the byte that
     * carries the value. So do we: every other byte is ignored, whatever it holds — CHIME is byte 0
     * with or without its 0x50, CALL is byte 1 whatever byte 0 says. Booleans read 1 as on and 0 or
     * 2 as off. Percentages clamp; anything else outside its domain is still not a value. */
    fun decode(bytes: List<Int>): PropertyValue.Number? {
        if (bytes.size != 4) return null
        val raw = when (encoding) {
            ControlEncoding.CALL_ASSIGNMENT -> bytes[1]
            else -> bytes[0]
        }
        val value = when (encoding) {
            ControlEncoding.INVERTED_PERCENT -> (100 - raw).coerceIn(0, 100)
            ControlEncoding.CHIME -> raw.coerceIn(0, 100)
            ControlEncoding.BOOLEAN_1_2 -> booleanRaw(raw) ?: return null
            ControlEncoding.HEARING_AID_PAIR -> (raw shl 8) or (booleanRaw(bytes[1]) ?: return null)
            // Upstream's swipe-interval screen treats a received 0 as the default interval.
            else -> if (property == CommandProperty.VOLUME_SWIPE_INTERVAL && raw == 0) 1 else raw
        }
        return value.takeIf(::acceptsReceived)?.let { PropertyValue.Number(it) }
    }
    private fun booleanRaw(raw: Int) = when (raw) { 1 -> 1; 0, 2 -> 2; else -> null }
}
object AirPodsProperties {
    private val bool = mapOf(1 to "on", 2 to "off")
    val controls = listOf(
        PropertySpec(CommandProperty.MIC_MODE, "aacp.mic_mode", 0x01, "microphone", 0..2, mapOf(0 to "automatic", 1 to "right", 2 to "left")),
        PropertySpec(CommandProperty.DOUBLE_CLICK_INTERVAL, "aacp.double_click_interval", 0x17, "accessibility", 0..2, mapOf(0 to "default", 1 to "slower", 2 to "slowest")),
        PropertySpec(CommandProperty.CLICK_HOLD_INTERVAL, "aacp.click_hold_interval", 0x18, "accessibility", 0..2, mapOf(0 to "default", 1 to "slower", 2 to "slowest")),
        PropertySpec(CommandProperty.LISTENING_MODE_CONFIGS, "aacp.listening_mode_configs", 0x1a, "gestures", 1..15, encoding=ControlEncoding.MODE_MASK, capability=Capability.STEM_CONFIG),
        PropertySpec(CommandProperty.ONE_BUD_ANC_MODE, "aacp.one_bud_anc_mode", 0x1b, "accessibility", 1..2, bool, ControlEncoding.BOOLEAN_1_2, Capability.LISTENING_MODE),
        PropertySpec(CommandProperty.MODE, "aacp.listening_mode", 0x0d, "audio", 1..4, mapOf(1 to "off", 2 to "anc", 3 to "transparency", 4 to "adaptive"), capability=Capability.LISTENING_MODE),
        PropertySpec(CommandProperty.CHIME_VOLUME, "aacp.chime_volume", 0x1f, "volume", 0..100, encoding=ControlEncoding.CHIME),
        PropertySpec(CommandProperty.VOLUME_SWIPE_INTERVAL, "aacp.volume_swipe_interval", 0x23, "volume", 1..3, mapOf(1 to "default", 2 to "longer", 3 to "longest"), capability=Capability.SWIPE_FOR_VOLUME),
        PropertySpec(CommandProperty.CALL_MANAGEMENT_CONFIG, "aacp.call_management_config", 0x24, "calls", 2..3, mapOf(2 to "flipped", 3 to "normal"), ControlEncoding.CALL_ASSIGNMENT),
        PropertySpec(CommandProperty.VOLUME_SWIPE_MODE, "aacp.volume_swipe_mode", 0x25, "volume", 1..2, bool, ControlEncoding.BOOLEAN_1_2, Capability.SWIPE_FOR_VOLUME),
        PropertySpec(CommandProperty.ADAPTIVE_VOLUME_CONFIG, "aacp.adaptive_volume_config", 0x26, "audio", 1..2, bool, ControlEncoding.BOOLEAN_1_2, Capability.ADAPTIVE_VOLUME),
        PropertySpec(CommandProperty.CONVERSATION, "aacp.conversation_detect_config", 0x28, "audio", 1..2, bool, ControlEncoding.BOOLEAN_1_2, Capability.CONVERSATION_AWARENESS),
        PropertySpec(CommandProperty.AUTO_ANC_STRENGTH, "aacp.auto_anc_strength", 0x2e, "audio", 0..100, encoding=ControlEncoding.INVERTED_PERCENT, capability=Capability.ADAPTIVE_AUDIO, debounceMs=150),
        PropertySpec(CommandProperty.ALLOW_OFF_OPTION, "aacp.allow_off_option", 0x34, "audio", 1..2, bool, ControlEncoding.BOOLEAN_1_2, Capability.LOUD_SOUND_REDUCTION),
        PropertySpec(CommandProperty.SLEEP_DETECTION_CONFIG, "aacp.sleep_detection_config", 0x35, "automation", 1..2, bool, ControlEncoding.BOOLEAN_1_2, Capability.SLEEP_DETECTION),
        PropertySpec(CommandProperty.EAR_DETECTION_CONFIG, "aacp.ear_detection_config", 0x0a, "automation", 1..2, bool, ControlEncoding.BOOLEAN_1_2),
        PropertySpec(CommandProperty.AUTOMATIC_CONNECTION_CONFIG, "aacp.automatic_connection_config", 0x20, "connection", 1..2, bool, ControlEncoding.BOOLEAN_1_2),
        PropertySpec(CommandProperty.PPE_TOGGLE_CONFIG, "aacp.ppe_toggle_config", 0x37, "hearing", 1..2, bool, ControlEncoding.BOOLEAN_1_2, Capability.PPE),
        PropertySpec(CommandProperty.DYNAMIC_END_OF_CHARGE, "aacp.dynamic_end_of_charge", 0x3b, "charging", 1..2, bool, ControlEncoding.BOOLEAN_1_2),
        PropertySpec(CommandProperty.STEM_CONFIG, "aacp.stem_config", 0x39, "gestures", 0..15, encoding=ControlEncoding.STEM_MASK, capability=Capability.STEM_CONFIG, internalDependency=true),
        // Hearing (pinned HearingAidScreen.kt, HearingAidAdjustmentsScreen.kt). Internal until a page
        // offers them: 0x2C and 0x33 are only ever written together, by AirPodsRepository.setHearingAid.
        PropertySpec(CommandProperty.HEARING_AID, "aacp.hearing_aid", 0x2c, "hearing", 0..0xFFFF,
            encoding=ControlEncoding.HEARING_AID_PAIR, capability=Capability.HEARING_AID),
        PropertySpec(CommandProperty.HEARING_ASSIST_CONFIG, "aacp.hearing_assist_config", 0x33, "hearing", 1..2, bool,
            ControlEncoding.BOOLEAN_1_2, Capability.HEARING_AID, internalDependency=true),
        PropertySpec(CommandProperty.HPS_GAIN_SWIPE, "aacp.hps_gain_swipe", 0x2f, "hearing", 1..2, bool,
            ControlEncoding.BOOLEAN_1_2, Capability.HEARING_AID),
    )
    fun spec(property: CommandProperty) = controls.firstOrNull { it.property == property }
    fun wire(id: Int) = controls.firstOrNull { it.wireId == id }
}
enum class HeadPacketFormat { STANDARD, ALTERNATE }
data class HeadSample(val orientation1: Int, val orientation2: Int, val orientation3: Int,
    val horizontal: Int, val vertical: Int, val wireVariant: Int)
enum class EarState { BOTH, LEFT, RIGHT, NONE, UNKNOWN }
data class EarSample(val firstRaw: Int, val secondRaw: Int) {
    // Pinned AAP docs: 00 in ear, 01 out of ear, 02 in case. LEFT/RIGHT retain
    // the existing wire-position labels; primary/secondary are not proven physical sides.
    val normalized: EarState get() = when {
        firstRaw !in 0..2 || secondRaw !in 0..2 -> EarState.UNKNOWN
        firstRaw == 0 && secondRaw == 0 -> EarState.BOTH
        firstRaw == 0 -> EarState.LEFT
        secondRaw == 0 -> EarState.RIGHT
        else -> EarState.NONE
    }
}
data class StemSample(val press: Int, val bud: Int)

/** Selection uses effective; observed remains a separate RX fact for automation. */
enum class PropertyStatus { UNKNOWN, CANDIDATE, OBSERVED, PENDING, NOT_CONFIRMED, DEPENDENCY_EXCLUDED, UNAVAILABLE }
data class PropertyView(val spec: PropertySpec, val status: PropertyStatus,
    val observed: dev.lelonio.square.airpods.Received<PropertyValue>?,
    val pending: dev.lelonio.square.airpods.PendingCommand?, val reason: String?,
    val effective: EffectiveProperty? = null, val desired: DesiredProperty? = null)
fun dev.lelonio.square.airpods.AirPodsState.propertyView(property: CommandProperty): PropertyView? {
    val spec = AirPodsProperties.spec(property) ?: return null
    val id = identityReceived?.takeIf { it.generation == generation }?.value
    val observed = propertyObserved(property)
    val request = pending[property]
    val excluded = id != null && !spec.candidate(id)
    val status = when {
        stage != dev.lelonio.square.airpods.AacpStage.SUBSCRIBED -> PropertyStatus.UNAVAILABLE
        id == null || !id.recognized -> PropertyStatus.UNKNOWN
        excluded -> PropertyStatus.DEPENDENCY_EXCLUDED
        request != null -> PropertyStatus.PENDING
        observed != null -> PropertyStatus.OBSERVED
        desired[property]?.takeIf { it.generation == generation }?.status == DesiredStatus.NOT_CONFIRMED ->
            PropertyStatus.NOT_CONFIRMED
        else -> PropertyStatus.CANDIDATE
    }
    return PropertyView(spec,status,observed,request,when(status) {
        PropertyStatus.UNAVAILABLE -> "Control session unavailable; platform/permission reason belongs to owner"
        PropertyStatus.UNKNOWN -> "Current recognized INFORMATION required"
        PropertyStatus.DEPENDENCY_EXCLUDED -> "Absent from pinned model capability candidates"
        PropertyStatus.CANDIDATE -> "Candidate from static model; device verification pending"
        PropertyStatus.NOT_CONFIRMED -> "No matching property RX within 5s; incompatibility not established"
        else -> null
    }, propertyEffective(property), desired[property])
}
