package dev.lelonio.square.playback

import android.content.*
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Event-driven policy: no polling while the screen is off. */
class AudioLightPolicy(private val context: Context) : AutoCloseable {
    private val state = MutableStateFlow(audioLightAllowed(context))
    val allowed = state.asStateFlow()
    private fun update() { state.value = audioLightAllowed(context) }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = update()
    }
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = update()
    }
    init {
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
    }
    override fun close() { context.unregisterReceiver(receiver); context.contentResolver.unregisterContentObserver(observer) }
}
