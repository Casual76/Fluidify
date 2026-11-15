package dev.pampa.fluidify.wear.ui.player

import dev.pampa.fluidify.wear.link.LinkStatus
import dev.pampa.fluidify.wear.link.ReceivedSnapshot
import dev.pampa.fluidify.wear.protocol.PlaybackSource

internal data class AirPodsBatteryModel(val left: Int? = null, val right: Int? = null, val case: Int? = null) {
    val available: Boolean get() = left != null || right != null || case != null
}

/** The exact projection used by the screen, also valid for optimistic snapshot.copy frames. */
internal fun airPodsBatteryModel(
    received: ReceivedSnapshot?,
    link: LinkStatus,
    accountSession: String?,
    wallMs: Long,
    elapsedMs: Long,
): AirPodsBatteryModel {
    val snapshot = received?.snapshot ?: return AirPodsBatteryModel()
    val receipt = received.airPodsBatteryReceipt ?: return AirPodsBatteryModel()
    val battery = snapshot.airPodsBattery ?: return AirPodsBatteryModel()
    if (link != LinkStatus.CONNECTED || snapshot.source != PlaybackSource.PHONE ||
        accountSession.isNullOrBlank() || battery.accountSession != accountSession ||
        receipt.battery != battery || !receipt.isCurrent(wallMs, elapsedMs)) return AirPodsBatteryModel()
    fun visible(percent: Int?, expires: Long?) = percent?.takeIf { expires != null && elapsedMs < expires }
    return AirPodsBatteryModel(
        visible(battery.left?.percent, receipt.leftExpiresAtElapsedMs),
        visible(battery.right?.percent, receipt.rightExpiresAtElapsedMs),
        visible(battery.case?.percent, receipt.caseExpiresAtElapsedMs),
    )
}
