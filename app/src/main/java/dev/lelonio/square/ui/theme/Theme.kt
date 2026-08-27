package dev.lelonio.square.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import dev.antigravity.fluidengine.foundation.AccentMode
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode as EngineThemeMode
import dev.antigravity.fluidengine.ui.theme.AccentPoles
import dev.antigravity.fluidengine.ui.theme.AccentPreset
import dev.antigravity.fluidengine.ui.theme.FluidTheme

/**
 * Fluidify's own colour: amethyst, in a light and a dark cut.
 *
 * With poles, and they are not optional here: violet sits next to the palette's
 * indigo pole, and without them the derived secondary family collapses into the
 * primary — the exact case the engine's colour tests exercise under this name.
 */
private val Amethyst = AccentPreset(
    name = "amethyst",
    label = "Ametista",
    light = Color(0xFF9966CC),
    dark = Color(0xFFB88CE8),
    poles = AccentPoles(
        secondaryLight = Color(0xFF007AFF),
        secondaryDark = Color(0xFF0A84FF),
        tertiaryLight = Color(0xFFFF2D55),
        tertiaryDark = Color(0xFFFF375F),
        secondaryBlend = 0.45f,
        tertiaryBlend = 0.40f,
    ),
)

/**
 * Glass over the album art, keyed to amethyst.
 *
 * The engine's [FluidTheme] provides the ladder — accent scale, continuous
 * shapes, the motion scheme, the typography — and what this adds on top is the
 * one thing this app's design insists on: the page *is* the artwork under a
 * dark wash, so the structural roles stay the translucent films every screen
 * here is drawn with, and `background` stays transparent for the backdrop to
 * show through.
 *
 * The accent is the app's own. It used to follow the playing artwork
 * everywhere; now amethyst is the identity — chrome, buttons, the light — and
 * the artwork's colour lives where the artwork is: pass [seed] and the content
 * is wrapped in [ArtworkAccentTheme], which the player and the detail pages do.
 */
@Composable
fun SquareTheme(
    /** Dominant colour of the content on screen, or null to stay amethyst. */
    seed: Color? = null,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    FluidTheme(
        settings = EngineSettings(
            // Dark on purpose, whatever the system says: see the note above —
            // the backdrop is artwork under a dark wash, and there is no light
            // half to switch to.
            themeMode = EngineThemeMode.DARK,
            accentMode = AccentMode.BRAND,
            dynamicColorEnabled = false,
        ),
        brand = Amethyst,
    ) {
        val engine = MaterialTheme.colorScheme
        // Keyed on the engine scheme: rebuilding a ColorScheme allocates dozens
        // of colours and invalidates every composable that reads MaterialTheme.
        val scheme = remember(engine) {
            engine.copy(
                onPrimary = Color(0xFF0B0D10),
                primaryContainer = GlassFill,
                onPrimaryContainer = Ink,
                secondary = InkDim,
                // The backdrop shows through this; an opaque page colour would
                // cover it.
                background = Color.Transparent,
                onBackground = Ink,
                // Glass, not a card: a translucent white film is what every
                // surface in this design is made of, and the refraction on top
                // comes from the backdrop rather than from the colour.
                surface = GlassFill,
                onSurface = Ink,
                surfaceVariant = GlassFillStrong,
                onSurfaceVariant = InkDim,
                outlineVariant = Color.White.copy(alpha = 0.16f),
            )
        }

        // System bar icons have to flip with the theme; left alone they are
        // drawn for the system's own theme and disappear against ours.
        val view = LocalView.current
        if (!view.isInEditMode) {
            SideEffect {
                val window = (view.context as android.app.Activity).window
                // Always light icons: the bars sit over the darkened artwork,
                // not over the system's idea of a background.
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
        }

        MaterialTheme(colorScheme = scheme) {
            if (seed == null) content() else ArtworkAccentTheme(seed, content)
        }
    }
}

/**
 * The playing artwork's colour, where the artwork itself is on screen.
 *
 * A wrapper rather than a different theme: everything but `primary` stays what
 * [SquareTheme] built, so the player and the detail pages keep the app's glass
 * and type while their accent follows the cover — animated, so a track change
 * slides the colour across instead of snapping, which otherwise reads as a
 * glitch when artwork loads a beat late.
 */
@Composable
fun ArtworkAccentTheme(
    /** Null before the artwork has said anything; amethyst holds the fort. */
    seed: Color?,
    content: @Composable () -> Unit,
) {
    // Pushed toward legibility rather than used raw: a cover's dominant colour
    // is as likely to be near-black as near-white, and either one vanishes
    // against the wrong background.
    val accent = seed?.liftFor(DarkBase) ?: MaterialTheme.colorScheme.primary
    val animatedAccent by animateColorAsState(accent, tween(600), label = "accent")

    val base = MaterialTheme.colorScheme
    val scheme = remember(base, animatedAccent) {
        base.copy(primary = animatedAccent)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private val DarkBase = Color(0xFF0D0E11)

/** The film every glass surface is tinted with. */
internal val GlassFill = Color.White.copy(alpha = 0.10f)
internal val GlassFillStrong = Color.White.copy(alpha = 0.16f)

/** Text and icons, fixed light — see the note on [SquareTheme]. */
val Ink = Color(0xFFF7F8FA)
val InkDim = Color(0xFFF7F8FA).copy(alpha = 0.66f)

/** Brighten until the colour reads against a near-black background. */
private fun Color.liftFor(background: Color): Color {
    val floor = background.luminance() + 0.16f
    if (luminance() >= floor) return this
    val amount = ((floor - luminance()) * 2f).coerceIn(0f, 1f)
    return Color(
        red = red + (1f - red) * amount,
        green = green + (1f - green) * amount,
        blue = blue + (1f - blue) * amount,
    )
}

/**
 * The wide, soft, low-opacity drop shadow this design leans on.
 *
 * Elevation is the only depth cue left once colour is gone, so it does the work
 * that an accent used to: cards, covers and the mini player read as separate
 * layers rather than as regions of one flat sheet. Kept very diffuse — a tight
 * shadow at this size looks like a border and flattens the effect.
 */
fun Modifier.softShadow(
    shape: Shape,
    elevation: Dp = 18.dp,
    ambient: Float = 0.10f,
    spot: Float = 0.13f,
): Modifier = shadow(
    elevation = elevation,
    shape = shape,
    clip = false,
    ambientColor = Color.Black.copy(alpha = ambient),
    spotColor = Color.Black.copy(alpha = spot),
)
