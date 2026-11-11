// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime

import dev.lelonio.square.airpods.*
import dev.lelonio.square.airpods.protocol.PropertyValue

/** Upstream Tile order, filtered by the current model, RX restrictions and pending state.
 * Returning a candidate never changes observed mode or confirms a hardware write. */
internal fun AirPodsState.nextTileListeningMode(): ListeningMode? {
    val current = mode?.takeIf { it.generation == generation }?.value ?: return null
    val order = listOf(ListeningMode.OFF, ListeningMode.TRANSPARENCY,
        ListeningMode.ADAPTIVE, ListeningMode.ANC)
    val index = order.indexOf(current)
    return (1 until order.size).asSequence()
        .map { order[(index + it) % order.size] }
        .firstOrNull { canAttemptWrite(CommandProperty.MODE, PropertyValue.Number(it.wire)) }
}
