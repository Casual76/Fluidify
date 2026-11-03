package dev.lelonio.square.data

import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color

/**
 * The glass settings as they are stored, field for field.
 *
 * Until 1.7.5 this was also the recipe of the app's own glass renderer, a copy
 * of the backdrop library with a layer of opinion on top. That renderer is gone
 * (engine 3.0: one glass, the engine's, with one recipe per role), and with it
 * the target of most of these numbers. They are kept, read and written exactly
 * as before, so that nothing anyone has set is lost: the settings page shows
 * the ones that still move something, and the rest wait for a decision about
 * what the page should become.
 *
 * What still moves something:
 * - [blurRadius]: the frost of the bottom bar, handed to the engine's bar as its
 *   base radius (`FluidFloatingTabBarGlass.blurRadius`);
 * - [puckColor] and [puckOpacity]: the wash under the selected tab, the bar's
 *   own two knobs;
 * - [playerEnabled]: whether the player's Canvas plays and its panes refract.
 */
@Stable
data class GlassEffectConfig(
    val globalEnabled: Boolean = true,
    val vibrancy: Float = 1.2f,
    val blurRadius: Float = 2f,
    val lensHeight: Float = 0.4f,
    val lensAmount: Float = 0.6f,
    val chromaticAberration: Boolean = false,
    val depthEffect: Boolean = false,
    val surfaceTintColor: Color = Color(0xFF1A1A1A),
    val highlightColor: Color = Color.Unspecified,
    val highlightOpacity: Float = 0.55f,
    val style: GlassStyle = GlassStyle.LIQUID,
    val puckColor: Color = Color.Unspecified,
    val puckOpacity: Float = 0.8f,
    val surfaceOpacity: Float = 0.5f,
    val playerEnabled: Boolean = true,
    val miniPlayerEnabled: Boolean = true,
    val navBarEnabled: Boolean = true,
)

/** The style a profile stood for; stored with the profile, read by nothing now. */
enum class GlassStyle { LIQUID, BLUR, TRANSPARENT }
