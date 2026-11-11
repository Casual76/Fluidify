package dev.lelonio.square.update

import dev.antigravity.fluidengine.foundation.compareVersions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the app's installed-version adapter with the comparator used by the updater. */
class UpdaterVersionTest {
    @Test fun installedBetaCanReceiveNextBetaInSameReleaseTrain() {
        val installed = FluidifyUpdates.releaseVersionName("2.1.0-beta.1")
        assertTrue(compareVersions("2.1.0-beta.2", installed) > 0)
    }

    @Test fun installedBetaCanReceiveFinalReleaseInSameTrain() {
        val installed = FluidifyUpdates.releaseVersionName("2.1.0-beta.1")
        assertTrue(compareVersions("2.1.0", installed) > 0)
    }

    @Test fun stableIsNotDowngradedToPrerelease() {
        val installed = FluidifyUpdates.releaseVersionName("2.1.0")
        assertTrue(compareVersions("2.1.0-beta.2", installed) < 0)
    }

    @Test fun debugBetaKeepsItsPrereleaseIdentity() {
        val installed = FluidifyUpdates.releaseVersionName("2.1.0-beta.1-debug")
        assertEquals(0, compareVersions("2.1.0-beta.1", installed))
        assertTrue(compareVersions("2.1.0-beta.2", installed) > 0)
        assertTrue(compareVersions("2.1.0", installed) > 0)
    }

    @Test fun debugStableKeepsItsReleaseIdentity() {
        val installed = FluidifyUpdates.releaseVersionName("2.1.0-debug")
        assertEquals(0, compareVersions("2.1.0", installed))
        assertTrue(compareVersions("2.1.1", installed) > 0)
    }
}
