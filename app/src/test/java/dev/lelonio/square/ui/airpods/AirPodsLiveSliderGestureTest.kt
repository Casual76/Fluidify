// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.ui.airpods

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Prepared for I2; virtual time, no Android transport or hardware. */
@OptIn(ExperimentalCoroutinesApi::class)
class AirPodsLiveSliderGestureTest {
    @Test fun quietPeriodRestartsAndReleaseNeverDuplicatesTheDebouncedFinalValue() = runTest {
        val sent = mutableListOf<Int>()
        val gesture = AirPodsLiveSliderGesture(this, 0..100, 150L, sent::add)
        assertNull(gesture.draft)
        advanceTimeBy(1_000); runCurrent()
        assertTrue(sent.isEmpty())
        gesture.change(25, 50)
        advanceTimeBy(149); runCurrent()
        assertTrue(sent.isEmpty())
        gesture.change(35, 50)
        advanceTimeBy(149); runCurrent()
        assertTrue(sent.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf(35), sent)
        assertEquals(35, gesture.draft)
        gesture.finish()
        gesture.finish()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf(35), sent)
        assertNull(gesture.draft)
    }

    @Test fun immediateReleaseFlushesOnlyLatestAndCancelsItsTimer() = runTest {
        val sent = mutableListOf<Int>()
        val gesture = AirPodsLiveSliderGesture(this, 0..100, 100L, sent::add)
        for (value in 10..90) gesture.change(value, 50)
        gesture.finish()
        assertEquals(listOf(90), sent)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf(90), sent)
    }

    @Test fun afterAnIntermediateSendThereIsExactlyOneFinalSend() = runTest {
        val sent = mutableListOf<Int>()
        val gesture = AirPodsLiveSliderGesture(this, 0..100, 100L, sent::add)
        gesture.change(20, 50)
        advanceTimeBy(100); runCurrent()
        gesture.change(70, 20)
        gesture.change(80, 20)
        gesture.finish()
        advanceTimeBy(500); runCurrent()
        assertEquals(listOf(20, 80), sent)
    }

    @Test fun duplicateIntegerStepsDoNotPostponeTheQuietPeriod() = runTest {
        val sent = mutableListOf<Int>()
        val gesture = AirPodsLiveSliderGesture(this, 10..85, 100L, sent::add)
        gesture.change(90, 40)
        advanceTimeBy(80)
        gesture.change(200, 40) // same quantized/clamped integer
        advanceTimeBy(20); runCurrent()
        gesture.finish()
        assertEquals(listOf(85), sent)
    }

    @Test fun cancellationOrDisableDropsDraftWithoutFlushing() = runTest {
        val sent = mutableListOf<Int>()
        val gesture = AirPodsLiveSliderGesture(this, 0..100, 100L, sent::add)
        gesture.change(90, 50)
        gesture.cancel()
        gesture.finish()
        advanceTimeBy(500); runCurrent()
        assertNull(gesture.draft)
        assertTrue(sent.isEmpty())
    }

    @Test fun aLaterGestureCanRestoreAContradictedValueAndReturnToAnEarlierSentValue() = runTest {
        val sent = mutableListOf<Int>()
        val gesture = AirPodsLiveSliderGesture(this, 0..100, 100L, sent::add)
        gesture.change(25, 50); gesture.finish()
        gesture.change(25, 75); gesture.finish() // actual/effective value changed externally
        gesture.change(80, 25)
        advanceTimeBy(100); runCurrent()
        gesture.change(25, 80); gesture.finish()
        assertEquals(listOf(25, 25, 80, 25), sent)
    }

    @Test fun contraryRxWhileFingerIsStillDownMustNotSuppressRelease() = runTest {
        val sent = mutableListOf<Int>()
        val gesture = AirPodsLiveSliderGesture(this, 0..100, 100L, sent::add)
        gesture.change(80, 50)
        advanceTimeBy(100); runCurrent()
        assertEquals(listOf(80), sent)
        gesture.finish(20)
        assertEquals(listOf(80, 80), sent)
    }

    @Test fun retiredScopeCannotFlushIntoAReplacementSession() = runTest {
        val sent = mutableListOf<Int>()
        val sessionScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val gesture = AirPodsLiveSliderGesture(sessionScope, 0..100, 150L, sent::add)
        gesture.change(90, 50)
        sessionScope.cancel()
        gesture.finish()
        advanceTimeBy(500); runCurrent()
        assertTrue(sent.isEmpty())
    }
}
