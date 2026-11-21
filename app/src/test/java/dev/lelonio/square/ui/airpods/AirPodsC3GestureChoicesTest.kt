// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.ui.airpods

import dev.lelonio.square.airpods.CommandProperty
import dev.lelonio.square.airpods.ListeningMode
import dev.lelonio.square.airpods.Received
import dev.lelonio.square.airpods.protocol.*
import org.junit.Assert.*
import org.junit.Test

class AirPodsC3GestureChoicesTest {
    @Test fun bothCallActionsShareOneInverseAssignment() {
        assertEquals(3, callAssignment(mute = true, once = true))
        assertEquals(2, callAssignment(mute = true, once = false))
        assertEquals(2, callAssignment(mute = false, once = true))
        assertEquals(3, callAssignment(mute = false, once = false))
    }

    @Test fun everyValidCycleRetainsAtLeastTwoModesAndPreservesOtherBits() {
        for (mask in 0..15) for (mode in ListeningMode.entries) {
            val next = toggledCycleMask(mask, mode)
            val bit = 1 shl (mode.wire - 1)
            if (next != null) {
                assertEquals(mask and bit.inv(), next and bit.inv())
                assertEquals(mask xor bit, next)
                if (Integer.bitCount(mask) >= 2) assertTrue(Integer.bitCount(next) >= 2)
            } else {
                assertTrue(mask and bit != 0)
                assertEquals(2, Integer.bitCount(mask))
            }
        }
    }

    @Test fun zeroAndSingleBitCanBeCompletedWithoutSendingInvalidMask() {
        val first = toggledCycleMask(0, ListeningMode.TRANSPARENCY)!!
        val second = toggledCycleMask(first, ListeningMode.ANC)!!
        val spec = AirPodsProperties.spec(CommandProperty.LISTENING_MODE_CONFIGS)!!
        assertFalse(spec.accepts(first))
        assertTrue(spec.accepts(second))
        assertEquals(6, second)
    }

    private val spec = AirPodsProperties.spec(CommandProperty.LISTENING_MODE_CONFIGS)!!

    @Test fun firstTouchFromZeroOrSingleBitNeverShowsOnWithoutDispatch() {
        for (mask in 0..15) {
            if (Integer.bitCount(mask) >= 2) continue
            for (mode in ListeningMode.entries) {
                val tap = cycleTap(mask, mode, spec::accepts, canBuild = true)
                val next = mask xor (1 shl (mode.wire - 1))
                if (Integer.bitCount(next) < 2) {
                    // Not sendable: remembered locally, never dispatched, and no switch is on for it.
                    assertEquals(CycleTap.Pending(next), tap)
                    ListeningMode.entries.forEach { assertFalse(cycleSwitchChecked(next, it, pending = true)) }
                } else {
                    assertEquals(CycleTap.Dispatch(next), tap)
                }
            }
        }
    }

    @Test fun secondModeFromPendingSelectionDispatchesTwoBitMask() {
        val first = cycleTap(0, ListeningMode.TRANSPARENCY, spec::accepts, canBuild = true) as CycleTap.Pending
        val second = cycleTap(first.mask, ListeningMode.ANC, spec::accepts, canBuild = true)
        assertEquals(CycleTap.Dispatch(6), second)
        assertEquals(2, Integer.bitCount((second as CycleTap.Dispatch).mask))
        // From a received single-bit mask the second mode also dispatches at two bits.
        val fromOne = cycleTap(first.mask, ListeningMode.ADAPTIVE, spec::accepts, canBuild = true)
        assertEquals(2, Integer.bitCount((fromOne as CycleTap.Dispatch).mask))
    }

    @Test fun pendingBuildIsRefusedWhenControlsAreNotReady() {
        assertEquals(CycleTap.Ignored, cycleTap(0, ListeningMode.TRANSPARENCY, spec::accepts, canBuild = false))
    }

    @Test fun reducingBelowTwoIsIgnoredAndReceivedMaskStaysChecked() {
        assertEquals(CycleTap.Ignored, cycleTap(6, ListeningMode.ANC, spec::accepts, canBuild = true))
        assertTrue(cycleSwitchChecked(6, ListeningMode.ANC, pending = false))
        assertFalse(cycleSwitchChecked(6, ListeningMode.ADAPTIVE, pending = false))
    }

    @Test fun localSideActionsAreIndependentOfReceivedMask() {
        val base = uiFixture().copy(gestureAssignments = mapOf(GestureSide.LEFT to GestureAction.DIGITAL_ASSISTANT,
            GestureSide.RIGHT to GestureAction.NOISE_CONTROL))
        val s = base.copy(domain = base.domain.copy(properties = base.domain.properties +
            (CommandProperty.STEM_CONFIG to Received(PropertyValue.Number(0), 1000, 7))))
        assertEquals(GestureAction.DIGITAL_ASSISTANT, s.gestureAssignments[GestureSide.LEFT])
        assertEquals(GestureAction.NOISE_CONTROL, s.gestureAssignments[GestureSide.RIGHT])
        assertTrue(s.stemPresentation().editable)
    }
}
