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
        return DynamicFloat.onCondition(elapsed.gt(1f)).use(1f).elseUse(elapsed)
    }
}
