package dev.lelonio.square.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.antigravity.fluidengine.foundation.AccentMode
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.antigravity.fluidengine.widget.EngineWidgetPalette
import dev.antigravity.fluidengine.widget.engineWidgetPalette
import dev.lelonio.square.SquareApplication
import dev.lelonio.square.data.AppThemeMode
import dev.lelonio.square.ui.theme.Amethyst

/**
 * The widget's colours, out of the same settings the app's own theme is built from.
 *
 * The one line that keeps the home screen and the app the same app. It is deliberately not a copy of
 * anything in `Theme.kt`: what goes in is the [EngineSettings] the theme gets, and the engine
 * resolves both launcher themes from it in advance — a widget is handed to the launcher as finished
 * views, so nothing can be decided at draw time and the day/night pair has to exist up front.
 *
 * The theme setting is read straight off the preferences rather than collected: this runs inside
 * `provideGlance`, in a process that may have started a moment ago for this one composition, and
 * there is no flow to have been collecting.
 */
@Composable
fun rememberAppWidgetPalette(context: Context): EngineWidgetPalette {
    val mode = remember(context) {
        runCatching {
            (context.applicationContext as SquareApplication).preferences.readThemeMode()
        }.getOrDefault(AppThemeMode.System)
    }
    return remember(context, mode) {
        engineWidgetPalette(
            context = context,
            settings = EngineSettings(
                themeMode = when (mode) {
                    AppThemeMode.System -> ThemeMode.SYSTEM
                    AppThemeMode.Light -> ThemeMode.LIGHT
                    AppThemeMode.Dark -> ThemeMode.DARK
                    AppThemeMode.Amoled -> ThemeMode.AMOLED
                },
                accentMode = AccentMode.BRAND,
                dynamicColorEnabled = false,
            ),
            brand = Amethyst,
        )
    }
}
