// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime

import dev.lelonio.square.airpods.*
import dev.lelonio.square.airpods.protocol.*

/** Upstream Tile order, filtered by the current model, RX restrictions and pending state.
 * Returning a candidate never changes observed mode or confirms a hardware write. */
internal fun AirPodsState.nextTileListeningMode(): ListeningMode? {
    val wire = (propertyEffective(CommandProperty.MODE)?.value as? PropertyValue.Number)?.value ?: return null
    val current = ListeningMode.entries.firstOrNull { it.wire == wire } ?: return null
    val order = listOf(ListeningMode.OFF, ListeningMode.TRANSPARENCY,
        ListeningMode.ADAPTIVE, ListeningMode.ANC)
    val index = order.indexOf(current)
    return (1 until order.size).asSequence()
        .map { order[(index + it) % order.size] }
        .firstOrNull { modelOffers(CommandProperty.MODE, PropertyValue.Number(it.wire)) }
}
