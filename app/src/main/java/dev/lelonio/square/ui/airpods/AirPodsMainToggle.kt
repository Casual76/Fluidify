// SPDX-License-Identifier: GPL-3.0-or-later
// Ear-detection/device preference coupling follows LibrePods' AirPodsViewModel (2025),
// pinned 89356c08cea3db0b6b54792d9329d09a87cc85fa. Local authority remains Fluidify's.
package dev.lelonio.square.ui.airpods

import dev.lelonio.square.airpods.CommandProperty
import dev.lelonio.square.airpods.protocol.PropertyValue
import dev.lelonio.square.airpods.runtime.storage.PairSettings

enum class AirPodsMainToggleFailure { UNAVAILABLE, LOCAL_SAVE_FAILED, DEVICE_NOT_SENT }

data class AirPodsMainToggleIssue(
    val property: CommandProperty,
    val failure: AirPodsMainToggleFailure,
)

internal val coordinatedMainProperties = setOf(CommandProperty.EAR_DETECTION_CONFIG, CommandProperty.CONVERSATION)

/** One atomic local update, preserving the master gate and conversation policy.
 * Background, other-app access, disconnect policy and gesture preferences are never opted in.
 */
internal fun mainToggleSettings(pair: PairSettings, property: CommandProperty, enabled: Boolean): PairSettings {
    val host = pair.host
    return pair.copy(host = when (property) {
        CommandProperty.EAR_DETECTION_CONFIG -> host.copy(
            earPause = enabled, earResume = enabled,
        )
        CommandProperty.CONVERSATION -> host.copy(
            // Keep a configured pause-only/duck+pause policy; otherwise default to attenuation.
            conversationDuck = host.conversationDuck || (enabled && !host.conversationPause),
            conversationPause = host.conversationPause,
        )
        else -> error("Not a coordinated main toggle")
    })
}

internal fun AirPodsUiState.mainToggleAllowed(property: CommandProperty, enabled: Boolean): Boolean {
    if (!writeAllowed(property, PropertyValue.Number(if (enabled) 1 else 2))) return false
    if (property !in coordinatedMainProperties) return true
    val toggles = if (property == CommandProperty.EAR_DETECTION_CONFIG)
        listOf(LocalToggle.EAR_PAUSE, LocalToggle.EAR_RESUME)
    else listOf(LocalToggle.CA_DUCK, LocalToggle.CA_PAUSE)
    return toggles.all { allowed("local.$it.${if (enabled) "enable" else "disable"}") }
}

/** Save first: a storage failure must never dispatch a device-only half operation.
 * If device admission fails after saving, keep the explicit local intent and report the partial
 * result. No compensating write, retry or fabricated RX: the runtime still requires observations.
 * A true return from send means admitted to the repository, never confirmed by the AirPods.
 */
internal suspend fun coordinateMainToggle(
    property: CommandProperty,
    enabled: Boolean,
    saveLocal: suspend ((PairSettings) -> PairSettings) -> Boolean,
    send: (PropertyValue.Number) -> Boolean,
): AirPodsMainToggleFailure? {
    require(property in coordinatedMainProperties)
    if (!saveLocal { mainToggleSettings(it, property, enabled) }) return AirPodsMainToggleFailure.LOCAL_SAVE_FAILED
    return if (send(PropertyValue.Number(if (enabled) 1 else 2))) null else AirPodsMainToggleFailure.DEVICE_NOT_SENT
}
