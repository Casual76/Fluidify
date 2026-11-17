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
