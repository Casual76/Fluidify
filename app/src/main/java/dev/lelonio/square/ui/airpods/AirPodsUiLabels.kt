package dev.lelonio.square.ui.airpods
import dev.lelonio.square.R
internal val optionLabels = mapOf(
    "on" to R.string.airpods_ui_on,
    "off" to R.string.airpods_ui_off,
    "automatic" to R.string.airpods_ui_automatic,
    "left" to R.string.airpods_ui_left,
    "right" to R.string.airpods_ui_right,
    "default" to R.string.airpods_ui_default,
    "slower" to R.string.airpods_ui_slower,
    "slowest" to R.string.airpods_ui_slowest,
    "longer" to R.string.airpods_ui_longer,
    "longest" to R.string.airpods_ui_longest,
    "flipped" to R.string.airpods_ui_flipped,
    "normal" to R.string.airpods_ui_normal,
)
internal val toggleLabels = mapOf(
    LocalToggle.AUTOMATIONS to R.string.airpods_ui_automations,
    LocalToggle.OTHER_APPS to R.string.airpods_ui_other_apps,
    LocalToggle.BACKGROUND to R.string.airpods_ui_background,
    LocalToggle.EAR_PAUSE to R.string.airpods_ui_ear_pause,
    LocalToggle.EAR_RESUME to R.string.airpods_ui_ear_resume,
    LocalToggle.CA_DUCK to R.string.airpods_ui_ca_duck,
    LocalToggle.CA_PAUSE to R.string.airpods_ui_ca_pause,
    LocalToggle.CA_RELATIVE to R.string.airpods_ui_ca_relative,
    LocalToggle.HEAD_GESTURES to R.string.airpods_ui_head_gestures,
    LocalToggle.ALTERNATE_PACKETS to R.string.airpods_ui_alternate_packets,
    LocalToggle.DISCONNECT_UNWORN to R.string.airpods_ui_disconnect_unworn,
)

internal val gestureLabels = mapOf(
    GestureAction.NONE to R.string.airpods_ui_none,
    GestureAction.PLAY_PAUSE to R.string.airpods_ui_play_pause,
    GestureAction.NEXT to R.string.airpods_ui_next,
    GestureAction.PREVIOUS to R.string.airpods_ui_previous,
    GestureAction.NOISE_CONTROL to R.string.airpods_ui_noise_control,
    GestureAction.DIGITAL_ASSISTANT to R.string.airpods_ui_digital_assistant,
)
