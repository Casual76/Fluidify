// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lelonio.square.ui.airpods



import dev.lelonio.square.airpods.*

import dev.lelonio.square.airpods.protocol.*

import dev.lelonio.square.airpods.runtime.storage.*



/** Host must supply current facts, never infer setup from a Bluetooth display name. */

data class AirPodsUiState(

    val domain: AirPodsState = AirPodsState(),

    val pair: PairSettings = PairSettings(),

    val enabled: Boolean = false,

    val suppressed: Boolean = false,

    val profileConnected: Boolean = false,

    val bluetoothGranted: Boolean = false,

    val productGatePassed: Boolean = false,

    val conformanceMatches: Boolean = false,

    val nowMs: Long = 0,

    val candidates: List<PairCandidate> = emptyList(),

    val selectedCandidateId: String? = null,

    val availability: Map<String, UiAvailability> = emptyMap(),

    val localVolumePercent: Int? = null,

    val headHistory: List<HeadSample> = emptyList(),

    val gestureAssignments: Map<GestureSide, GestureAction> = emptyMap(),

    /** Sanitized by owner; do not supply addresses, serials, raw packets or notification text. */

    val diagnostics: List<String> = emptyList(),

    val pendingCleanupCount: Int = 0,

    val functionalAuthority: dev.lelonio.square.airpods.runtime.android.AirPodsFunctionalUiAuthority? = null,

) {

    val verified get() = pair.verification != null && conformanceMatches

    val testAuthorized get() = functionalAuthority?.permits(domain.generation) == true

    val surfaceAuthorized get() = (verified && productGatePassed) || testAuthorized

    val homeVisible get() = surfaceAuthorized && enabled && !suppressed && profileConnected && bluetoothGranted

    val playerVisible get() = surfaceAuthorized && enabled && !suppressed && bluetoothGranted

    val controlsReady get() = homeVisible && domain.stage == AacpStage.SUBSCRIBED

    val identity get() = domain.identityReceived?.takeIf { it.generation == domain.generation }?.value

    val modes get() = identity?.modes.orEmpty().filter { mode ->

        mode != ListeningMode.OFF || (domain.propertyObserved(CommandProperty.ALLOW_OFF_OPTION)?.value as? PropertyValue.Number)?.value != 2

    }

    val preferredMode get() = when(pair.secondMode) {

        QuickMode.OFF -> ListeningMode.OFF

        QuickMode.ADAPTIVE -> ListeningMode.ADAPTIVE

        QuickMode.TRANSPARENCY -> ListeningMode.TRANSPARENCY

        else -> if(ListeningMode.ADAPTIVE in modes) ListeningMode.ADAPTIVE else ListeningMode.TRANSPARENCY

    }.takeIf { it in modes }

    val currentMode get() = domain.mode?.takeIf { controlsReady && it.generation == domain.generation }?.value

    val quickTarget get() = (if(currentMode == ListeningMode.ANC) preferredMode else ListeningMode.ANC)

        ?.takeIf { controlsReady && it in modes && CommandProperty.MODE !in domain.pending }

    fun allowed(id: String) = availability[id]?.enabled == true

    fun writeAllowed(property: CommandProperty, value: PropertyValue) = controlsReady &&

        domain.canAttemptWrite(property, value)

}

data class PairCandidate(val id: String, val displayName: String, val identityHint: String)

data class UiAvailability(val enabled: Boolean, val reason: String? = null)

data class AirPodsUiBinding(val state: AirPodsUiState, val dispatch: (AirPodsUiAction) -> Unit, val openQuick: () -> Unit,

    val openQuickFrom: ((androidx.compose.ui.geometry.Rect) -> Unit)? = null)

enum class LocalToggle { AUTOMATIONS, OTHER_APPS, BACKGROUND, EAR_PAUSE, EAR_RESUME, CA_DUCK, CA_PAUSE, CA_RELATIVE, HEAD_GESTURES, ALTERNATE_PACKETS, DISCONNECT_UNWORN }

internal fun PairSettings.localToggleValue(toggle: LocalToggle): Boolean = when (toggle) {
    LocalToggle.AUTOMATIONS -> host.automationsEnabled
    LocalToggle.OTHER_APPS -> host.otherAppsEnabled
    LocalToggle.BACKGROUND -> host.fluidifyBackgroundEnabled
    LocalToggle.EAR_PAUSE -> host.earPause
    LocalToggle.EAR_RESUME -> host.earResume
    LocalToggle.CA_DUCK -> host.conversationDuck
    LocalToggle.CA_PAUSE -> host.conversationPause
    LocalToggle.CA_RELATIVE -> host.conversationRelative
    LocalToggle.HEAD_GESTURES -> host.headGestures
    LocalToggle.ALTERNATE_PACKETS -> headPacketAlternate
    LocalToggle.DISCONNECT_UNWORN -> host.disconnectUnworn
}

