// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime

import org.junit.Assert.*
import org.junit.Test

/** SOURCE ONLY preparation. Tests the same activity-local state machine used by MainActivity;
 * no ActivityManager, permission dialog or OEM lifecycle delivery is simulated here. */
class AirPodsAppOpeningTest {
    private fun opened() = AirPodsAppOpening().also {
        it.created(restored = false, ordinaryIntent = true)
        assertTrue(it.resumed())
    }

    @Test fun coldOrdinaryLaunchIsConsumedExactlyOnce() {
        val opening = opened()
        repeat(3) { assertFalse(opening.resumed()) }
    }

    @Test fun mereResumeAndUnrequestedBackgroundReturnArePassive() {
        val opening = opened()
        opening.paused()
        opening.stopped(changingConfigurations = false)
        assertFalse(opening.resumed())
        assertFalse(opening.resumed())
    }

    @Test fun homeOrRecentsDepartureMakesTheNextResumeAnOpening() {
        val opening = opened()
        opening.userLeaveHint()
        opening.paused()
        opening.stopped(changingConfigurations = false)
        assertTrue(opening.resumed())
        assertFalse(opening.resumed())
    }

    @Test fun settingsAssociationOrExportReturnIsPassiveEvenWithUserLeaveHint() {
        val opening = opened()
        opening.externalActivityLaunched()
        opening.userLeaveHint()
        opening.paused()
        opening.stopped(changingConfigurations = false)
        assertFalse(opening.saveUserOpening())
        assertFalse(opening.resumed())
        // A later, ordinary Home/Recents opening is not permanently disabled.
        opening.userLeaveHint()
        opening.stopped(changingConfigurations = false)
        assertTrue(opening.resumed())
    }

    @Test fun permissionResultCancelsAnyUnconsumedOpening() {
        val opening = AirPodsAppOpening()
        opening.created(restored = false, ordinaryIntent = true)
        opening.externalActivityLaunched() // onRequestPermissionsResult before onPostResume
        assertFalse(opening.resumed())
        assertFalse(opening.resumed())
    }

    @Test fun permissionPauseBeforeFirstResumeCannotPersistAnOrdinaryOpening() {
        val opening = AirPodsAppOpening()
        opening.created(restored = false, ordinaryIntent = true)
        opening.paused()
        assertFalse(opening.saveUserOpening())
        val restored = AirPodsAppOpening()
        restored.created(restored = true, ordinaryIntent = true, savedUserOpening = opening.saveUserOpening())
        assertFalse(restored.resumed())
        assertFalse(opening.resumed())
    }

    @Test fun freshUserIntentAfterExternalActivityCanOpenButPassiveIntentCannot() {
        val opening = opened()
        opening.externalActivityLaunched()
        opening.newIntent(ordinaryIntent = true)
        assertTrue(opening.resumed())
        opening.userLeaveHint()
        opening.stopped(changingConfigurations = false)
        opening.newIntent(ordinaryIntent = false)
        assertFalse(opening.resumed())
    }

    @Test fun configurationAndPassiveProcessRestorationDoNotBecomeOpenings() {
        val opening = opened()
        opening.paused()
        opening.stopped(changingConfigurations = true)
        val recreated = AirPodsAppOpening()
        recreated.created(restored = true, ordinaryIntent = true, savedUserOpening = opening.saveUserOpening())
        assertFalse(recreated.resumed())
        val passive = AirPodsAppOpening()
        passive.created(restored = true, ordinaryIntent = true)
        assertFalse(passive.resumed())
    }

    @Test fun ordinaryBackgroundRestorationKeepsTheOpeningButExternalRestorationDoesNot() {
        val opening = opened()
        opening.userLeaveHint()
        val savedBeforeStop = opening.saveUserOpening()
        opening.stopped(changingConfigurations = false)
        assertTrue(savedBeforeStop)
        assertTrue(opening.saveUserOpening())
        val restored = AirPodsAppOpening()
        restored.created(restored = true, ordinaryIntent = true, savedUserOpening = savedBeforeStop)
        assertTrue(restored.resumed())
        opening.externalActivityLaunched()
        val external = AirPodsAppOpening()
        external.created(restored = true, ordinaryIntent = true, savedUserOpening = opening.saveUserOpening())
        assertFalse(external.resumed())
    }

    @Test fun coldPassiveIntentCannotAcquireAnOpening() {
        val opening = AirPodsAppOpening()
        opening.created(restored = false, ordinaryIntent = false)
        assertFalse(opening.resumed())
    }
}
