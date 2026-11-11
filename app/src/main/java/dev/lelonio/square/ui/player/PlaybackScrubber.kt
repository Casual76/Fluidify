package dev.lelonio.square.ui.player

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidScrubber
import dev.antigravity.fluidengine.ui.fluid.FluidScrubbing
import dev.lelonio.square.R

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
    resetKey: Any? = null,
) {
    val valuePattern = stringResource(R.string.player_seek_value)
    val duration = durationMs.coerceAtLeast(0L)
    val currentDuration by rememberUpdatedState(duration)
    val currentIdentity by rememberUpdatedState(resetKey)
    val currentOnSeek by rememberUpdatedState(onSeek)
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val restoreFocus = focused

    // The held seek belongs to this source and range. Recreating the control resets that hold,
    // while an already focused keyboard user stays on the same control of the new track.
    key(resetKey, duration) {
        LaunchedEffect(Unit) {
            if (restoreFocus && duration > 0L && currentDuration == duration && currentIdentity == resetKey) {
                focusRequester.requestFocus()
            }
        }
        FluidScrubber(
            position = { positionMs.value.toFloat() },
            onSeek = {
                // Cancelling an old pointer node can arrive as an Up. Its callback cannot act on
                // the replacement source, even if both songs happen to have the same duration.
                if (duration > 0L && currentDuration == duration && currentIdentity == resetKey) {
                    currentOnSeek(it.toLong().coerceIn(0L, duration))
                }
            },
            modifier = modifier.fillMaxWidth().focusRequester(focusRequester)
                .onFocusChanged { focused = it.isFocused },
            valueRange = 0f..duration.toFloat(),
            enabled = duration > 0L,
            startLabel = FluidScrubbing.Elapsed,
            endLabel = FluidScrubbing.Total,
            keyStep = KEY_STEP_MS,
            contentDescription = stringResource(R.string.player_seek_position),
            stepBackLabel = stringResource(R.string.player_seek_back),
            stepForwardLabel = stringResource(R.string.player_seek_forward),
            valueDescription = { value, range ->
                valuePattern.format(FluidScrubbing.Elapsed(value, range), FluidScrubbing.Total(value, range))
            },
        )
    }
}

/** Ten seconds per arrow key or TalkBack step: the default five per cent is minutes on a long mix. */
private const val KEY_STEP_MS = 10_000f
