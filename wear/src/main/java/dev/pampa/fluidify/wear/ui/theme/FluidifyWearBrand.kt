package dev.pampa.fluidify.wear.ui.theme

import androidx.compose.ui.graphics.Color
import dev.antigravity.fluidengine.ui.theme.AccentPoles
import dev.antigravity.fluidengine.ui.theme.AccentPreset

/**
 * Fluidify's amethyst, the same preset as the phone's.
 *
 * Duplicated rather than shared because the phone keeps it inside its app
 * module; keep the two in step (app/src/main/java/dev/lelonio/square/ui/theme/Theme.kt).
 */
val FluidifyWearBrand = AccentPreset(
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
