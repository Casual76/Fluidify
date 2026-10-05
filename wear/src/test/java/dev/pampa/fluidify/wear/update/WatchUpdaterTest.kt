package dev.pampa.fluidify.wear.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pampa.fluidify.wear.protocol.UpdateCheckReply
import dev.pampa.fluidify.wear.protocol.UpdatePhase
import dev.pampa.fluidify.wear.protocol.UpdateStatus
import dev.pampa.fluidify.wear.protocol.WearCodec
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
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
    @Test fun anInstallHeldBackForTheMusicIsStillWaitingAfterTheProcessDies() = runTest {
        val waiting = UpdateStatus(UpdatePhase.ACCEPT, "9.0.0", reason = UpdateStatus.REASON_WAITING_PLAYBACK)
        prefs.edit().putString("status", WearCodec.json.encodeToString(UpdateStatus.serializer(), waiting)).commit()
        // The music is still on, so the new process goes on waiting rather than calling it a failure.
        val reopened = WatchUpdater(context, playingLocally = { true })
        assertEquals(waiting, reopened.status.value)
        assertTrue(ready.exists())
    }
    @Test fun aPlainAcceptThatWasInterruptedIsStillAFailure() = runTest {
        val accepted = UpdateStatus(UpdatePhase.ACCEPT, "9.0.0")
        prefs.edit().putString("status", WearCodec.json.encodeToString(UpdateStatus.serializer(), accepted)).commit()
        val reopened = WatchUpdater(context)
        assertEquals(UpdatePhase.FAILED, reopened.status.value!!.phase)
        assertEquals("transfer-interrupted", reopened.status.value!!.reason)
    }
    @Test fun theCheckRowTakesTheNewsOfAnAnswerOnlyWhenAskedAndIgnoresStrays() = runTest {
        val updater = WatchUpdater(context)
        updater.onCheckReply(UpdateCheckReply(UpdateCheckReply.UPDATE, "9.1.0"))
        assertEquals(WatchUpdater.Check.Idle, updater.check.value)
    }
    @Test fun aRequestThatDoesNotGoIsAPhoneNobodyCanReach() = kotlinx.coroutines.runBlocking {
        val updater = WatchUpdater(context)
        updater.requestCheck { false }
        val settled = kotlinx.coroutines.withTimeoutOrNull(2_000) {
            updater.check.first { it == WatchUpdater.Check.Unreachable }
        }
        assertEquals(WatchUpdater.Check.Unreachable, settled)
    }
    @Test fun whatTheJustInstalledVersionBroughtIsKeptUntilItIsRead() = runTest {
        prefs.edit().putString("news_version", dev.pampa.fluidify.wear.BuildConfig.VERSION_NAME).putString("news", "Tutto nuovo").commit()
        val updater = WatchUpdater(context)
        assertEquals("Tutto nuovo", updater.news.value!!.notes)
        updater.dismissNews()
        assertNull(updater.news.value)
        assertNull(WatchUpdater(context).news.value)
    }
    @Test fun newsForAnotherVersionThanTheRunningOneIsStale() = runTest {
        prefs.edit().putString("news_version", "0.0.1").putString("news", "Vecchie notizie").commit()
        assertNull(WatchUpdater(context).news.value)
    }
    @Test fun unrelatedSessionCannotDeleteOrChangeTheUpdate() = runTest {
        val updater = WatchUpdater(context)
        updater.onInstallResult(result(PackageInstaller.STATUS_SUCCESS).putExtra("session", 99))
        assertTrue(ready.exists())
        assertNull(updater.status.value)
    }
}
