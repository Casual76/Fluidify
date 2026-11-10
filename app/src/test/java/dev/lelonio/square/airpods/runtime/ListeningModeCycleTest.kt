// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime

import dev.lelonio.square.airpods.*
import dev.lelonio.square.airpods.protocol.PropertyValue
import org.junit.Assert.*
import org.junit.Test

class ListeningModeCycleTest {
    private fun state(model: String, mode: ListeningMode = ListeningMode.ANC, allowOff: Boolean? = true): AirPodsState {
        val id = AirPodsIdentity(model, "Apple Inc.", "fixture")
        return AirPodsState(generation = 7, stage = AacpStage.SUBSCRIBED,
            identity = id, identityReceived = Received(id, 10, 7), mode = Received(mode, 11, 7),
            properties = allowOff?.let { mapOf(CommandProperty.ALLOW_OFF_OPTION to
                Received<PropertyValue>(PropertyValue.Number(if(it) 1 else 2), 11, 7)) }.orEmpty())
    }
    private fun cycle(initial: AirPodsState, count: Int): List<ListeningMode> {
        var current = initial
        return (0 until count).map {
            val next = requireNotNull(current.nextTileListeningMode())
            // Test each next click after a distinct matching RX; never optimistic UI state.
            current = current.copy(mode = Received(next, 12 + it.toLong(), 7))
            next
        }
    }
    @Test fun pro3IncludesAdaptiveAndAllowedOffInPinnedOrder() {
        assertEquals(listOf(ListeningMode.OFF, ListeningMode.TRANSPARENCY, ListeningMode.ADAPTIVE, ListeningMode.ANC),
            cycle(state("A3063"), 4))
    }
    @Test fun pro1OmitsUnsupportedAdaptive() {
        assertEquals(listOf(ListeningMode.OFF, ListeningMode.TRANSPARENCY, ListeningMode.ANC), cycle(state("A2084"), 3))
    }
    @Test fun currentOffRestrictionFiltersBothModelsAndAllowsLeavingOff() {
        assertEquals(listOf(ListeningMode.TRANSPARENCY, ListeningMode.ADAPTIVE, ListeningMode.ANC),
            cycle(state("A3063", allowOff = false), 3))
        assertEquals(listOf(ListeningMode.TRANSPARENCY, ListeningMode.ANC), cycle(state("A2084", allowOff = false), 2))
        assertEquals(ListeningMode.TRANSPARENCY, state("A3063", ListeningMode.OFF, false).nextTileListeningMode())
    }
    @Test fun unknownIdentityMissingOrStaleModeAndPendingDoNotEmitCandidate() {
        assertNull(state("A9999").nextTileListeningMode())
        assertNull(state("A3063").copy(mode = null).nextTileListeningMode())
        assertNull(state("A3063").copy(mode = Received(ListeningMode.ANC, 11, 6)).nextTileListeningMode())
        assertNull(state("A3063").copy(identityReceived = null).nextTileListeningMode())
        assertNull(state("A3063").copy(stage = AacpStage.CLOSED).nextTileListeningMode())
        assertNull(state("A3063").copy(pending = mapOf(CommandProperty.MODE to PendingCommand(3, 12, 1))).nextTileListeningMode())
    }
    @Test fun staleRestrictionDoesNotBecomeCurrentAndUnknownAllowanceRemainsOnlyCandidate() {
        val stale = state("A3063", allowOff = false).copy(properties = mapOf(CommandProperty.ALLOW_OFF_OPTION to
            Received(PropertyValue.Number(2), 11, 6)))
        assertEquals(ListeningMode.OFF, stale.nextTileListeningMode())
        val unknown = state("A3063", allowOff = null)
        assertEquals(ListeningMode.OFF, unknown.nextTileListeningMode())
        assertEquals(ListeningMode.ANC, unknown.mode?.value)
        assertNull(unknown.propertyObserved(CommandProperty.ALLOW_OFF_OPTION))
    }
}
