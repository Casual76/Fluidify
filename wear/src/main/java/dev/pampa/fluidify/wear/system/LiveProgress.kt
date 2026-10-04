package dev.pampa.fluidify.wear.system

import androidx.wear.protolayout.expression.DynamicBuilders.DynamicFloat
import androidx.wear.protolayout.expression.DynamicBuilders.DynamicInstant
import java.time.Instant

/**
 * A song's progress as an expression the system evaluates by itself, 0..1, from the platform's
 * clock: what lets the tile's ring and the watch face's progress complication move every second
 * without Fluidify being woken to redraw them.
 */
object LiveProgress {

    /** Progress of a song [positionMs] into its [durationMs] at [sampledAtEpochMs], playing on. */
    fun expression(positionMs: Long, sampledAtEpochMs: Long, durationMs: Long): DynamicFloat? {
        if (durationMs <= 0) return null
        val started = Instant.ofEpochMilli(sampledAtEpochMs - positionMs)
        val elapsed = DynamicInstant.withSecondsPrecision(started)
            .durationUntil(DynamicInstant.platformTimeWithSecondsPrecision())
            .toIntSeconds()
            .asFloat()
            .div(durationMs / 1_000f)
        return clamp(elapsed)
    }

    /**
     * How full one of the tile's two arcs is: the song's first half fills arc 0, its second half
     * arc 1. [RingHalves.split] for an expression.
     */
    fun half(progress: DynamicFloat, arc: Int): DynamicFloat = clamp(progress.times(2f).minus(arc.toFloat()))

    private fun clamp(value: DynamicFloat): DynamicFloat =
        DynamicFloat.onCondition(value.lt(0f)).use(0f)
            .elseUse(DynamicFloat.onCondition(value.gt(1f)).use(1f).elseUse(value))
}

/**
 * The tile's ring in two arcs, one each side, so that it stays clear of the title at the top and of
 * the edge button at the bottom — a whole ring ran under the heart's lower corners.
 */
object RingHalves {

    /** How full each arc is for a song [progress] (0..1) through: the right arc first. */
    fun split(progress: Float): Pair<Float, Float> {
        val p = progress.coerceIn(0f, 1f)
        return (p * 2f).coerceIn(0f, 1f) to (p * 2f - 1f).coerceIn(0f, 1f)
    }

    /**
     * The opening at the bottom, in degrees, for a [screenWidthDp] watch: the angle the edge
     * button's lower corners stand at, seen from the middle, plus a margin each side.
     *
     * The button's geometry is Wear's ([androidx.wear.protolayout.material3.EdgeButtonDefaults]):
     * as wide as the screen less a margin of 24% each side (26% from 225 dp up), its bottom
     * following the screen's curve 3 dp in from the edge.
     */
    fun bottomGapDegrees(screenWidthDp: Float): Float {
        val margin = if (screenWidthDp >= LARGE_SCREEN_DP) LARGE_MARGIN else SMALL_MARGIN
        val halfWidth = screenWidthDp * (0.5f - margin)
        val bottomRadius = screenWidthDp / 2f - BOTTOM_MARGIN_DP
        val cornerDepth = kotlin.math.sqrt((bottomRadius * bottomRadius - halfWidth * halfWidth).coerceAtLeast(1f))
        val corner = Math.toDegrees(kotlin.math.atan2(halfWidth, cornerDepth).toDouble()).toFloat()
        return 2f * (corner + CLEARANCE_DEGREES)
    }

    private const val LARGE_SCREEN_DP = 225f
    private const val SMALL_MARGIN = 0.24f
    private const val LARGE_MARGIN = 0.26f
    private const val BOTTOM_MARGIN_DP = 3f

    /** Room for the stroke's round cap and a hair of black between the ring and the button. */
    private const val CLEARANCE_DEGREES = 6f
}
