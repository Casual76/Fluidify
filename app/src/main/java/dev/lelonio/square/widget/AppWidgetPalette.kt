package dev.lelonio.square.widget

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.glance.color.ColorProvider as DayNightColorProvider
import androidx.glance.unit.ColorProvider
import dev.antigravity.fluidengine.foundation.AccentMode
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.antigravity.fluidengine.ui.theme.fluidColorScheme
import dev.antigravity.fluidengine.widget.EngineWidgetPalette
import dev.antigravity.fluidengine.widget.engineWidgetPalette
import dev.lelonio.square.SquareApplication
import dev.lelonio.square.data.AppThemeMode
import dev.lelonio.square.ui.theme.Amethyst

/**
 * The widget's colours: the engine's palette, plus the three this app adds on top of it.
 *
 * The three exist because a Fluidify widget is not a card with a cover in it — it is a cover with
 * the words standing on it, the same arrangement every page of the app uses. That needs a veil to
 * hold the ink, a quieter one for where the buttons sit, and a tile wash for the frame around the
 * cover itself. None of them are the engine's business: the engine's palette is for widgets that
 * stand on the app's background, and this one stands on a photograph.
 */
@Immutable
data class AppWidgetPalette(
    val engine: EngineWidgetPalette,
    /**
     * What the blurred cover is taken under before anything is written on it.
     *
     * Heavy, and it has to be. A cover can be anything — the palette cannot be built for the page
     * when the page is a photograph nobody chose — so the veil is what makes the ink a safe bet
     * whatever is playing. Heavier on the light side for the reason the app's own backdrop gives:
     * the bright parts of a photograph swallow dark letters far harder than a dark veil hides light
     * ones.
     */
    val coverVeil: ColorProvider,
    /** The same, for the strip the buttons sit on, where a little more separation is worth it. */
    val controlVeil: ColorProvider,
    /** The frame around the cover, for the moment before there is a cover. */
    val tile: ColorProvider,
)

/**
 * The widget's colours, out of the same settings the app's own theme is built from.
 *
 * The one thing that keeps the home screen and the app the same app. It is deliberately not a copy
 * of anything in `Theme.kt`: what goes in is the [EngineSettings] the theme gets, and both launcher
 * themes are resolved from it in advance — a widget is handed to the launcher as finished views, so
 * nothing can be decided at draw time and the day/night pair has to exist up front.
 *
 * The theme setting is read straight off the preferences rather than collected: this runs inside
 * `provideGlance`, in a process that may have started a moment ago for this one composition, and
 * there is no flow to have been collecting.
 */
@Composable
fun rememberAppWidgetPalette(context: Context): AppWidgetPalette {
    val mode = remember(context) {
        runCatching {
            (context.applicationContext as SquareApplication).preferences.readThemeMode()
        }.getOrDefault(AppThemeMode.System)
    }
    return remember(context, mode) {
        val settings = EngineSettings(
            themeMode = when (mode) {
                AppThemeMode.System -> ThemeMode.SYSTEM
                AppThemeMode.Light -> ThemeMode.LIGHT
                AppThemeMode.Dark -> ThemeMode.DARK
                AppThemeMode.Amoled -> ThemeMode.AMOLED
            },
            accentMode = AccentMode.BRAND,
            dynamicColorEnabled = false,
        )
        // The same two schemes the engine's palette is built from, resolved again here because the
        // palette hands back providers and a provider cannot be read back into a colour.
        val light = scheme(settings, isDark = false)
        val dark = scheme(settings, isDark = true)

        AppWidgetPalette(
            engine = engineWidgetPalette(context, settings, Amethyst),
            coverVeil = DayNightColorProvider(
                day = light.background.copy(alpha = LightVeil),
                night = dark.background.copy(alpha = DarkVeil),
            ),
            controlVeil = DayNightColorProvider(
                day = light.background.copy(alpha = LightControlVeil),
                night = dark.background.copy(alpha = DarkControlVeil),
            ),
            tile = DayNightColorProvider(
                day = light.onSurface.copy(alpha = TileWash),
                night = dark.onSurface.copy(alpha = TileWash),
            ),
        )
    }
}

private fun scheme(settings: EngineSettings, isDark: Boolean): ColorScheme {
    val resolvedDark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }
    return fluidColorScheme(settings = settings, isDark = resolvedDark, brand = Amethyst)
}

/** See [AppWidgetPalette.coverVeil]. */
private const val LightVeil = 0.80f
private const val DarkVeil = 0.68f
private const val LightControlVeil = 0.55f
private const val DarkControlVeil = 0.42f
private const val TileWash = 0.10f

/** A colour with an alpha, for Glance, which has no `copy`. */
private fun Color.copy(alpha: Float): Color = Color(red, green, blue, alpha)
