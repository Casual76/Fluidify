package dev.pampa.fluidify.wear.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pampa.fluidify.wear.protocol.UpdatePhase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class WatchUpdaterTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("watch_update", Context.MODE_PRIVATE)
    private val ready get() = File(context.filesDir, "watch-update/ready.apk")
    @Before fun setup() {
        prefs.edit().clear().putString("ready_version", "9.0.0").putString("ready_sha", "a".repeat(64)).putInt("session", 7).commit()
        ready.parentFile!!.mkdirs()
        ready.writeText("cached verified bytes")
    }
    private fun result(code: Int) = Intent().putExtra("session", 7).putExtra(PackageInstaller.EXTRA_STATUS, code)

    @Test fun confirmationSurvivesRecreationAndDoesNotLaunchFromTheBackground() = runTest {
        val updater = WatchUpdater(context)
        updater.onInstallResult(result(PackageInstaller.STATUS_PENDING_USER_ACTION)
            .putExtra(Intent.EXTRA_INTENT, Intent("system-confirmation")))
        assertEquals(UpdatePhase.AWAITING_CONFIRMATION, updater.status.value!!.phase)
        assertNotNull(WatchInstallConfirmationActivity.find(context, 7))
        val reopened = WatchUpdater(context)
        assertEquals(UpdatePhase.AWAITING_CONFIRMATION, reopened.status.value!!.phase)
        assertTrue(reopened.canRetry)
        val notification = context.getSystemService(NotificationManager::class.java).activeNotifications
            .first { it.id == 0x5743 }.notification
        assertNotNull(notification.contentIntent)
        assertNull(org.robolectric.Shadows.shadowOf(context as android.app.Application).nextStartedActivity)
    }
    @Test fun failedInstallationRetainsTheApkForARetryWithoutBluetooth() = runTest {
        val bytes = ready.readBytes()
        WatchUpdater(context).onInstallResult(result(PackageInstaller.STATUS_FAILURE_ABORTED))
        val reopened = WatchUpdater(context)
        assertEquals(UpdatePhase.FAILED, reopened.status.value!!.phase)
        assertTrue(reopened.canRetry)
        assertArrayEquals(bytes, ready.readBytes())
    }
    @Test fun onlySuccessfulInstallationRemovesTheCachedApk() = runTest {
        WatchUpdater(context).onInstallResult(result(PackageInstaller.STATUS_SUCCESS))
        assertFalse(ready.exists())
        assertFalse(WatchUpdater(context).canRetry)
    }
    @Test fun unrelatedSessionCannotDeleteOrChangeTheUpdate() = runTest {
        val updater = WatchUpdater(context)
        updater.onInstallResult(result(PackageInstaller.STATUS_SUCCESS).putExtra("session", 99))
        assertTrue(ready.exists())
        assertNull(updater.status.value)
    }
}
