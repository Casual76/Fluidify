package dev.lelonio.square.airpods.runtime.android.platform

import org.junit.Assert.*
import org.junit.Test

/** PREPARED, NOT EXECUTED. Fake Telecom dispatch, no real calls. */
class OrdinaryCallOperationsTest {
    private class Port : OrdinaryCallPort {
        var current = OrdinaryCallState(OrdinaryCallState.IDLE)
        var answer: PlatformResult = PlatformResult.RequestAccepted
        val requests = mutableListOf<Boolean>()
        override fun state() = current
        override fun dispatch(answer: Boolean): PlatformResult { requests += answer; return this.answer }
    }
    @Test fun nodAnswersShakeRejectsOncePerRingingEpoch() {
        val port = Port(); val owner = OrdinaryCallOperations(port)
        owner.observe(); port.current = OrdinaryCallState(OrdinaryCallState.RINGING); owner.observe()
        assertEquals(PlatformResult.RequestAccepted, owner.request(true, owner.epoch) { true })
        assertTrue(owner.request(false, owner.epoch) { true } is PlatformResult.Denied)
        port.current = OrdinaryCallState(OrdinaryCallState.IDLE); owner.observe()
        port.current = OrdinaryCallState(OrdinaryCallState.RINGING); owner.observe()
        assertEquals(PlatformResult.RequestAccepted, owner.request(false, owner.epoch) { true })
        assertEquals(listOf(true, false), port.requests)
    }
    @Test fun observedCallWaitingStaysBlockedUntilIdle() {
        val port = Port(); val owner = OrdinaryCallOperations(port)
        port.current = OrdinaryCallState(OrdinaryCallState.ACTIVE); owner.observe()
        port.current = OrdinaryCallState(OrdinaryCallState.RINGING); owner.observe()
        assertTrue(owner.request(false, owner.epoch) { true } is PlatformResult.Denied)
        assertTrue(port.requests.isEmpty())
    }
    @Test fun lateGestureRevokedOptInUnknownAndMultipleCallsNeverDispatch() {
        val port = Port(); val owner = OrdinaryCallOperations(port)
        port.current = OrdinaryCallState(OrdinaryCallState.RINGING); owner.observe(); val old = owner.epoch
        owner.reset(); owner.observe()
        assertTrue(owner.request(true, old) { true } is PlatformResult.Denied)
        assertTrue(owner.request(true, owner.epoch) { false } is PlatformResult.Denied)
        port.current = OrdinaryCallState(OrdinaryCallState.RINGING, true); owner.observe()
        assertTrue(owner.request(false, owner.epoch) { true } is PlatformResult.Denied)
        port.current = OrdinaryCallState(OrdinaryCallState.UNKNOWN); owner.observe()
        assertTrue(owner.request(true, owner.epoch) { true } is PlatformResult.Denied)
        assertTrue(port.requests.isEmpty())
    }
    @Test fun callEndingBetweenDetectionAndDispatchInvalidatesEpoch() {
        val port = Port(); val owner = OrdinaryCallOperations(port)
        port.current = OrdinaryCallState(OrdinaryCallState.RINGING); owner.observe(); val epoch = owner.epoch
        port.current = OrdinaryCallState(OrdinaryCallState.IDLE)
        assertTrue(owner.request(false, epoch) { true } is PlatformResult.Denied)
        assertTrue(port.requests.isEmpty())
    }
    @Test fun platformRejectionDoesNotLatchSoSecondGestureInSameRingEpochRetries() {
        val port = Port(); val owner = OrdinaryCallOperations(port)
        port.current = OrdinaryCallState(OrdinaryCallState.RINGING); owner.observe()
        port.answer = PlatformResult.Denied(PlatformFailure.REJECTED)
        assertEquals(PlatformResult.Denied(PlatformFailure.REJECTED), owner.request(false, owner.epoch) { true })
        port.answer = PlatformResult.RequestAccepted
        assertEquals(PlatformResult.RequestAccepted, owner.request(false, owner.epoch) { true })
        assertEquals(listOf(false, false), port.requests)
        // an accepted dispatch still latches the epoch
        assertTrue(owner.request(false, owner.epoch) { true } is PlatformResult.Denied)
        assertEquals(2, port.requests.size)
    }
}
