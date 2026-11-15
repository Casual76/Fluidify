package dev.lelonio.square.ui.requests

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.lelonio.square.R

/** One quiet, dismissible line. No dialog, log text or queue mutation on failure. */
@Composable
internal fun I07RadioFeedback(state: I07LatestRequest.State, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    if (state.phase == I07LatestRequest.Phase.IDLE) return
    FluidInlineMessage(
        message = stringResource(when (state.phase) {
            I07LatestRequest.Phase.LOADING -> R.string.wf_i07_radio_waiting
            I07LatestRequest.Phase.UNAVAILABLE -> R.string.wf_i07_radio_offline
            else -> R.string.wf_i07_radio_failed
        }),
        tone = FluidTone.Neutral,
        onClick = onDismiss,
        modifier = modifier,
    )
}
