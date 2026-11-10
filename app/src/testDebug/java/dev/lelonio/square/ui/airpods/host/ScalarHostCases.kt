// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.ui.airpods.host

import dev.lelonio.square.airpods.CommandProperty
import dev.lelonio.square.airpods.protocol.PropertyValue

internal enum class ScalarEditorKind { OPTIONS, NUMBER, MASK }

internal data class ScalarInput(val value: Int, val hex: String, val label: String = "") {
    val number get() = PropertyValue.Number(value)
}

internal data class ScalarHostCase(
    val property: CommandProperty,
    val catalogId: String,
    val kind: ScalarEditorKind,
    val range: IntRange,
    // Explicit expectation for recognized A1523, not computed from the production capability table.
    val needsCapability: Boolean,
    val inputs: List<ScalarInput>,
) {
    val initial get() = inputs.first()
    val requested get() = inputs.last()
    val different get() = inputs.dropLast(1).last()
    override fun toString() = catalogId
}

/** Independent literals from wire-reference-vectors.json / PinnedWireVectorsTest, upstream
 * 89356c08cea3db0b6b54792d9329d09a87cc85fa. All literals are checked against the frozen JSON
 * by the delivery static audit. No encoder or PropertySpec.encode constructs expected bytes.
 * RX reuses these source-derived control layouts as SYNTHETIC input, never as a causal ACK.
 * This is a host/UI integration matrix, not a second codec-only vector suite. */
internal object ScalarHostCases {
    private fun input(value: Int, hex: String, label: String = "") = ScalarInput(value, hex, label)
    val all = listOf(
        ScalarHostCase(CommandProperty.MIC_MODE, "aacp.mic_mode", ScalarEditorKind.OPTIONS, 0..2, false, listOf(
            input(0, "04 00 04 00 09 00 01 00 00 00 00", "automatic"),
            input(1, "04 00 04 00 09 00 01 01 00 00 00", "right"),
            input(2, "04 00 04 00 09 00 01 02 00 00 00", "left"))),
        ScalarHostCase(CommandProperty.DOUBLE_CLICK_INTERVAL, "aacp.double_click_interval", ScalarEditorKind.OPTIONS, 0..2, false, listOf(
            input(0, "04 00 04 00 09 00 17 00 00 00 00", "default"),
            input(1, "04 00 04 00 09 00 17 01 00 00 00", "slower"),
            input(2, "04 00 04 00 09 00 17 02 00 00 00", "slowest"))),
        ScalarHostCase(CommandProperty.CLICK_HOLD_INTERVAL, "aacp.click_hold_interval", ScalarEditorKind.OPTIONS, 0..2, false, listOf(
            input(0, "04 00 04 00 09 00 18 00 00 00 00", "default"),
            input(1, "04 00 04 00 09 00 18 01 00 00 00", "slower"),
            input(2, "04 00 04 00 09 00 18 02 00 00 00", "slowest"))),
        ScalarHostCase(CommandProperty.LISTENING_MODE_CONFIGS, "aacp.listening_mode_configs", ScalarEditorKind.MASK, 1..15, true, listOf(
            input(6, "04 00 04 00 09 00 1a 06 00 00 00"),
            input(15, "04 00 04 00 09 00 1a 0f 00 00 00"))),
        ScalarHostCase(CommandProperty.ONE_BUD_ANC_MODE, "aacp.one_bud_anc_mode", ScalarEditorKind.OPTIONS, 1..2, true, listOf(
            input(2, "04 00 04 00 09 00 1b 02 00 00 00", "off"),
            input(1, "04 00 04 00 09 00 1b 01 00 00 00", "on"))),
        ScalarHostCase(CommandProperty.MODE, "aacp.listening_mode", ScalarEditorKind.OPTIONS, 1..4, true, listOf(
            input(1, "04 00 04 00 09 00 0d 01 00 00 00", "off"),
            input(2, "04 00 04 00 09 00 0d 02 00 00 00", "anc"),
            input(3, "04 00 04 00 09 00 0d 03 00 00 00", "transparency"),
            input(4, "04 00 04 00 09 00 0d 04 00 00 00", "adaptive"))),
        ScalarHostCase(CommandProperty.CHIME_VOLUME, "aacp.chime_volume", ScalarEditorKind.NUMBER, 0..100, false, listOf(
            input(0, "04 00 04 00 09 00 1f 00 50 00 00"),
            input(75, "04 00 04 00 09 00 1f 4b 50 00 00"),
            input(100, "04 00 04 00 09 00 1f 64 50 00 00"))),
        ScalarHostCase(CommandProperty.VOLUME_SWIPE_INTERVAL, "aacp.volume_swipe_interval", ScalarEditorKind.OPTIONS, 1..3, true, listOf(
            input(1, "04 00 04 00 09 00 23 01 00 00 00", "default"),
            input(2, "04 00 04 00 09 00 23 02 00 00 00", "longer"),
            input(3, "04 00 04 00 09 00 23 03 00 00 00", "longest"))),
        ScalarHostCase(CommandProperty.CALL_MANAGEMENT_CONFIG, "aacp.call_management_config", ScalarEditorKind.OPTIONS, 2..3, false, listOf(
            input(3, "04 00 04 00 09 00 24 00 03 00 00", "normal"),
            input(2, "04 00 04 00 09 00 24 00 02 00 00", "flipped"))),
        ScalarHostCase(CommandProperty.VOLUME_SWIPE_MODE, "aacp.volume_swipe_mode", ScalarEditorKind.OPTIONS, 1..2, true, listOf(
            input(2, "04 00 04 00 09 00 25 02 00 00 00", "off"),
            input(1, "04 00 04 00 09 00 25 01 00 00 00", "on"))),
        ScalarHostCase(CommandProperty.ADAPTIVE_VOLUME_CONFIG, "aacp.adaptive_volume_config", ScalarEditorKind.OPTIONS, 1..2, true, listOf(
            input(2, "04 00 04 00 09 00 26 02 00 00 00", "off"),
            input(1, "04 00 04 00 09 00 26 01 00 00 00", "on"))),
        ScalarHostCase(CommandProperty.CONVERSATION, "aacp.conversation_detect_config", ScalarEditorKind.OPTIONS, 1..2, true, listOf(
            input(2, "04 00 04 00 09 00 28 02 00 00 00", "off"),
            input(1, "04 00 04 00 09 00 28 01 00 00 00", "on"))),
        ScalarHostCase(CommandProperty.AUTO_ANC_STRENGTH, "aacp.auto_anc_strength", ScalarEditorKind.NUMBER, 0..100, true, listOf(
            input(0, "04 00 04 00 09 00 2e 64 00 00 00"),
            input(50, "04 00 04 00 09 00 2e 32 00 00 00"),
            input(100, "04 00 04 00 09 00 2e 00 00 00 00"))),
        ScalarHostCase(CommandProperty.ALLOW_OFF_OPTION, "aacp.allow_off_option", ScalarEditorKind.OPTIONS, 1..2, true, listOf(
            input(2, "04 00 04 00 09 00 34 02 00 00 00", "off"),
            input(1, "04 00 04 00 09 00 34 01 00 00 00", "on"))),
        ScalarHostCase(CommandProperty.SLEEP_DETECTION_CONFIG, "aacp.sleep_detection_config", ScalarEditorKind.OPTIONS, 1..2, true, listOf(
            input(2, "04 00 04 00 09 00 35 02 00 00 00", "off"),
            input(1, "04 00 04 00 09 00 35 01 00 00 00", "on"))),
        ScalarHostCase(CommandProperty.EAR_DETECTION_CONFIG, "aacp.ear_detection_config", ScalarEditorKind.OPTIONS, 1..2, false, listOf(
            input(2, "04 00 04 00 09 00 0a 02 00 00 00", "off"),
            input(1, "04 00 04 00 09 00 0a 01 00 00 00", "on"))),
        ScalarHostCase(CommandProperty.AUTOMATIC_CONNECTION_CONFIG, "aacp.automatic_connection_config", ScalarEditorKind.OPTIONS, 1..2, false, listOf(
            input(2, "04 00 04 00 09 00 20 02 00 00 00", "off"),
            input(1, "04 00 04 00 09 00 20 01 00 00 00", "on"))),
        ScalarHostCase(CommandProperty.PPE_TOGGLE_CONFIG, "aacp.ppe_toggle_config", ScalarEditorKind.OPTIONS, 1..2, true, listOf(
            input(2, "04 00 04 00 09 00 37 02 00 00 00", "off"),
            input(1, "04 00 04 00 09 00 37 01 00 00 00", "on"))),
        ScalarHostCase(CommandProperty.DYNAMIC_END_OF_CHARGE, "aacp.dynamic_end_of_charge", ScalarEditorKind.OPTIONS, 1..2, false, listOf(
            input(2, "04 00 04 00 09 00 3b 02 00 00 00", "off"),
            input(1, "04 00 04 00 09 00 3b 01 00 00 00", "on"))),
    )
    fun get(property: CommandProperty) = all.single { it.property == property }

