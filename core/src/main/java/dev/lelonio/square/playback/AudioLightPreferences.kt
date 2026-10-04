package dev.lelonio.square.playback

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Local to each device; changing the phone does not disable a subscribed watch. */
class AudioLightPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("audio_light", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(prefs.getBoolean("enabled", true))
    val enabled = state.asStateFlow()
    fun setEnabled(value: Boolean) { state.value = value; prefs.edit().putBoolean("enabled", value).apply() }
}

fun audioLightAllowed(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.let { it.isInteractive && !it.isPowerSaveMode } == true &&
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
