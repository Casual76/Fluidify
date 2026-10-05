package dev.lelonio.square.wear

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pampa.fluidify.wear.protocol.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WatchUpdateCoordinatorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("square_watch", Context.MODE_PRIVATE)
    private val apk get() = File(context.filesDir, "watch-ready.apk")
    private fun setup() {
        apk.writeText("verified cached APK")
        val offer = UpdateOffer("9.0.0", sizeBytes = apk.length(), sha256 = "a".repeat(64))
        prefs.edit().clear().putString("offer", WearCodec.json.encodeToString(UpdateOffer.serializer(), offer))
            .putString("offer_node", "watch").putLong("offer_at", System.currentTimeMillis()).commit()
    }
    @Test fun receivedProgressSurvivesSettingsRecreationAndLateProgressCannotUndoInstallation() = runTest(UnconfinedTestDispatcher()) {
        setup()
        val updates = WatchUpdateCoordinator(context, WearLink(context), backgroundScope)
        updates.onStatus("watch", UpdateStatus(UpdatePhase.RECEIVING, "9.0.0", 0.42f))
        assertEquals(0.42f, (updates.state.value as WatchUpdateCoordinator.State.Sending).progress!!, 0f)
        updates.onStatus("watch", UpdateStatus(UpdatePhase.RECEIVING, "9.0.0", 0.1f))
        assertEquals(0.42f, (updates.state.value as WatchUpdateCoordinator.State.Sending).progress!!, 0f)
        updates.onStatus("watch", UpdateStatus(UpdatePhase.INSTALLING, "9.0.0"))
        updates.onStatus("watch", UpdateStatus(UpdatePhase.RECEIVING, "9.0.0", 0.99f))
        assertTrue(updates.state.value is WatchUpdateCoordinator.State.Installing)
    }
    @Test fun failedInstallKeepsThePhoneCopyAndOnlySuccessRemovesIt() = runTest(UnconfinedTestDispatcher()) {
        setup()
        val updates = WatchUpdateCoordinator(context, WearLink(context), backgroundScope)
        updates.onStatus("watch", UpdateStatus(UpdatePhase.FAILED, "9.0.0", reason = "install"))
        assertTrue(apk.isFile)
        updates.onStatus("watch", UpdateStatus(UpdatePhase.INSTALLED, "9.0.0"))
        assertFalse(apk.exists())
    }
    @Test fun anAcceptThatWaitsForPlaybackIsNotARequestToSendAgain() = runTest(UnconfinedTestDispatcher()) {
        setup()
        val updates = WatchUpdateCoordinator(context, WearLink(context), backgroundScope)
        updates.onStatus("watch", UpdateStatus(UpdatePhase.ACCEPT, "9.0.0", reason = UpdateStatus.REASON_WAITING_PLAYBACK))
        // The APK is the watch's already: this is a wait, not a transfer to start (which would
        // enqueue the send worker, which this test has no WorkManager for).
        assertEquals(WatchUpdateCoordinator.State.WaitingForPlayback("9.0.0"), updates.state.value)
        assertTrue(apk.isFile)
    }
    @Test fun aDeclineBecauseAutomaticUpdatesAreOffIsNotAFailure() = runTest(UnconfinedTestDispatcher()) {
        setup()
        val updates = WatchUpdateCoordinator(context, WearLink(context), backgroundScope)
        updates.onStatus("watch", UpdateStatus(UpdatePhase.DECLINE, "9.0.0", reason = "auto-update-off"))
        assertEquals(WatchUpdateCoordinator.State.AutoUpdateOff, updates.state.value)
        updates.onStatus("watch", UpdateStatus(UpdatePhase.DECLINE, "9.0.0", reason = "busy"))
        assertEquals(WatchUpdateCoordinator.State.Failed("busy"), updates.state.value)
    }
    @Test fun installedCarriesTheNotesAndLeavesAnOutcomeThatSurvivesARestart() = runTest(UnconfinedTestDispatcher()) {
        setup()
        prefs.edit().putString("offer_notes", "Novita: tutto nuovo").commit()
        val updates = WatchUpdateCoordinator(context, WearLink(context), backgroundScope)
        updates.onStatus("watch", UpdateStatus(UpdatePhase.INSTALLED, "9.0.0"))
        assertEquals(WatchUpdateCoordinator.State.Installed("9.0.0", "Novita: tutto nuovo"), updates.state.value)
        // A new process: the state is Idle again, the outcome is not.
        val reborn = WatchUpdateCoordinator(context, WearLink(context), backgroundScope)
        assertTrue(reborn.state.value is WatchUpdateCoordinator.State.Idle)
        val outcome = reborn.lastOutcome()!!
        assertEquals("9.0.0", outcome.version)
        assertEquals("Novita: tutto nuovo", outcome.changelog)
        assertTrue(outcome.atMs > 0)
    }
    @Test fun changingTheChannelForgetsWhatWasLearnedOnTheOtherOne() = runTest(UnconfinedTestDispatcher()) {
        setup()
        prefs.edit().putLong("checked_at", System.currentTimeMillis())
            .putString("outcome_version", "1.6.4").putLong("outcome_at", 1L).commit()
        val updates = WatchUpdateCoordinator(context, WearLink(context), backgroundScope)
        updates.onChannelChanged()
        // The gate is open again, so the next hello or page open checks at once.
        assertFalse(prefs.contains("checked_at"))
        assertNull(updates.lastOutcome())
        assertTrue(updates.state.value is WatchUpdateCoordinator.State.Idle)
    }
    @Test fun unrelatedWatchCannotChangeProgressOrRemoveTheCachedApk() = runTest(UnconfinedTestDispatcher()) {
        setup()
        val updates = WatchUpdateCoordinator(context, WearLink(context), backgroundScope)
        updates.onStatus("other", UpdateStatus(UpdatePhase.INSTALLED, "9.0.0"))
        assertTrue(updates.state.value is WatchUpdateCoordinator.State.Idle)
        assertTrue(apk.exists())
    }
}
