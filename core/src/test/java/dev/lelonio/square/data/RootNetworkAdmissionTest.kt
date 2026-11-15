package dev.lelonio.square.data

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Native scope opener is synthetic; no native library, account or network request is used. */
class RootNetworkAdmissionTest {
    @After fun online() { SpotifyNetworkPolicy.setManual(false) }

    @Test fun accountAdmissionIsReleasedBeforeTheBlockingRequestAndPinLivesUntilReturn() {
        SpotifyNetworkPolicy.setManual(false)
        val accountLock = Any()
        var pinned = false
        var closed = false
        val binding = SpotifyNetworkPolicy.bindNative(enter = {
            assertTrue(Thread.holdsLock(accountLock))
            pinned = true
            AutoCloseable { closed = true }
        }, mirror = {})
        try {
            val result = SpotifyNetworkPolicy.capture().dispatchWithAdmission(
                admission = { enter -> synchronized(accountLock) { enter() } }, requireNativeScope = true,
            ) {
                assertFalse("HTTP must not retain the account monitor", Thread.holdsLock(accountLock))
                assertTrue(pinned)
                assertFalse(closed)
                "accepted"
            }
            assertTrue(result == "accepted")
            assertTrue(closed)
        } finally { binding.close() }
    }

    @Test fun revocationDuringAdmissionCannotDispatchAndClosesThePinnedScope() {
        SpotifyNetworkPolicy.setManual(false)
        var closed = false
        var sent = false
        val binding = SpotifyNetworkPolicy.bindNative(enter = { AutoCloseable { closed = true } }, mirror = {})
        try {
            val permit = SpotifyNetworkPolicy.capture()
            try {
                permit.dispatchWithAdmission(admission = { enter ->
                    enter()
                    SpotifyNetworkPolicy.setManual(true)
                }, requireNativeScope = true) { sent = true }
                fail("revoked dispatch must be refused")
            } catch (_: SpotifyNetworkPolicy.Offline) { }
            assertFalse(sent)
            assertTrue(closed)
        } finally { binding.close() }
    }

    @Test fun failedRequestStillClosesThePinnedScope() {
        SpotifyNetworkPolicy.setManual(false)
        var closed = false
        val binding = SpotifyNetworkPolicy.bindNative(enter = { AutoCloseable { closed = true } }, mirror = {})
        try {
            try {
                SpotifyNetworkPolicy.capture().dispatchWithAdmission({ it() }, requireNativeScope = true) {
                    throw IllegalArgumentException("synthetic request failure")
                }
                fail("request failure must propagate")
            } catch (_: IllegalArgumentException) { }
            assertTrue(closed)
        } finally { binding.close() }
    }

    @Test fun absentAdmissionOrNativeBindingCannotBecomeASuccessfulAccountWrite() {
        SpotifyNetworkPolicy.setManual(false)
        var sent = false
        try {
            SpotifyNetworkPolicy.capture().dispatchWithAdmission({}, block = { sent = true })
            fail("admission callback must enter its scope")
        } catch (_: IllegalStateException) { }
        try {
            SpotifyNetworkPolicy.capture().dispatchWithAdmission({ it() }, requireNativeScope = true) { sent = true }
            fail("account writes require a live native binding")
        } catch (_: IllegalStateException) { }
        assertFalse(sent)
    }
}
