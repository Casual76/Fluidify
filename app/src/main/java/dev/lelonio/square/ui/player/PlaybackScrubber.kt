package dev.lelonio.square.ui.player

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import dev.antigravity.fluidengine.ui.fluid.FluidScrubber
import dev.antigravity.fluidengine.ui.fluid.FluidScrubbing

/**
 * The playback position in full: the engine's scrubber, with the time under it.
 *
 * Scrubbed locally and committed once, on release -- which is the scrubber's own contract, and
 * the reason the app's hand-made bar existed: every intermediate value here would be a seek, and
 * the engine would spend a drag re-buffering instead of playing. The position is read inside the
 * draw (a lambda, not a value) so a ticking position recomposes nothing.
 *
 * The folded form the now-playing panel uses beside a page -- a three-point hairline that grows
 * into a bar -- is not this; it stays [GlassProgressBar].
 */
@Composable
internal fun PlaybackScrubber(
    positionMs: State<Long>,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    FluidScrubber(
        position = { positionMs.value.toFloat() },
        onSeek = { onSeek(it.toLong()) },
        modifier = modifier.fillMaxWidth(),
        valueRange = 0f..durationMs.coerceAtLeast(0L).toFloat(),
        enabled = durationMs > 0,
        startLabel = FluidScrubbing.Elapsed,
        endLabel = FluidScrubbing.Total,
        keyStep = KEY_STEP_MS,
    )
}

/** Ten seconds per arrow key or TalkBack step: the default five per cent is minutes on a long mix. */
private const val KEY_STEP_MS = 10_000f
