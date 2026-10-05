package dev.lelonio.square.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the music light may run at all: the screen on, no battery saver, animations not
 * switched off ([audioLightAllowed]).
 *
 * Told by the system rather than polled, so nothing wakes while the screen is off. Meant to exist
 * once per process, on the application context — every instance is a broadcast receiver and a
 * settings observer — and [close]d only by something that really is going away.
 */
class AudioLightPolicy(context: Context) : AutoCloseable {
    private val context = context.applicationContext
    private val state = MutableStateFlow(audioLightAllowed(this.context))
    val allowed: StateFlow<Boolean> = state.asStateFlow()

    private fun update() {
        state.value = audioLightAllowed(context)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = update()
    }

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = update()
    }

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        ContextCompat.registerReceiver(this.context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        this.context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
    }

    override fun close() {
        runCatching { context.unregisterReceiver(receiver) }
        context.contentResolver.unregisterContentObserver(observer)
    }
}
