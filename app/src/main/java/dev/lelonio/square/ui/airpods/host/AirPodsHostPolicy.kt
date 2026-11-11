// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.ui.airpods.host

import dev.lelonio.square.airpods.runtime.storage.*
import dev.lelonio.square.ui.airpods.*
import java.util.UUID

/** Never put Bluetooth addresses into view IDs, saved state or diagnostics. */
internal class CandidateIds {
    private val ids = linkedMapOf<PairAddress, String>()
    fun id(address: PairAddress): String = ids.getOrPut(address) { UUID.randomUUID().toString() }
    fun resolve(id: String): PairAddress? = ids.entries.firstOrNull { it.value == id }?.key
    fun retain(addresses: Set<PairAddress>) { ids.keys.retainAll(addresses) }
}

internal data class HostStamp(val pair: PairAddress?, val consent: Long, val repository: Long) {
    fun matches(data: AirPodsRuntimeData, generation: Long) =
        pair == data.selectedPair && consent == data.start.consentGeneration && repository == generation
    companion object {
        fun capture(data: AirPodsRuntimeData, generation: Long) = HostStamp(data.selectedPair, data.start.consentGeneration, generation)
    }
}

internal fun headAction(action: GestureAction): HeadAction? = when(action) {
    GestureAction.NONE -> HeadAction.NONE
    GestureAction.PLAY_PAUSE -> HeadAction.TOGGLE_PLAYBACK
    GestureAction.NEXT -> HeadAction.NEXT
    GestureAction.PREVIOUS -> HeadAction.PREVIOUS
    else -> null
}
internal fun longAction(action: GestureAction): LongPressAction? = when(action.name) {
    "NOISE_CONTROL" -> LongPressAction.CYCLE_NOISE_CONTROL_MODES
    "DIGITAL_ASSISTANT" -> LongPressAction.DIGITAL_ASSISTANT
    else -> null
}
internal fun gestureName(action: LongPressAction) = when(action) {
    LongPressAction.CYCLE_NOISE_CONTROL_MODES -> "NOISE_CONTROL"
    LongPressAction.DIGITAL_ASSISTANT -> "DIGITAL_ASSISTANT"
}
internal fun gestureName(action: HeadAction) = when(action) {
    HeadAction.NONE -> "NONE"
    HeadAction.TOGGLE_PLAYBACK -> "PLAY_PAUSE"
    HeadAction.NEXT -> "NEXT"
    HeadAction.PREVIOUS -> "PREVIOUS"
}

internal fun toggleSettings(pair: PairSettings, toggle: LocalToggle, value: Boolean): PairSettings {
    if (toggle == LocalToggle.ALTERNATE_PACKETS) return pair.copy(headPacketAlternate = value)
    val h = pair.host
    return pair.copy(host = when(toggle) {
        LocalToggle.AUTOMATIONS -> h.copy(automationsEnabled = value)
        LocalToggle.OTHER_APPS -> h.copy(otherAppsEnabled = value)
        LocalToggle.BACKGROUND -> h.copy(fluidifyBackgroundEnabled = value)
        LocalToggle.EAR_PAUSE -> h.copy(earPause = value)
        LocalToggle.EAR_RESUME -> h.copy(earResume = value)
        LocalToggle.CA_DUCK -> h.copy(conversationDuck = value)
        LocalToggle.CA_PAUSE -> h.copy(conversationPause = value)
        LocalToggle.CA_RELATIVE -> h.copy(conversationRelative = value)
        LocalToggle.HEAD_GESTURES -> h.copy(headGestures = value)
        LocalToggle.DISCONNECT_UNWORN -> h.copy(disconnectUnworn = value)
        LocalToggle.ALTERNATE_PACKETS -> h
    })
}

internal fun actionKey(action: AirPodsUiAction): String? = when(action) {
    is AirPodsUiAction.Write -> null
    is AirPodsUiAction.SelectPair -> "host.select"
    is AirPodsUiAction.Setup -> "setup.${action.action}"
    is AirPodsUiAction.Toggle -> "local.${action.toggle}"
    is AirPodsUiAction.ConversationVolume -> "host.ca_volume"
    is AirPodsUiAction.LocalVolume -> "host.volume"
    is AirPodsUiAction.PreferredMode -> "local.preferredMode"
    is AirPodsUiAction.AssignGesture -> "gesture.${action.side}"
    is AirPodsUiAction.Tracking -> if(action.enabled) "tracking.start" else "tracking.stop"
}
