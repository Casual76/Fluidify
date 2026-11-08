// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.android.platform

data class AssistantAuthorization(
    val selectedBonded: Boolean,
    val permission: Boolean,
    val controllerEntitled: Boolean,
    val noCallOrRouteMix: Boolean,
    val visible: Boolean,
    val backgroundOptIn: Boolean,
    val ownAssociation: Boolean,
    /** Only a validated live AACP stem long press; never cache, presence or synthetic intent. */
    val physicalLongPress: Boolean,
)
fun interface AssistantLaunchPort { fun requestVoiceCommand(): PlatformResult }

class OrdinaryAssistantOperation(
    private val launch: AssistantLaunchPort,
    private val elapsed: () -> Long,
    private val authorization: (PlatformAction) -> AssistantAuthorization,
) {
    fun request(action: PlatformAction, stillAuthorized: () -> Boolean): PlatformResult {
        val now = elapsed()
        if (now < action.issuedAtElapsed || now - action.issuedAtElapsed > 5_000 ||
            !runCatching(stillAuthorized).getOrDefault(false)) return PlatformResult.Denied(PlatformFailure.STALE)
        val a = runCatching { authorization(action) }.getOrNull()
            ?: return PlatformResult.Denied(PlatformFailure.NOT_ENTITLED)
        val reason = when {
            !a.permission -> PlatformFailure.PERMISSION
            !a.selectedBonded -> PlatformFailure.NOT_SELECTED_BONDED
            !a.controllerEntitled -> PlatformFailure.NOT_ENTITLED
            !a.noCallOrRouteMix -> PlatformFailure.CALL_OR_ROUTE_MIX
            !a.physicalLongPress -> PlatformFailure.BACKGROUND_NOT_AUTHORIZED
            !a.visible && !(a.backgroundOptIn && a.ownAssociation) -> PlatformFailure.BACKGROUND_NOT_AUTHORIZED
            else -> null
        }
        if (reason != null) return PlatformResult.Denied(reason)
        if (!runCatching(stillAuthorized).getOrDefault(false)) return PlatformResult.Denied(PlatformFailure.STALE)
        return launch.requestVoiceCommand()
    }
}
