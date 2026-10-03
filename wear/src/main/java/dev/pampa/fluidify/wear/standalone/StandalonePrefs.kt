package dev.pampa.fluidify.wear.standalone

import android.content.Context
import androidx.core.content.edit
import java.util.UUID

/**
 * The watch's own playback settings.
 *
 * Mobile data starts off, whether or not the watch has a radio: the user asked
 * for it to be offered, in the onboarding and in Altro, never assumed.
 */
class StandalonePrefs(context: Context) {

    private val prefs = context.getSharedPreferences("standalone", Context.MODE_PRIVATE)

    /** Stream and download over the watch's own mobile data. */
    var allowCellular: Boolean
        get() = prefs.getBoolean(KEY_CELLULAR, false)
        set(value) = prefs.edit { putBoolean(KEY_CELLULAR, value) }

    /** Whether the mobile-data question has been put to the listener once. */
    var cellularOffered: Boolean
        get() = prefs.getBoolean(KEY_CELLULAR_OFFERED, false)
        set(value) = prefs.edit { putBoolean(KEY_CELLULAR_OFFERED, value) }

    /** Play through the watch's speaker when no headphones are connected. */
    var speakerAllowed: Boolean
        get() = prefs.getBoolean(KEY_SPEAKER, true)
        set(value) = prefs.edit { putBoolean(KEY_SPEAKER, value) }

    /** Who the watch is signed in as, for Altro. Not a secret; the credential lives with the engine. */
    var username: String?
        get() = prefs.getString(KEY_USERNAME, null)
        set(value) = prefs.edit { putString(KEY_USERNAME, value) }

    /** The client the engine logs in as; must be the one the phone's token was minted for. */
    var clientId: String?
        get() = prefs.getString(KEY_CLIENT, null)
        set(value) = prefs.edit { putString(KEY_CLIENT, value) }

    /**
     * The same Connect device id on every start, so the account sees one watch
     * rather than a new ghost each time the engine comes up.
     */
    val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().replace("-", "")
            .also { prefs.edit { putString(KEY_DEVICE_ID, it) } }

    private companion object {
        const val KEY_CELLULAR = "allow_cellular"
        const val KEY_CELLULAR_OFFERED = "cellular_offered"
        const val KEY_SPEAKER = "speaker_allowed"
        const val KEY_USERNAME = "username"
        const val KEY_CLIENT = "client_id"
        const val KEY_DEVICE_ID = "device_id"
    }
}
