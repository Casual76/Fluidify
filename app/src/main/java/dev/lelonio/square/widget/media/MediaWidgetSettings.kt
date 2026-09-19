package dev.lelonio.square.widget.media

import android.content.Context
import dev.lelonio.square.SquareApplication
import dev.lelonio.square.data.AppThemeMode

/**
 * The handful of choices the renderer asks about.
 *
 * Ported from Pampa Widgets, where it is a slice of that app's whole settings object. Here it is its
 * own type, and most of it is fixed: that app draws whatever is playing and has to ask which player
 * it is and how much room to give the answer, while this one is the player and the answers are known.
 * What is left is the theme, which follows the app's own, so the home screen and the app are the
 * same app — the whole reason for reading the setting rather than the system's night mode.
 *
 * [AppSettings] keeps its name from the source so the renderer is a diff and not a rewrite.
 */
data class AppSettings(
    val mediaWidgetTheme: MediaWidgetTheme = MediaWidgetTheme.AdaptiveGlass,
    val mediaWidgetArtworkSize: MediaWidgetArtworkSize = MediaWidgetArtworkSize.Balanced,
    /**
     * False, and it stays false.
     *
     * The source line is "which app is this coming from", which in a widget that belongs to the
     * player is a line that says the name of the app it is sitting inside. Room better spent on the
     * title.
     */
    val mediaWidgetShowSource: Boolean = false,
    val mediaWidgetShowArtist: Boolean = true,
    val mediaWidgetAnimatedFeedback: Boolean = true,
    /** Keep the last song on screen when playback stops, rather than emptying the card. */
    val mediaWidgetKeepLastSong: Boolean = true,
    /**
     * Draw the pressed state at once instead of waiting for the player to answer.
     *
     * The player is in this app's own process group and answers in a frame or two, but "a frame or
     * two" across a binder and back out to the launcher is long enough to read as a button that did
     * not work.
     */
    val mediaWidgetInstantControls: Boolean = true,
)

enum class MediaWidgetTheme {
    SamsungGlass,
    AdaptiveGlass,
    LightGlass,
    DarkGlass,
    AlbumColor,
}

enum class MediaWidgetArtworkSize {
    Compact,
    Balanced,
    Large,
}

/**
 * The settings as the app's own theme setting decides them.
 *
 * Read straight off the preferences rather than collected: this runs in a broadcast receiver or a
 * service, in a process that may have started a moment ago for this one update, and there is no flow
 * to have been collecting. Pure black asks for the dark glass rather than a black card, because the
 * widget stands on a wallpaper and not on the app's page — a true-black card on a bright wallpaper
 * is a hole, not a surface.
 */
fun widgetSettings(context: Context): AppSettings {
    val mode = runCatching {
        (context.applicationContext as SquareApplication).preferences.readThemeMode()
    }.getOrDefault(AppThemeMode.System)
    return AppSettings(
        mediaWidgetTheme = when (mode) {
            AppThemeMode.System -> MediaWidgetTheme.AdaptiveGlass
            AppThemeMode.Light -> MediaWidgetTheme.LightGlass
            AppThemeMode.Dark, AppThemeMode.Amoled -> MediaWidgetTheme.DarkGlass
        },
    )
}
