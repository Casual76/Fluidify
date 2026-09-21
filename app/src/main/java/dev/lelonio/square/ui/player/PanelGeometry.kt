package dev.lelonio.square.ui.player

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

/**
 * The four sizes of the now-playing panel, and the arithmetic between them.
 *
 * The panel has one number, `reach`: 0 is the panel as it always was, 1 is the
 * panel grown to the height of the page, 2 is that panel with the words and the
 * queue opened out to its left. Past the last of those the surface is no longer
 * the panel's but the window's — `expand`, the morph that was already there —
 * and the finger runs along `reach + expand` as if it were one axis.
 *
 * Everything here is a pure function of pixels worked out once per window, so
 * the frame, the page and the bar can ask the same question in a measure pass
 * and get the same answer. No `State` inside: a geometry is a value, and the
 * thing that moves is the number handed to it.
 */
@Immutable
class PanelGeometry(
    /** The panel at rest, px. */
    val miniWidth: Float,
    /** The panel at the height of the page, px. See [tallWidthFor]. */
    val tallWidth: Float,
    /** The words and the queue beside it, px. Zero when the window has no room for them. */
    val extension: Float,
    /** The height the panel is allowed, px: the window less the bars and the gutters. */
    val boxHeight: Float,
    /**
     * True when a clip fills the tall pane edge to edge, as it does the small
     * one. False when the clip is wider than the pane can afford at that
     * height, in which case it sits inset in the glass like a cover does.
     */
    val pictureAtTall: Boolean,
    /** How far a finger runs for the last leg, the one into the window, px. */
    private val windowLegPx: Float,
    /** Floors for a leg, px: no journey shorter than a thumb. */
    private val minHorizontalLegPx: Float,
    private val minVerticalLegPx: Float,
    private val minExtensionLegPx: Float,
) {
    /** Whether there is room for the third size at all. */
    val hasWide: Boolean get() = extension > 0f

    /** The last value `reach` may take: 2 with the extension, 1 without. */
    val maxReach: Float get() = if (hasWide) 2f else 1f

    /** The last anchor on the unified axis, which is always the window. */
    val maxUnits: Float get() = maxReach + 1f

    /** How far along the first leg, 0..1. */
    fun reach01(reach: Float): Float = reach.coerceIn(0f, 1f)

    /** How far along the second leg, 0..1. Always zero without an extension. */
    fun ext01(reach: Float): Float = if (hasWide) (reach - 1f).coerceIn(0f, 1f) else 0f

    /** The column's width at this reach, px. */
    fun columnWidthPx(reach: Float): Float = lerp(miniWidth, tallWidth, reach01(reach))

    /** The extension's width at this reach, px. */
    fun extPx(reach: Float): Float = extension * ext01(reach)

    /** The whole panel's width at this reach, px. */
    fun widthPx(reach: Float): Float = columnWidthPx(reach) + extPx(reach)

    /** How much of the clip is a picture behind the controls rather than a cover among them, 0..1. */
    fun pictureShare(reach: Float): Float = if (pictureAtTall) 1f else 1f - reach01(reach)

    /**
     * How far a finger has to run for one whole leg, px.
     *
     * The travelling edge's own distance where that distance is a gesture: the
     * left edge for the first two legs, which is what makes the glass follow the
     * finger one to one. The last leg is the window's, and its edge really
     * travels most of the screen, so that one is the panel's own width instead —
     * the same compromise the single journey already made.
     *
     * @param units where on the axis the finger is, so the leg can be told.
     * @param miniHeight the panel's natural height at rest, px, for the vertical run.
     * @param backwards true when the pull is towards the smaller sizes, which
     *   decides which leg a finger standing exactly on an anchor is on.
     */
    fun legPx(units: Float, horizontal: Boolean, miniHeight: Float, backwards: Boolean): Float {
        val raw = if (backwards) ceil(units) - 1f else floor(units)
        val leg = raw.toInt().coerceIn(0, maxReach.toInt())
        return when {
            leg >= maxReach.toInt() -> if (horizontal) windowLegPx else 0f
            leg == 0 -> if (horizontal) {
                (tallWidth - miniWidth).coerceAtLeast(minHorizontalLegPx)
            } else {
                ((boxHeight - miniHeight) / 2f).coerceAtLeast(minVerticalLegPx)
            }
            else -> if (horizontal) extension.coerceAtLeast(minExtensionLegPx) else 0f
        }
    }

    /** The anchor nearest to a value on the axis. */
    fun nearestAnchor(units: Float): Float = round(units).coerceIn(0f, maxUnits)

    companion object {
        /** A phone's width less its gutters; see [NowPlayingPanelWidth]. */
        val MiniWidth: Dp get() = NowPlayingPanelWidth

        /** The column at the height of the page, when nothing else has a say. */
        val TallWidth = 400.dp

        /**
         * How much wider than [TallWidth] a clip may ask the tall pane to be
         * before it is inset instead. Ten percent of crop, the same allowance
         * the small pane already gives a clip that is not quite nine by sixteen,
         * so a clip a few points over the line does not change arrangement.
         */
        val TallWidthTolerance = 440.dp

        /** What must be left of the page beside the widest panel: a phone's worth. */
        val PageFloor = 430.dp

        /** Narrower than this the words and the queue are not worth opening. */
        val ExtensionMin = 260.dp

        /** Wider than this they are taking room for nothing. */
        val ExtensionMax = 360.dp

        /** The air between the panel's glass and the edge of the window. */
        val EndPadding = 14.dp

        /** The first leg to the left: a short pull, since the edge moves little. */
        val MinHorizontalLeg = 48.dp

        /** And upwards, where the edge moves half the difference in height. */
        val MinVerticalLeg = 120.dp

        /**
         * The geometry for a window.
         *
         * @param windowWidth the window, dp.
         * @param boxHeightPx the height the panel is allowed, px.
         * @param clipRatio the clip's width over its height, or null without one.
         */
        fun of(
            density: Density,
            windowWidth: Dp,
            boxHeightPx: Float,
            clipRatio: Float?,
        ): PanelGeometry = with(density) {
            val mini = MiniWidth.toPx()
            val tallMax = TallWidth.toPx()
            val natural = clipRatio?.let { boxHeightPx * it }
            val tall = natural?.coerceIn(mini, tallMax) ?: tallMax
            val pictureAtTall = natural != null && natural <= TallWidthTolerance.toPx()
            val room = windowWidth.toPx() - EndPadding.toPx() - tall - PageFloor.toPx()
            val extension = if (room >= ExtensionMin.toPx()) {
                room.coerceAtMost(ExtensionMax.toPx())
            } else {
                0f
            }
            PanelGeometry(
                miniWidth = mini,
                tallWidth = tall,
                extension = extension,
                boxHeight = boxHeightPx,
                pictureAtTall = pictureAtTall,
                windowLegPx = MiniWidth.toPx(),
                minHorizontalLegPx = MinHorizontalLeg.toPx(),
                minVerticalLegPx = MinVerticalLeg.toPx(),
                minExtensionLegPx = MinMorphTravel.toPx(),
            )
        }

        /** Before the window has been measured: the panel of today, and nowhere to go. */
        fun rest(density: Density): PanelGeometry = with(density) {
            PanelGeometry(
                miniWidth = MiniWidth.toPx(),
                tallWidth = MiniWidth.toPx(),
                extension = 0f,
                boxHeight = 0f,
                pictureAtTall = true,
                windowLegPx = MiniWidth.toPx(),
                minHorizontalLegPx = MinHorizontalLeg.toPx(),
                minVerticalLegPx = MinVerticalLeg.toPx(),
                minExtensionLegPx = MinMorphTravel.toPx(),
            )
        }
    }
}

private fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t