    // Complete positional INFORMATION packets, synthetic model substitutions in the existing
    // fixture's independent layout. No identity, state, admission or capability is injected.
    const val INFORMATION_A1523 = "04 00 04 00 1d 00 01 00 78 00 " +
        "50 6f 64 73 00 41 31 35 32 33 00 41 70 70 6c 65 20 49 6e 63 2e 00 " +
        "53 00 31 00 32 00 48 00 55 00 4c 00 52 00 33 00"
    const val INFORMATION_A2084 = "04 00 04 00 1d 00 01 00 78 00 " +
        "50 6f 64 73 00 41 32 30 38 34 00 41 70 70 6c 65 20 49 6e 63 2e 00 " +
        "53 00 31 00 32 00 48 00 55 00 4c 00 52 00 33 00"
    const val INFORMATION_UNKNOWN = "04 00 04 00 1d 00 01 00 78 00 " +
        "50 6f 64 73 00 41 39 39 39 39 00 41 70 70 6c 65 20 49 6e 63 2e 00 " +
        "53 00 31 00 32 00 48 00 55 00 4c 00 52 00 33 00"
    const val CONVERSATION_ACTIVITY_START = "04 00 04 00 4b 00 02 00 00 01"
}

internal fun HostProtocolFixture.receiveModel(hex: String, model: String) {
    transport.deliver(hex)
    await("decoded INFORMATION $model") { owner.state.value.identityReceived?.value?.modelNumber == model }
    refresh()
}

/** Unlike value equality alone, this fence proves a new event was consumed even if the
 * incoming value equals bootstrap RX. Qualification observes it inside the repository
 * receive lock, so a following write cannot overtake that frame's state publication. */
internal fun HostProtocolFixture.receiveScalar(hex: String, property: CommandProperty, value: PropertyValue) {
    fun matchingEvents() = events.count { event ->
        event.step == dev.lelonio.square.airpods.QualificationStep.RX && when (property) {
            CommandProperty.MODE -> event.flags == 3
            CommandProperty.CONVERSATION -> event.flags == 4
            else -> event.property == property
        }
    }
    val previousEvents = matchingEvents()
    transport.deliver(hex)
    await("new scalar RX $property") {
        matchingEvents() > previousEvents && observed(property) == value && property !in owner.state.value.pending
    }
    refresh()
}
