package dev.lelonio.square.airpods

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AirPodsSessionOwnerTest {
    @Test fun pauseLeaseExpiresAtNinetySecondsWithoutPolling() = runTest {
        var acquisitions = 0; var releases = 0
        val owner = AirPodsSessionOwner(this, AirPodsClock { testScheduler.currentTime }, { acquisitions++ }, { releases++ })
        owner.configure(true, true); owner.localPlayback(true)
        assertEquals(1, acquisitions)
        owner.localPlayback(false)
        advanceTimeBy(89_999); runCurrent(); assertEquals(0, releases)
        advanceTimeBy(1); runCurrent(); assertEquals(1, releases)
        owner.close()
    }
    @Test fun visibleUiKeepsExpiredPauseButClosingItReleasesImmediately() = runTest {
        var released = 0
        val owner = AirPodsSessionOwner(this, AirPodsClock { testScheduler.currentTime }, {}, { released++ })
        owner.configure(true, true); owner.setUiVisible(true); owner.localPlayback(true); owner.localPlayback(false)
        advanceTimeBy(90_000); runCurrent(); assertEquals(0, released)
        owner.setUiVisible(false); assertEquals(1, released)
        owner.close()
    }
    @Test fun remoteTransferAndPermissionLossReleaseWithoutGrace() = runTest {
        var released = 0
        val owner = AirPodsSessionOwner(this, AirPodsClock { testScheduler.currentTime }, {}, { released++ })
        owner.configure(true, true); owner.localPlayback(true); owner.localPlayback(false)
        owner.transferredToRemote(); assertEquals(1, released)
        owner.setUiVisible(true); owner.configure(true, false); assertEquals(2, released)
        owner.close()
    }
    @Test fun disconnectSuppressesExistingLeasesAndProfilesUntilExplicitSession() = runTest {
        var acquired = 0
        val owner = AirPodsSessionOwner(this, AirPodsClock { testScheduler.currentTime }, { acquired++ }, {})
        owner.configure(true, true); owner.setUiVisible(true); owner.disconnectOrLogout()
        owner.profileConnected(true); owner.setUiVisible(true); assertEquals(1, acquired)
        owner.explicitConnectOrNewUserSession(); assertEquals(2, acquired)
        owner.profileConnected(true); assertEquals(2, acquired)
        owner.close()
    }
}
