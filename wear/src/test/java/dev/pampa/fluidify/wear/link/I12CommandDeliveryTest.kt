package dev.pampa.fluidify.wear.link

import android.app.Application
import dev.pampa.fluidify.wear.protocol.Command
import dev.pampa.fluidify.wear.protocol.CommandAck
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class I12CommandDeliveryTest {
    @Test fun timeoutNeverResendsAndLateIdCannotCompleteANewIntent() = runTest {
        val h = I12CommandHarness()
        val first = h.prepare(Command.AddToQueue("spotify:track:a"), 10_000)
        val result = async { first.await() }
        runCurrent()
        val old = h.sent.single()
        advanceTimeBy(10_000)
        runCurrent()
        assertNull(result.await())
        assertNull(first.await()) // same captured intent cannot be run twice
        assertEquals(1, h.sent.size)
        val second = async { h.send(Command.AddToQueue("spotify:track:b")) }
        runCurrent()
        assertEquals(2, h.sent.size)
        assertNotEquals(old.id, h.sent.last().id)
        h.ack(old)
        runCurrent()
        assertFalse(second.isCompleted)
        h.ack()
        runCurrent()
        assertTrue(second.await()!!.ok)
        assertEquals(listOf("a-1", "a-1"), h.sent.map { it.accountSession })
    }

    @Test fun accountChangesDuringDiscoveryCannotSendAUnderB() = runTest {
        val h = I12CommandHarness()
        val found = CompletableDeferred<String?>()
        h.discover = { found.await() }
        val result = async { h.send(Command.SetLiked("spotify:track:a", true)) }
        runCurrent()
        h.account("b", "b-1", 2)
        found.complete("phone")
        runCurrent()
        assertNull(result.await())
        assertTrue(h.sent.isEmpty())
    }

    @Test fun accountCapturedBeforeLaunchIsNotReinterpretedWhenLaunchRuns() = runTest {
        val h = I12CommandHarness()
        val request = h.prepare(Command.AddToQueue("spotify:track:a"), 10_000)
        h.account("b", "b-1", 2)
        assertNull(request.await())
        assertFalse(request.ifCurrent { fail("A must not publish under B") })
        assertTrue(h.sent.isEmpty())
    }

    @Test fun accountChangesAfterAckBeforeAwaitResumesRejectsResult() = runTest {
        val h = I12CommandHarness()
        val request = h.prepare(Command.SetLiked("spotify:track:a", true), 10_000)
        val result = async { request.await() }
        runCurrent()
        h.ack()
        h.account("b", "b-1", 2)
        runCurrent()
        assertNull(result.await())
        assertEquals("a-1", h.sent.single().accountSession)
        assertFalse(request.ifCurrent { fail("Revoked ACK must not publish") })
    }

    @Test fun accountChangesAfterSuccessfulAwaitBeforePublicationRejectsCommit() = runTest {
        val h = I12CommandHarness()
        h.afterSend = { h.ack(it) }
        val request = h.prepare(Command.Transfer("phone"), 11_000)
        assertTrue(request.await()!!.ok)
        h.account("b", "b-1", 2)
        assertFalse(request.ifCurrent { fail("Must keep local playback") })
        assertEquals(1, h.sent.size)
    }

    @Test fun unknownIdAndNegativeAckNeverBecomeSuccess() = runTest {
        val h = I12CommandHarness()
        val result = async { h.send(Command.Transfer("phone")) }
        runCurrent()
        h.delivery.onAck(CommandAck(999, true))
        runCurrent()
        assertFalse(result.isCompleted)
        h.ack(ok = false)
        runCurrent()
        assertFalse(result.await()!!.ok)
        assertEquals(1, h.sent.size)
    }

    @Test fun cancelledWaiterIgnoresLateAckAndDoesNotResend() = runTest {
        val h = I12CommandHarness()
        val request = h.prepare(Command.AddToQueue("spotify:track:a"), 10_000)
        val result = async { request.await() }
        runCurrent()
        result.cancel()
        runCurrent()
        h.ack()
        assertNull(request.await())
        assertEquals(1, h.sent.size)
    }

    @Test fun unreachableDiscoveryIsTerminalAndFreshExplicitGestureGetsANewId() = runTest {
        val h = I12CommandHarness()
        h.discover = { null }
        assertNull(h.send(Command.Play))
        assertTrue(h.sent.isEmpty())
        h.discover = { "phone" }
        h.afterSend = { h.ack(it) }
        assertTrue(h.send(Command.Play)!!.ok)
        assertEquals(102L, h.sent.single().id)
    }
}