enum class GestureSide { LEFT, RIGHT, NOD, SHAKE }

enum class GestureAction { NONE, PLAY_PAUSE, NEXT, PREVIOUS, NOISE_CONTROL, DIGITAL_ASSISTANT }

enum class SetupAction { CALL_PERMISSION, BLUETOOTH_PERMISSION, NOTIFICATION_PERMISSION, MEDIA_ACCESS, COMPANION_ASSOCIATION, VERIFY_SELECTED, ENABLE, DISABLE, STOP, RESUME, FORGET, RETRY_CLEANUP, CONNECT, DISCONNECT, BLUETOOTH_SETTINGS, TILE_INSTRUCTIONS, INFORMATION, EXPORT_DIAGNOSTICS, DELETE_DIAGNOSTICS, START_DIAGNOSTICS, STOP_DIAGNOSTICS }

sealed interface AirPodsUiAction {

    data class Write(val generation: Long, val property: CommandProperty, val value: PropertyValue): AirPodsUiAction

    data class SelectPair(val candidateId: String): AirPodsUiAction

    data class Setup(val action: SetupAction): AirPodsUiAction

    data class Toggle(val toggle: LocalToggle, val enabled: Boolean): AirPodsUiAction

    data class ConversationVolume(val percent: Int): AirPodsUiAction

    data class LocalVolume(val percent: Int): AirPodsUiAction

    data class PreferredMode(val mode: ListeningMode): AirPodsUiAction

    data class AssignGesture(val side: GestureSide, val action: GestureAction): AirPodsUiAction

    data class Tracking(val enabled: Boolean): AirPodsUiAction

}

fun mapAirPodsUiState(domain: AirPodsState, data: AirPodsRuntimeData, profileConnected: Boolean,

    bluetoothGranted: Boolean, productGatePassed: Boolean, conformanceBuild: String, nowMs: Long): AirPodsUiState {

    val pair = data.pairs[data.selectedPair] ?: PairSettings()

    return AirPodsUiState(domain, pair, data.start.enabled, data.start.suppressUntilExplicitResume,

        profileConnected, bluetoothGranted, productGatePassed,

        pair.verification?.conformanceBuild == conformanceBuild, nowMs)

}

/** Deterministic fit policy: preserve mark, avatar and friends; capsule disappears before overlap. */

fun airPodsHeaderFits(availablePx: Float, markPx: Float, trailingPx: Float, capsulePx: Float, collapse: Float, gapPx: Float = 16f): Boolean =

    listOf(availablePx, markPx, trailingPx, capsulePx, collapse, gapPx).all { it.isFinite() } &&
        capsulePx > 0f && gapPx >= 0f && collapse >= 0f && collapse < 0.08f &&
        availablePx >= markPx + trailingPx + capsulePx + gapPx



/** UI policy mirrors only actions supported by the active upstream Android paths. */

internal fun gestureOptions(side: GestureSide): List<GestureAction> = when (side) {

    GestureSide.LEFT, GestureSide.RIGHT -> listOf(GestureAction.NOISE_CONTROL, GestureAction.DIGITAL_ASSISTANT)

    GestureSide.NOD, GestureSide.SHAKE -> listOf(GestureAction.NONE, GestureAction.PLAY_PAUSE, GestureAction.NEXT, GestureAction.PREVIOUS)

}

internal fun AirPodsUiState.freshBattery(component: Int): BatteryValue? =

    domain.liveBattery(component, nowMs).takeIf { homeVisible }


/** Generic charge represents fresh earbuds only. Case charge always has an explicit label. */
internal data class CapsuleBatteryReading(val percent: Int, val component: Int? = null)
internal fun AirPodsUiState.capsuleBatteryReading(): CapsuleBatteryReading? {
    val earbuds = listOf(4, 2).mapNotNull { component ->
        freshBattery(component)?.percent?.let { component to it }
    }
    return when (earbuds.size) {
        2 -> CapsuleBatteryReading(earbuds.minOf { it.second })
        1 -> CapsuleBatteryReading(earbuds.single().second, earbuds.single().first)
        else -> freshBattery(8)?.percent?.let { CapsuleBatteryReading(it, 8) }
    }
}
