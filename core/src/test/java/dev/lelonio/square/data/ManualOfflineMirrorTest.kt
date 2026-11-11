package dev.lelonio.square.data

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ManualOfflineMirrorTest {
    @After fun reset() { SpotifyNetworkPolicy.setManual(false) }

    @Test fun replayAndTransitionsAreOrderedAndDoNotRenewOldPermits() {
        SpotifyNetworkPolicy.setManual(false)
        val old = SpotifyNetworkPolicy.capture()
        val seen = mutableListOf<SpotifyNetworkPolicy.Snapshot>()
        val binding = SpotifyNetworkPolicy.bindNative { snapshot ->
            seen += snapshot
            if (snapshot.manual) assertFalse(SpotifyNetworkPolicy.isCurrent(old.revision))
        }
        try {
            SpotifyNetworkPolicy.setManual(true)
            SpotifyNetworkPolicy.setManual(false)
            assertEquals(listOf(false, true, false), seen.map { it.manual })
            assertTrue(seen.zipWithNext().all { (a, b) -> b.revision > a.revision })
            assertThrows(SpotifyNetworkPolicy.Offline::class.java) { old.check() }
            SpotifyNetworkPolicy.capture().check()
        } finally { binding.close() }
    }

    @Test fun offAdmissionWaitsForNativeAcknowledgementAndCannotBorrowAnOldPermit() {
        SpotifyNetworkPolicy.setManual(false)
        val old = SpotifyNetworkPolicy.capture()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        var hold = false
        val binding = SpotifyNetworkPolicy.bindNative { snapshot ->
            if (hold && !snapshot.manual) {
                entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
        SpotifyNetworkPolicy.setManual(true); hold = true
        val toggle = kotlin.concurrent.thread { SpotifyNetworkPolicy.setManual(false) }
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertThrows(SpotifyNetworkPolicy.Offline::class.java) { SpotifyNetworkPolicy.capture() }
            assertThrows(SpotifyNetworkPolicy.Offline::class.java) { old.check() }
        } finally { release.countDown(); toggle.join(5_000); binding.close() }
        assertFalse(toggle.isAlive)
        SpotifyNetworkPolicy.capture().check()
        assertThrows(SpotifyNetworkPolicy.Offline::class.java) { old.check() }
    }

    @Test fun failingOrdinaryObserverCannotSkipNativeRevocation() {
        SpotifyNetworkPolicy.setManual(false)
        var mirrored = false
        val mirror = SpotifyNetworkPolicy.bindNative { if (it.manual) mirrored = true }
        val observer = SpotifyNetworkPolicy.observe { if (it) error("synthetic observer failure") }
        try {
            assertThrows(IllegalStateException::class.java) { SpotifyNetworkPolicy.setManual(true) }
            assertTrue(mirrored)
            assertThrows(SpotifyNetworkPolicy.Offline::class.java) { SpotifyNetworkPolicy.capture() }
        } finally { observer.close(); mirror.close() }
    }
}
