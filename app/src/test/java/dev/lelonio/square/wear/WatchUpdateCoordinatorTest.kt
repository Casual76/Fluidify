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
    @Test fun unrelatedWatchCannotChangeProgressOrRemoveTheCachedApk() = runTest(UnconfinedTestDispatcher()) {
        setup()
        val updates = WatchUpdateCoordinator(context, WearLink(context), backgroundScope)
        updates.onStatus("other", UpdateStatus(UpdatePhase.INSTALLED, "9.0.0"))
        assertTrue(updates.state.value is WatchUpdateCoordinator.State.Idle)
        assertTrue(apk.exists())
    }
}
