package dev.pampa.fluidify.wear.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The measures the watch app adds to the engine's `FluidWearDimens`, which it keeps for the
 * player's glass. These are the ones a list or a sheet uses; a screen never writes a size by hand.
 */
object WearDimens {
    /** The glyph that leads a list row: the same on every list, so the icons line up screen to screen. */
    val ListIcon: Dp = 22.dp

    /** The glyph in a pill button (Search, Library on the Home). */
    val PillIcon: Dp = 20.dp

    /** Below this width the screen is the 192 dp class of watch; see the player and the ambient screen. */
    val CompactScreen: Dp = 210.dp

    /** The gap between the clock, the title and what is under them on the player and the ambient screen. */
    val PlayerGap: Dp = 6.dp
    val PlayerGapCompact: Dp = 4.dp

    /** The same gap, and the clock's distance from the top, on a compact screen with a large font. */
    val PlayerGapTight: Dp = 2.dp
    val ClockTopTight: Dp = 4.dp

    /** The largest font scale the clock above the player takes on a compact screen; see [PlayerClock]. */
    const val CompactClockMaxFontScale = 1.15f

    /** The font scale from which a compact screen counts as tight: its room above the player is the problem. */
    const val TightFontScale = 1.2f
}
