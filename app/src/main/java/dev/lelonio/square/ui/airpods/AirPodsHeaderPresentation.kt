// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.ui.airpods

/** Presentation only: persisted percentages never supply a charging status. */
internal data class CaseBatteryReading(val percent: Int, val live: Boolean, val status: Int = 2)

internal fun AirPodsUiState.caseBatteryReading(): CaseBatteryReading? {
    freshBattery(BatteryCase)?.let { value ->
        value.percent?.let { return CaseBatteryReading(it, live = true, status = value.status) }
    }
    return lastKnownCasePercent?.let { CaseBatteryReading(it.percent, live = false) }
}

internal val AirPodsUiState.headerName: String?
    get() = effectiveName ?: identity?.name

/** Follow late INFORMATION when idle or after submit, preserving an unsent edit. */
internal data class AirPodsRenameDraft(val text: String, val edited: Boolean = false) {
    fun edit(value: String) = copy(text = value, edited = true)
    fun reconcile(knownName: String) = if (edited) this else copy(text = knownName)
    fun submitted() = copy(edited = false)
}
