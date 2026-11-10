// SPDX-License-Identifier: GPL-3.0-or-later
// Derived from LibrePods contributors (2025), data/AirPods.kt at 89356c08cea3db0b6b54792d9329d09a87cc85fa.
package dev.lelonio.square.airpods.protocol

enum class Capability { LISTENING_MODE, CONVERSATION_AWARENESS, STEM_CONFIG, HEAD_GESTURES,
    LOUD_SOUND_REDUCTION, PPE, SLEEP_DETECTION, HEARING_AID, ADAPTIVE_AUDIO, ADAPTIVE_VOLUME, SWIPE_FOR_VOLUME, HRM }
object AirPodsModels {
    data class Model(val name: String, val numbers: Set<String>, val capabilities: Set<Capability>)
    val models = listOf(
        Model("AirPods 1", setOf("A1523", "A1722"), setOf()),
        Model("AirPods 2", setOf("A2032", "A2031"), setOf()),
        Model("AirPods 3", setOf("A2565", "A2564"), setOf(Capability.HEAD_GESTURES)),
        Model("AirPods 4", setOf("A3053", "A3050", "A3054"), setOf(Capability.HEAD_GESTURES, Capability.SLEEP_DETECTION, Capability.ADAPTIVE_VOLUME)),
        Model("AirPods 4 (ANC)", setOf("A3056", "A3055", "A3057"), setOf(Capability.LISTENING_MODE, Capability.CONVERSATION_AWARENESS, Capability.HEAD_GESTURES, Capability.ADAPTIVE_AUDIO, Capability.SLEEP_DETECTION, Capability.ADAPTIVE_VOLUME, Capability.STEM_CONFIG)),
        Model("AirPods 5", setOf("A3531", "A3532", "A3533"), setOf(Capability.LISTENING_MODE, Capability.CONVERSATION_AWARENESS, Capability.HEAD_GESTURES, Capability.ADAPTIVE_AUDIO, Capability.SLEEP_DETECTION, Capability.ADAPTIVE_VOLUME, Capability.STEM_CONFIG)),
        Model("AirPods 5 with Wireless Charging Case", setOf("A3439", "A3440", "A3441"), setOf(Capability.LISTENING_MODE, Capability.CONVERSATION_AWARENESS, Capability.HEAD_GESTURES, Capability.ADAPTIVE_AUDIO, Capability.SLEEP_DETECTION, Capability.ADAPTIVE_VOLUME, Capability.STEM_CONFIG, Capability.SWIPE_FOR_VOLUME)),
        Model("AirPods Pro 1", setOf("A2084", "A2083"), setOf(Capability.LISTENING_MODE)),
        Model("AirPods Pro 2 with Magsafe Charging Case (Lightning)", setOf("A2931", "A2699", "A2698"), setOf(Capability.LISTENING_MODE, Capability.CONVERSATION_AWARENESS, Capability.STEM_CONFIG, Capability.LOUD_SOUND_REDUCTION, Capability.SLEEP_DETECTION, Capability.HEARING_AID, Capability.ADAPTIVE_AUDIO, Capability.ADAPTIVE_VOLUME, Capability.SWIPE_FOR_VOLUME, Capability.HEAD_GESTURES)),
        Model("AirPods Pro 2 with Magsafe Charging Case (USB-C)", setOf("A3047", "A3048", "A3049"), setOf(Capability.LISTENING_MODE, Capability.CONVERSATION_AWARENESS, Capability.STEM_CONFIG, Capability.LOUD_SOUND_REDUCTION, Capability.SLEEP_DETECTION, Capability.HEARING_AID, Capability.ADAPTIVE_AUDIO, Capability.ADAPTIVE_VOLUME, Capability.SWIPE_FOR_VOLUME, Capability.HEAD_GESTURES)),
        Model("AirPods Pro 3", setOf("A3063", "A3064", "A3065"), setOf(Capability.LISTENING_MODE, Capability.CONVERSATION_AWARENESS, Capability.HEAD_GESTURES, Capability.STEM_CONFIG, Capability.LOUD_SOUND_REDUCTION, Capability.PPE, Capability.SLEEP_DETECTION, Capability.HEARING_AID, Capability.ADAPTIVE_AUDIO, Capability.ADAPTIVE_VOLUME, Capability.SWIPE_FOR_VOLUME, Capability.HRM))
    )
    fun find(number: String): Model? = models.firstOrNull { number in it.numbers }
}
