// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.ui.airpods

import dev.lelonio.square.airpods.AacpCodec
import dev.lelonio.square.airpods.AacpStage
import dev.lelonio.square.airpods.CommandProperty
import dev.lelonio.square.airpods.Received
import dev.lelonio.square.airpods.protocol.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Synthetic display/gesture -> real EQ packet encoder. No transport or hardware. */
@OptIn(ExperimentalCoroutinesApi::class)
class AirPodsC5EqualizerTest {
    private fun withoutRx() = uiFixture().let { it.copy(domain = it.domain.copy(properties = emptyMap())) }

    @Test fun firstBandEditUsesDomainDefaultsForAllUntouchedFields() {
        val state = withoutRx()
        assertNull(state.domain.propertyObserved(CommandProperty.EQ))
        val write = requireNotNull(state.equalizerWrite { it.copy(low = 81) })
        assertEquals(CommandProperty.EQ, write.property)
        assertEquals(state.domain.generation, write.generation)
        assertEquals(PropertyValue.Eq(false, 81, 50, 50), write.value)
        assertArrayEquals(byteArrayOf(4,0,4,0,0x63,0,5,0,1,1,81,50,50),
            AacpCodec.eq(write.value as PropertyValue.Eq))
    }

    @Test fun toggleSendsCompleteCurrentEqAndDoesNotResetBands() {
        val state = uiFixture()
        val write = requireNotNull(state.equalizerWrite { it.copy(enabled = true) })
        assertArrayEquals(byteArrayOf(4,0,4,0,0x63,0,5,0,1,2,40,50,60),
            AacpCodec.eq(write.value as PropertyValue.Eq))
    }

    @Test fun bandEditPreservesNewerIntentInsteadOfOldRx() {
        val state = uiFixture().let { it.copy(domain = it.domain.copy(desired = mapOf(
            CommandProperty.EQ to DesiredProperty(PropertyValue.Eq(true, 19, 28, 37), 1, 7,
                DesiredStatus.SENT, 1001)))) }
        assertEquals(PropertyValue.Eq(true, 19, 28, 92),
            state.equalizerWrite { it.copy(high = 92) }?.value)
    }

    @Test fun noWriteForUnchangedValueOrClosedGate() {
        val state = withoutRx()
        assertNull(state.equalizerWrite { it.copy(mid = 50) })
        listOf(state.copy(bluetoothGranted = false), state.copy(suppressed = true),
            state.copy(domain = state.domain.copy(stage = AacpStage.CLOSED))).forEach {
            assertNull(it.equalizerWrite { eq -> eq.copy(high = 70) })
        }
    }

    @Test fun fastDragFlushesOneCompleteFinalPacketAndCancelsTimer() = runTest {
        val packets = mutableListOf<ByteArray>()
        val gesture = AirPodsLiveSliderGesture(this, 0..100, 100L) { low ->
            val action = requireNotNull(withoutRx().equalizerWrite { it.copy(low = low) })
            packets += AacpCodec.eq(action.value as PropertyValue.Eq)
        }
        gesture.change(61, 50)
        runCurrent()
        advanceTimeBy(30)
        gesture.change(72, 50)
        runCurrent()
        advanceTimeBy(30)
        gesture.change(83, 50)
        gesture.finish()
        gesture.finish()
        advanceTimeBy(300)
        runCurrent()
        assertEquals(1, packets.size)
        assertArrayEquals(byteArrayOf(4,0,4,0,0x63,0,5,0,1,1,83,50,50), packets.single())
    }

    @Test fun pauseSendsDuringGestureAndReleaseDoesNotRepeatSameValue() = runTest {
        val sent = mutableListOf<Int>()
        val gesture = AirPodsLiveSliderGesture(this, 0..100, 100L, sent::add)
        gesture.change(67, 50)
        runCurrent()
        advanceTimeBy(99)
        runCurrent()
        assertTrue(sent.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(67), sent)
        gesture.finish()
        advanceTimeBy(200)
        runCurrent()
        assertEquals(listOf(67), sent)
        gesture.change(90, 67)
        gesture.finish()
        assertEquals(listOf(67, 90), sent)
    }

    @Test fun releaseAfterPauseSendsChangedFinalValueOnceAndCancellationDropsDraft() = runTest {
        val sent = mutableListOf<Int>()
        val gesture = AirPodsLiveSliderGesture(this, 0..100, 100L, sent::add)
        gesture.change(60, 50)
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        gesture.change(80, 60)
        gesture.finish()
        advanceTimeBy(200)
        runCurrent()
        assertEquals(listOf(60, 80), sent)
        gesture.change(95, 80)
        gesture.cancel()
        advanceTimeBy(200)
        runCurrent()
        assertEquals(listOf(60, 80), sent)
    }

    @Test fun contraryRxBecomesBasisForNextFullPacket() {
        val state = withoutRx().let { it.copy(domain = it.domain.copy(properties = mapOf(
            CommandProperty.EQ to Received(PropertyValue.Eq(true, 12, 23, 34), 1100, 7)))) }
        assertEquals(PropertyValue.Eq(true, 12, 66, 34),
            state.equalizerWrite { it.copy(mid = 66) }?.value)
    }
}
