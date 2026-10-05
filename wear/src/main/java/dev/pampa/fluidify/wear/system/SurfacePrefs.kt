package dev.pampa.fluidify.wear.system

import dev.pampa.fluidify.wear.protocol.logic.NowBarMode
import dev.pampa.fluidify.wear.protocol.logic.NowBarPolicy

import android.content.Context
import androidx.core.content.edit

/**
 * The switches that decide how loud Fluidify is outside its own screens.
 *
 * The entry on the watch face defaults to "only when the system shows nothing",
 * which is the one setting that never puts two Fluidifys there; leaving the
 * phone's other notifications alone is what the system does with no watch app
 * at all (see [Bridging]).
 */
class SurfacePrefs(context: Context) {

    private val prefs = context.getSharedPreferences("surfaces", Context.MODE_PRIVATE)

    /**
     * When Fluidify puts its own entry on the watch face for phone playback; see [NowBarPolicy].
     * Watches that had the old on/off switch off keep it off.
     */
    var nowBar: NowBarMode
        get() = prefs.getString(KEY_NOW_BAR, null)?.let { runCatching { NowBarMode.valueOf(it) }.getOrNull() }
            ?: if (prefs.getBoolean(KEY_ONGOING, true)) NowBarMode.AUTO else NowBarMode.NEVER
        set(value) = prefs.edit { putString(KEY_NOW_BAR, value.name) }

    /**
     * The developer experiment: the phone keeps its media notification to itself and the watch shows
     * a media notification of its own over a mirror of the phone's session. See [MirrorNowPlaying].
     */
    var mirrorNowBar: Boolean
        get() = prefs.getBoolean(KEY_MIRROR, false)
        set(value) = prefs.edit { putBoolean(KEY_MIRROR, value) }

    /** The phone's Fluidify notifications mirrored on the watch, as Wear does by default. */
    var phoneNotifications: Boolean
        get() = prefs.getBoolean(KEY_BRIDGING, true)
        set(value) = prefs.edit { putBoolean(KEY_BRIDGING, value) }

    /** What the tile and the complication last showed of the phone's playback, so an unchanged state asks for nothing. */
    internal var lastPhoneSignature: String?
        get() = prefs.getString(KEY_PHONE_SIGNATURE, null)
        set(value) = prefs.edit { putString(KEY_PHONE_SIGNATURE, value) }

    /** The same, for the watch's own playback. */
    internal var lastWatchSignature: String?
        get() = prefs.getString(KEY_WATCH_SIGNATURE, null)
        set(value) = prefs.edit { putString(KEY_WATCH_SIGNATURE, value) }

    private companion object {
        const val KEY_ONGOING = "ongoing_icon"
        const val KEY_NOW_BAR = "now_bar"
        const val KEY_MIRROR = "mirror_now_bar"
        const val KEY_BRIDGING = "phone_notifications"
        const val KEY_PHONE_SIGNATURE = "phone_signature"
        const val KEY_WATCH_SIGNATURE = "watch_signature"
    }
}
