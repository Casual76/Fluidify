package dev.pampa.fluidify.wear.system

import android.content.Context
import androidx.core.content.edit

/**
 * The two switches that decide how loud Fluidify is outside its own screens.
 *
 * Both default to on: the icon on the watch face is how Spotify's watch app
 * behaves, and leaving the phone's notifications alone is what the system does
 * with no watch app at all. Whether the second one should start off is a call
 * to make on the watch itself (see [Bridging]).
 */
class SurfacePrefs(context: Context) {

    private val prefs = context.getSharedPreferences("surfaces", Context.MODE_PRIVATE)

    /** The icon on the watch face (Wear's ongoing activity) while the phone plays. */
    var ongoingIcon: Boolean
        get() = prefs.getBoolean(KEY_ONGOING, true)
        set(value) = prefs.edit { putBoolean(KEY_ONGOING, value) }

    /** The phone's Fluidify notifications mirrored on the watch, as Wear does by default. */
    var phoneNotifications: Boolean
        get() = prefs.getBoolean(KEY_BRIDGING, true)
        set(value) = prefs.edit { putBoolean(KEY_BRIDGING, value) }

    /** What the tile and the complication last showed, so an unchanged state asks for nothing. */
    internal var lastSignature: String?
        get() = prefs.getString(KEY_SIGNATURE, null)
        set(value) = prefs.edit { putString(KEY_SIGNATURE, value) }

    private companion object {
        const val KEY_ONGOING = "ongoing_icon"
        const val KEY_BRIDGING = "phone_notifications"
        const val KEY_SIGNATURE = "surface_signature"
    }
}
