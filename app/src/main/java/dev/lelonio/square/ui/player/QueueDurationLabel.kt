package dev.lelonio.square.ui.player

import android.text.format.DateUtils
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.lelonio.square.R
import dev.lelonio.square.playback.PlaybackQueueTiming
import dev.lelonio.square.playback.QueueDurationBoundary

internal data class QueueDurationSource(val timing: PlaybackQueueTiming, val position: State<Long>, val speed: Float)
internal val LocalQueueDurationSource = staticCompositionLocalOf<QueueDurationSource?> { null }

/** Only this small label observes the changing position; the queue rows remain unchanged. */
@Composable
internal fun QueueDurationLabel(modifier: Modifier = Modifier) {
    val source = LocalQueueDurationSource.current ?: return
    val remaining = source.timing.remaining(source.position.value, source.speed) ?: return
    val seconds = remaining.milliseconds / 1_000 + if (remaining.milliseconds % 1_000 > 0) 1 else 0
    val time = DateUtils.formatElapsedTime(seconds)
    val formatted = if (remaining.incomplete) stringResource(R.string.wf_root_queue_at_least, time) else time
    val label = if (remaining.incomplete && remaining.milliseconds == 0L) {
        stringResource(R.string.wf_root_queue_unknown)
    } else {
        stringResource(when (remaining.boundary) {
            QueueDurationBoundary.END -> R.string.wf_root_queue_remaining
            QueueDurationBoundary.REPEAT_CYCLE -> R.string.wf_root_queue_until_cycle
            QueueDurationBoundary.REPEAT_TRACK -> R.string.wf_root_queue_until_track
        }, formatted)
    }
    Text(label, modifier, style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}
