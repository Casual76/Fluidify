package dev.pampa.fluidify.wear.system

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class MediaControlsEntryTest {
    @Test fun publicRemoteSessionEntryResolvesToFluidifyAsWell() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent = Intent(PlayerIntents.ACTION_REMOTE_MEDIA_ACTIVITY).setPackage(context.packageName)
        val resolved = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        assertEquals("dev.pampa.fluidify.wear.MainActivity", resolved?.activityInfo?.name)
        assertEquals(PlayerIntents.Request.PLAYER, PlayerIntents.requestOf(intent))
    }
    @Test fun systemPhoneMediaEntryResolvesToFluidifyPlayer() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        // Same package-scoped, implicit action used by Wear OS's MediaSessions app.
        val intent = Intent(PlayerIntents.ACTION_MEDIA_CONTROLS).setPackage(context.packageName)
            .putExtra("extra_controls_launched_from", "launched_from_tap_intent")
        val resolved = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        assertNotNull("Wear OS must find the companion instead of falling back to its player", resolved)
        assertEquals("dev.pampa.fluidify.wear.MainActivity", resolved!!.activityInfo.name)
        assertTrue(resolved.activityInfo.exported)
        assertEquals(PlayerIntents.Request.PLAYER, PlayerIntents.requestOf(intent))
    }

    @Test fun autoLaunchUsesTheSamePlayerEntryWithoutOpeningUpdatesOrUnlike() {
        val intent = Intent(PlayerIntents.ACTION_MEDIA_CONTROLS)
            .putExtra("extra_controls_launched_from", "launched_from_media_control_manager")
        assertEquals(PlayerIntents.Request.PLAYER, PlayerIntents.requestOf(intent))
        assertNull(PlayerIntents.requestOf(Intent(Intent.ACTION_MAIN)))
        assertEquals(PlayerIntents.Request.CONFIRM_UNLIKE,
            PlayerIntents.requestOf(Intent(PlayerIntents.ACTION_CONFIRM_UNLIKE)))
        assertEquals(PlayerIntents.Request.UPDATES,
            PlayerIntents.requestOf(Intent(PlayerIntents.ACTION_UPDATES)))
    }
}
