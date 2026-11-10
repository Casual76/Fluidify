// SPDX-License-Identifier: GPL-3.0-or-later
// Reference fixtures from orchestration/wire-reference-vectors.json, pinned LibrePods 89356c08.
package dev.lelonio.square.airpods.protocol
import dev.lelonio.square.airpods.*
import org.junit.Assert.*
import org.junit.Test
class PinnedWireVectorsTest {
    private fun hex(s: String) = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()
    @Test fun all59PinnedTransmitVectors() {
        assertArrayEquals("aacp.listening_mode/off", hex("04 00 04 00 09 00 0d 01 00 00 00"), AacpCodec.property(CommandProperty.MODE, 1))
        assertArrayEquals("aacp.listening_mode/anc", hex("04 00 04 00 09 00 0d 02 00 00 00"), AacpCodec.property(CommandProperty.MODE, 2))
        assertArrayEquals("aacp.listening_mode/transparency", hex("04 00 04 00 09 00 0d 03 00 00 00"), AacpCodec.property(CommandProperty.MODE, 3))
        assertArrayEquals("aacp.listening_mode/adaptive", hex("04 00 04 00 09 00 0d 04 00 00 00"), AacpCodec.property(CommandProperty.MODE, 4))
        assertArrayEquals("aacp.mic_mode/automatic", hex("04 00 04 00 09 00 01 00 00 00 00"), AacpCodec.property(CommandProperty.MIC_MODE, 0))
        assertArrayEquals("aacp.mic_mode/right", hex("04 00 04 00 09 00 01 01 00 00 00"), AacpCodec.property(CommandProperty.MIC_MODE, 1))
        assertArrayEquals("aacp.mic_mode/left", hex("04 00 04 00 09 00 01 02 00 00 00"), AacpCodec.property(CommandProperty.MIC_MODE, 2))
        assertArrayEquals("aacp.double_click_interval/default", hex("04 00 04 00 09 00 17 00 00 00 00"), AacpCodec.property(CommandProperty.DOUBLE_CLICK_INTERVAL, 0))
        assertArrayEquals("aacp.double_click_interval/slower", hex("04 00 04 00 09 00 17 01 00 00 00"), AacpCodec.property(CommandProperty.DOUBLE_CLICK_INTERVAL, 1))
        assertArrayEquals("aacp.double_click_interval/slowest", hex("04 00 04 00 09 00 17 02 00 00 00"), AacpCodec.property(CommandProperty.DOUBLE_CLICK_INTERVAL, 2))
        assertArrayEquals("aacp.click_hold_interval/default", hex("04 00 04 00 09 00 18 00 00 00 00"), AacpCodec.property(CommandProperty.CLICK_HOLD_INTERVAL, 0))
        assertArrayEquals("aacp.click_hold_interval/slower", hex("04 00 04 00 09 00 18 01 00 00 00"), AacpCodec.property(CommandProperty.CLICK_HOLD_INTERVAL, 1))
        assertArrayEquals("aacp.click_hold_interval/slowest", hex("04 00 04 00 09 00 18 02 00 00 00"), AacpCodec.property(CommandProperty.CLICK_HOLD_INTERVAL, 2))
        assertArrayEquals("aacp.listening_mode_configs/anc_and_transparency", hex("04 00 04 00 09 00 1a 06 00 00 00"), AacpCodec.property(CommandProperty.LISTENING_MODE_CONFIGS, 6))
        assertArrayEquals("aacp.listening_mode_configs/all_four", hex("04 00 04 00 09 00 1a 0f 00 00 00"), AacpCodec.property(CommandProperty.LISTENING_MODE_CONFIGS, 15))
        assertArrayEquals("aacp.chime_volume/zero", hex("04 00 04 00 09 00 1f 00 50 00 00"), AacpCodec.property(CommandProperty.CHIME_VOLUME, 0))
        assertArrayEquals("aacp.chime_volume/seventy_five", hex("04 00 04 00 09 00 1f 4b 50 00 00"), AacpCodec.property(CommandProperty.CHIME_VOLUME, 75))
        assertArrayEquals("aacp.chime_volume/hundred", hex("04 00 04 00 09 00 1f 64 50 00 00"), AacpCodec.property(CommandProperty.CHIME_VOLUME, 100))
        assertArrayEquals("aacp.volume_swipe_interval/default", hex("04 00 04 00 09 00 23 01 00 00 00"), AacpCodec.property(CommandProperty.VOLUME_SWIPE_INTERVAL, 1))
        assertArrayEquals("aacp.volume_swipe_interval/longer", hex("04 00 04 00 09 00 23 02 00 00 00"), AacpCodec.property(CommandProperty.VOLUME_SWIPE_INTERVAL, 2))
        assertArrayEquals("aacp.volume_swipe_interval/longest", hex("04 00 04 00 09 00 23 03 00 00 00"), AacpCodec.property(CommandProperty.VOLUME_SWIPE_INTERVAL, 3))
        assertArrayEquals("aacp.call_management_config/flipped", hex("04 00 04 00 09 00 24 00 02 00 00"), AacpCodec.property(CommandProperty.CALL_MANAGEMENT_CONFIG, 2))
        assertArrayEquals("aacp.call_management_config/normal", hex("04 00 04 00 09 00 24 00 03 00 00"), AacpCodec.property(CommandProperty.CALL_MANAGEMENT_CONFIG, 3))
        assertArrayEquals("aacp.auto_anc_strength/ui_zero", hex("04 00 04 00 09 00 2e 64 00 00 00"), AacpCodec.property(CommandProperty.AUTO_ANC_STRENGTH, 0))
        assertArrayEquals("aacp.auto_anc_strength/ui_fifty", hex("04 00 04 00 09 00 2e 32 00 00 00"), AacpCodec.property(CommandProperty.AUTO_ANC_STRENGTH, 50))
        assertArrayEquals("aacp.auto_anc_strength/ui_hundred", hex("04 00 04 00 09 00 2e 00 00 00 00"), AacpCodec.property(CommandProperty.AUTO_ANC_STRENGTH, 100))
        assertArrayEquals("aacp.stem_config/none_customized", hex("04 00 04 00 09 00 39 00 00 00 00"), AacpCodec.property(CommandProperty.STEM_CONFIG, 0))
        assertArrayEquals("aacp.stem_config/only_long_customized", hex("04 00 04 00 09 00 39 08 00 00 00"), AacpCodec.property(CommandProperty.STEM_CONFIG, 8))
        assertArrayEquals("aacp.stem_config/all_customized", hex("04 00 04 00 09 00 39 0f 00 00 00"), AacpCodec.property(CommandProperty.STEM_CONFIG, 15))
        assertArrayEquals("aacp.one_bud_anc_mode/enabled", hex("04 00 04 00 09 00 1b 01 00 00 00"), AacpCodec.property(CommandProperty.ONE_BUD_ANC_MODE, 1))
        assertArrayEquals("aacp.one_bud_anc_mode/disabled", hex("04 00 04 00 09 00 1b 02 00 00 00"), AacpCodec.property(CommandProperty.ONE_BUD_ANC_MODE, 2))
        assertArrayEquals("aacp.volume_swipe_mode/enabled", hex("04 00 04 00 09 00 25 01 00 00 00"), AacpCodec.property(CommandProperty.VOLUME_SWIPE_MODE, 1))
        assertArrayEquals("aacp.volume_swipe_mode/disabled", hex("04 00 04 00 09 00 25 02 00 00 00"), AacpCodec.property(CommandProperty.VOLUME_SWIPE_MODE, 2))
        assertArrayEquals("aacp.adaptive_volume_config/enabled", hex("04 00 04 00 09 00 26 01 00 00 00"), AacpCodec.property(CommandProperty.ADAPTIVE_VOLUME_CONFIG, 1))
        assertArrayEquals("aacp.adaptive_volume_config/disabled", hex("04 00 04 00 09 00 26 02 00 00 00"), AacpCodec.property(CommandProperty.ADAPTIVE_VOLUME_CONFIG, 2))
        assertArrayEquals("aacp.conversation_detect_config/enabled", hex("04 00 04 00 09 00 28 01 00 00 00"), AacpCodec.property(CommandProperty.CONVERSATION, 1))
        assertArrayEquals("aacp.conversation_detect_config/disabled", hex("04 00 04 00 09 00 28 02 00 00 00"), AacpCodec.property(CommandProperty.CONVERSATION, 2))
        assertArrayEquals("aacp.allow_off_option/enabled", hex("04 00 04 00 09 00 34 01 00 00 00"), AacpCodec.property(CommandProperty.ALLOW_OFF_OPTION, 1))
        assertArrayEquals("aacp.allow_off_option/disabled", hex("04 00 04 00 09 00 34 02 00 00 00"), AacpCodec.property(CommandProperty.ALLOW_OFF_OPTION, 2))
        assertArrayEquals("aacp.sleep_detection_config/enabled", hex("04 00 04 00 09 00 35 01 00 00 00"), AacpCodec.property(CommandProperty.SLEEP_DETECTION_CONFIG, 1))
        assertArrayEquals("aacp.sleep_detection_config/disabled", hex("04 00 04 00 09 00 35 02 00 00 00"), AacpCodec.property(CommandProperty.SLEEP_DETECTION_CONFIG, 2))
        assertArrayEquals("aacp.ear_detection_config/enabled", hex("04 00 04 00 09 00 0a 01 00 00 00"), AacpCodec.property(CommandProperty.EAR_DETECTION_CONFIG, 1))
        assertArrayEquals("aacp.ear_detection_config/disabled", hex("04 00 04 00 09 00 0a 02 00 00 00"), AacpCodec.property(CommandProperty.EAR_DETECTION_CONFIG, 2))
        assertArrayEquals("aacp.automatic_connection_config/enabled", hex("04 00 04 00 09 00 20 01 00 00 00"), AacpCodec.property(CommandProperty.AUTOMATIC_CONNECTION_CONFIG, 1))
        assertArrayEquals("aacp.automatic_connection_config/disabled", hex("04 00 04 00 09 00 20 02 00 00 00"), AacpCodec.property(CommandProperty.AUTOMATIC_CONNECTION_CONFIG, 2))
        assertArrayEquals("aacp.ppe_toggle_config/enabled", hex("04 00 04 00 09 00 37 01 00 00 00"), AacpCodec.property(CommandProperty.PPE_TOGGLE_CONFIG, 1))
        assertArrayEquals("aacp.ppe_toggle_config/disabled", hex("04 00 04 00 09 00 37 02 00 00 00"), AacpCodec.property(CommandProperty.PPE_TOGGLE_CONFIG, 2))
        assertArrayEquals("aacp.dynamic_end_of_charge/enabled", hex("04 00 04 00 09 00 3b 01 00 00 00"), AacpCodec.property(CommandProperty.DYNAMIC_END_OF_CHARGE, 1))
        assertArrayEquals("aacp.dynamic_end_of_charge/disabled", hex("04 00 04 00 09 00 3b 02 00 00 00"), AacpCodec.property(CommandProperty.DYNAMIC_END_OF_CHARGE, 2))
        assertArrayEquals("device.rename/AirPods", hex("04 00 04 00 1a 00 01 07 00 41 69 72 50 6f 64 73"), AacpCodec.rename("AirPods"))
        assertArrayEquals("device.rename/Cuffie e", hex("04 00 04 00 1a 00 01 08 00 43 75 66 66 69 65 20 65"), AacpCodec.rename("Cuffie e"))
        assertArrayEquals("device.rename/Cuffie \u00e8", hex("04 00 04 00 1a 00 01 09 00 43 75 66 66 69 65 20 c3 a8"), AacpCodec.rename("Cuffie \u00e8"))
        assertArrayEquals("device.eq/disabled_flat", hex("04 00 04 00 63 00 05 00 01 01 32 32 32"), AacpCodec.eq(PropertyValue.Eq(false, 50, 50, 50)))
        assertArrayEquals("device.eq/enabled_flat", hex("04 00 04 00 63 00 05 00 01 02 32 32 32"), AacpCodec.eq(PropertyValue.Eq(true, 50, 50, 50)))
        assertArrayEquals("device.eq/enabled_extremes", hex("04 00 04 00 63 00 05 00 01 02 00 32 64"), AacpCodec.eq(PropertyValue.Eq(true, 0, 50, 100)))
        assertArrayEquals("host.head_gestures/standard_start", hex("04 00 04 00 17 00 00 00 10 00 10 00 08 a1 02 42 0b 08 0e 10 02 1a 05 01 40 9c 00 00"), AacpCodec.headTracking(HeadPacketFormat.STANDARD, true))
        assertArrayEquals("host.head_gestures/standard_stop", hex("04 00 04 00 17 00 00 00 10 00 11 00 08 7e 10 02 42 0b 08 4e 10 02 1a 05 01 00 00 00 00"), AacpCodec.headTracking(HeadPacketFormat.STANDARD, false))
        assertArrayEquals("host.head_gestures/alternate_start", hex("04 00 04 00 17 00 00 00 10 00 0f 00 08 73 42 0b 08 10 10 02 1a 05 01 40 9c 00 00"), AacpCodec.headTracking(HeadPacketFormat.ALTERNATE, true))
        assertArrayEquals("host.head_gestures/alternate_stop", hex("04 00 04 00 17 00 00 00 10 00 0f 00 08 75 42 0b 08 10 10 02 1a 05 01 00 00 00 00"), AacpCodec.headTracking(HeadPacketFormat.ALTERNATE, false))
    }
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected invalid wire input to be rejected") } catch (_: IllegalArgumentException) { }
    }
    @Test fun eightNegativeReferenceRequirementsAndByteOverflow() {
        rejects { AacpCodec.controlPayload(1, listOf(1,2,3,4,5)) }
        rejects { AacpCodec.controlPayload(1, emptyList()) }
        rejects { AacpCodec.property(CommandProperty.AUTO_ANC_STRENGTH, -1) }
        rejects { AacpCodec.property(CommandProperty.AUTO_ANC_STRENGTH, 101) }
        rejects { PropertyValue.Eq(true,-1,50,50) }
        rejects { PropertyValue.Eq(true,50,50,101) }
        rejects { AacpCodec.rename("a".repeat(256)) }
        rejects { AacpCodec.property(CommandProperty.LISTENING_MODE_CONFIGS,2) }
        rejects { AacpCodec.controlPayload(1,listOf(256)) }
        rejects { AacpCodec.controlPayload(1,listOf(-1)) }
        rejects { AacpCodec.rename("a\u0000b") }
        assertEquals(255, AacpCodec.rename("a".repeat(255))[7].toInt() and 255)
    }
}
