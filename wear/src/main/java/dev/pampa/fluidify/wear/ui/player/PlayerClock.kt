package dev.pampa.fluidify.wear.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import dev.pampa.fluidify.wear.ui.theme.WearDimens
import kotlin.math.min

/**
 * Whether a screen has too little room above the player for the clock at the listener's font size:
 * a compact (192 dp) watch with a large font setting. Shared by the player and the ambient screen,
 * which put the clock in the same place.
 */
@Composable
internal fun isTightClock(compact: Boolean): Boolean = compact && LocalDensity.current.fontScale >= WearDimens.TightFontScale

/**
 * Lays out [content], the clock, at the listener's font scale — but no more than
 * [WearDimens.CompactClockMaxFontScale] when the screen is [tight]. The clock was drawn at scale 1
 * there, whatever the setting, to keep it from pushing the title down onto the transport; the
 * clock is the least needed of the things up there, so it gives a little, not all of what the
 * listener asked for.
 */
@Composable
internal fun PlayerClock(tight: Boolean, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    if (!tight) {
        content()
        return
    }
    CompositionLocalProvider(LocalDensity provides Density(density.density, min(density.fontScale, WearDimens.CompactClockMaxFontScale))) {
        content()
    }
}
