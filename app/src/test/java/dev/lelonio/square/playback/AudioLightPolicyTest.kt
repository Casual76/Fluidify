package dev.lelonio.square.playback

import android.app.Application
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk=[35],application=Application::class)
class AudioLightPolicyTest {
    @Test fun screenPowerSaveAndReducedMotionSuppressAnalysisAndPreferencesPersistSeparatelyFromGlass() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val power=shadowOf(context.getSystemService(PowerManager::class.java))
        power.setIsInteractive(true); power.setIsPowerSaveMode(false)
        Settings.Global.putFloat(context.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)
        assertTrue(audioLightAllowed(context))
        power.setIsInteractive(false); assertFalse(audioLightAllowed(context)); power.setIsInteractive(true)
        power.setIsPowerSaveMode(true); assertFalse(audioLightAllowed(context)); power.setIsPowerSaveMode(false)
        Settings.Global.putFloat(context.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,0f); assertFalse(audioLightAllowed(context))
        val prefs=AudioLightPreferences(context); assertTrue(prefs.enabled.value)
        prefs.setEnabled(false); assertFalse(AudioLightPreferences(context).enabled.value)
        prefs.setEnabled(true); assertTrue(AudioLightPreferences(context).enabled.value)
    }
}
