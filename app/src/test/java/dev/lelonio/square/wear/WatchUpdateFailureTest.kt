package dev.lelonio.square.wear

import dev.lelonio.square.R
import org.junit.Assert.assertEquals
import org.junit.Test

/** What each reason the watch or the phone can give for a failed update is turned into. */
class WatchUpdateFailureTest {

    @Test
    fun anOfferTheWatchNeverAnsweredSaysSo() {
        assertEquals(R.string.watch_update_err_expired, watchUpdateFailureRes("offer-expired"))
    }

    @Test
    fun anInterruptedInstallationHasItsOwnSentence() {
        assertEquals(R.string.watch_update_err_interrupted, watchUpdateFailureRes("install-interrupted"))
        // And an ordinary install failure still has the one that says to open the watch's app.
        assertEquals(R.string.watch_update_err_install, watchUpdateFailureRes("install"))
    }

    @Test
    fun aReasonNobodyKnowsGetsTheGenericLineAndNeverItself() {
        assertEquals(R.string.watch_update_failed, watchUpdateFailureRes("something-added-later"))
    }

    @Test
    fun theWordTheWatchUsesForAutomaticUpdatesOffIsStable() {
        // It is a state of its own, not a failure (see WatchUpdateCoordinator.State.AutoUpdateOff),
        // and the watch's own word for it is what the coordinator matches.
        assertEquals("auto-update-off", WatchUpdateCoordinator.REASON_AUTO_OFF)
    }
}
