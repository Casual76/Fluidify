package dev.lelonio.square.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Light, dark, or whatever the phone is doing.
 *
 * Stored by its [key] rather than by ordinal, so reordering this enum — or
 * dropping a value — cannot silently turn somebody's setting into a different
 * one.
 */
enum class AppThemeMode(val key: String) {
    System("system"),
    Light("light"),
    Dark("dark"),

    /**
     * Dark, with the page floor at true black.
     *
     * Not a fourth palette but the dark one standing on nothing: on an OLED panel a black pixel is
     * an *unlit* pixel, so the page stops being a very dark grey and starts being the edge of the
     * screen. Everything glass keeps working, and keeps working better — a translucent film reads
     * more clearly against black than against near-black, which is the whole trick.
     */
    Amoled("amoled"),
}

/**
 * Small UI choices that should survive the screen being left.
 *
 * Kept as strings rather than as an enum so this module does not have to know
 * about the screens that use it, and so a value written by an older version that
 * no longer exists reads back as "not set" instead of crashing.
 */
class PreferencesStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val _trackSort = MutableStateFlow(prefs.getString(KEY_TRACK_SORT, null))

    /** How the detail screen's track list is ordered; null until first chosen. */
    val trackSort: StateFlow<String?> = _trackSort.asStateFlow()

    fun setTrackSort(value: String) {
        _trackSort.value = value
        prefs.edit().putString(KEY_TRACK_SORT, value).apply()
    }

    private val _trackSortDescending = MutableStateFlow(prefs.getBoolean(KEY_TRACK_DESC, false))

    /** Whether that order runs backwards; the direction is part of the sort. */
    val trackSortDescending: StateFlow<Boolean> = _trackSortDescending.asStateFlow()

    fun setTrackSortDescending(value: Boolean) {
        _trackSortDescending.value = value
        prefs.edit().putBoolean(KEY_TRACK_DESC, value).apply()
    }

    /**
     * This phone's Connect id, made once and kept for good.
     *
     * A uuid rather than anything drawn from the hardware: it identifies an
     * install to one account's device list and nothing else, and there is no
     * reason for it to survive the app being removed.
     */
    fun deviceId(): String = prefs.getString(KEY_DEVICE_ID, null) ?: run {
        val id = java.util.UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        id
    }

    private val _onboarded = MutableStateFlow(prefs.getBoolean(KEY_ONBOARDED, false))

    /**
     * Whether the welcome tutorial has been finished at least once.
     *
     * Kept apart from "is the app configured": the setup can be undone later —
     * logging out, unlinking the Web API application — and re-running the whole
     * tutorial at that point would be answering a question nobody asked. The
     * tutorial stays reachable from the settings instead.
     */
    val onboarded: StateFlow<Boolean> = _onboarded.asStateFlow()

    fun setOnboarded(value: Boolean) {
        _onboarded.value = value
        prefs.edit().putBoolean(KEY_ONBOARDED, value).apply()
    }

    /**
     * Whether the player was open when the app was last left.
     *
     * Read straight rather than as a flow: the answer decides what the *first*
     * composed frame is, so it has to be available before anything is drawn. A
     * value that arrives a moment later is what produced the flash of the home
     * page this replaced.
     */
    fun playerWasOpen(): Boolean = prefs.getBoolean(KEY_PLAYER_OPEN, false)

    fun setPlayerOpen(value: Boolean) {
        prefs.edit().putBoolean(KEY_PLAYER_OPEN, value).apply()
    }

    /**
     * Which size the now-playing panel was left at on a wide window: 0 beside
     * the page, 1 the height of it, 2 with the words and the queue opened out.
     *
     * A layout choice rather than a place you went, so it is kept the way the
     * player's own openness is — read straight, for the first frame.
     */
    fun panelReach(): Int = prefs.getInt(KEY_PANEL_REACH, 0)

    fun setPanelReach(value: Int) {
        prefs.edit().putInt(KEY_PANEL_REACH, value).apply()
    }

    private val _canvasEnabled = MutableStateFlow(prefs.getBoolean(KEY_CANVAS, true))

    /**
     * Whether a track's Canvas is fetched and shown behind the player.
     *
     * On by default — it is the one thing the player has that a list of songs
     * does not. Off is for data, for battery, and for anyone who would rather
     * look at the cover.
     */
    val canvasEnabled: StateFlow<Boolean> = _canvasEnabled.asStateFlow()

    fun setCanvasEnabled(value: Boolean) {
        _canvasEnabled.value = value
        prefs.edit().putBoolean(KEY_CANVAS, value).apply()
    }

    /**
     * The name and the picture the account was last seen wearing.
     *
     * Kept because the library can come up without a session — see
     * `MainViewModel.offlineLibrary` — and the account is still the same
     * account. Blanking the header on every bad handshake is what made the
     * profile look like it went missing at random, which is exactly what it
     * looked like from the outside.
     *
     * Read straight, like the player's own last pose: it decides what the first
     * composed frame says.
     */
    fun lastProfile(): Pair<String, String?>? {
        val name = prefs.getString(KEY_PROFILE_NAME, null) ?: return null
        return name to prefs.getString(KEY_PROFILE_AVATAR, null)
    }

    fun setLastProfile(name: String, avatarUrl: String?) {
        prefs.edit()
            .putString(KEY_PROFILE_NAME, name)
            .putString(KEY_PROFILE_AVATAR, avatarUrl)
            .apply()
    }

    fun clearLastProfile() {
        prefs.edit().remove(KEY_PROFILE_NAME).remove(KEY_PROFILE_AVATAR).apply()
    }

    /** When the manifest was last asked about a newer release. See [UpdateChecker]. */
    fun lastUpdateCheck(): Long = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L)

    fun setLastUpdateCheck(value: Long) {
        prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, value).apply()
    }

    /**
     * The version the user has already been told about and dismissed.
     *
     * One version rather than a set: releases only move forward, so a newer one
     * than the dismissed one is news again, and an older one cannot appear.
     */
    fun skippedUpdate(): String? = prefs.getString(KEY_SKIPPED_UPDATE, null)

    fun setSkippedUpdate(value: String) {
        prefs.edit().putString(KEY_SKIPPED_UPDATE, value).apply()
    }

    private val _showLocalFiles = MutableStateFlow(prefs.getBoolean(KEY_LOCAL_FILES, false))

    /**
     * Whether the phone's own music gets a shelf in the library.
     *
     * Off unless asked for. It used to be unconditional, on the reasoning that a
     * file on this phone is there whichever service is signed in — true, but it
     * put a folder at the head of every library for the many listeners who have
     * no local music at all, and made them look at it every time. A shelf that
     * is empty for most people is a setting, not a default.
     */
    val showLocalFiles: StateFlow<Boolean> = _showLocalFiles.asStateFlow()

    fun setShowLocalFiles(value: Boolean) {
        _showLocalFiles.value = value
        prefs.edit().putBoolean(KEY_LOCAL_FILES, value).apply()
    }

    private val _themeMode = MutableStateFlow(readThemeMode())

    /** Which side the app paints on, and whether it is the phone's decision. */
    val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(value: AppThemeMode) {
        _themeMode.value = value
        prefs.edit().putString(KEY_THEME, value.key).apply()
    }

    /**
     * Read straight, without the flow.
     *
     * The theme has to be decided on the very first composition or the app opens
     * on the wrong side and corrects itself a frame later, which is a flash of
     * the other colour on every cold start. Same reason [playerWasOpen] exists.
     */
    fun readThemeMode(): AppThemeMode =
        AppThemeMode.entries.firstOrNull { it.key == prefs.getString(KEY_THEME, null) }
            ?: AppThemeMode.System

    private companion object {
        const val FILE_NAME = "square_preferences"
        const val KEY_THEME = "theme_mode"
        const val KEY_LOCAL_FILES = "show_local_files"
        const val KEY_CANVAS = "canvas"
        const val KEY_TRACK_SORT = "track_sort"
        const val KEY_TRACK_DESC = "track_sort_descending"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_ONBOARDED = "onboarded"
        const val KEY_PLAYER_OPEN = "player_open"
        const val KEY_PANEL_REACH = "panel_reach"
        const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        const val KEY_SKIPPED_UPDATE = "skipped_update"
        const val KEY_PROFILE_NAME = "profile_name"
        const val KEY_PROFILE_AVATAR = "profile_avatar"
    }
}
