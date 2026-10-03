package dev.pampa.fluidify.wear.ui.sheets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.wear.components.FluidWearListRow
import dev.pampa.fluidify.wear.R
import dev.pampa.fluidify.wear.playback.PlaybackControls
import dev.pampa.fluidify.wear.ui.common.WatchList
import java.text.DateFormat
import java.util.Date

/** The phone's sleep timer, from the wrist: stop in N minutes, at the end of the song, or not. */
@Composable
fun SleepScreen(controls: PlaybackControls, onSet: () -> Unit) {
    val now by controls.nowPlaying.collectAsStateWithLifecycle()
    val sleep = now.snapshot?.sleep
    WatchList(title = stringResource(R.string.sleep_timer)) {
        if (sleep != null) {
            item {
                FluidWearListRow(
                    title = stringResource(R.string.sleep_cancel),
                    subtitle = when {
                        sleep.atTrackEnd -> stringResource(R.string.sleep_at_track_end)
                        sleep.endsAtEpochMs != null -> stringResource(
                            R.string.sleep_ends_at,
                            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(sleep.endsAtEpochMs!!)),
                        )
                        else -> null
                    },
                    onClick = {
                        controls.sleep(cancel = true)
                        onSet()
                    },
                )
            }
        }
        listOf(5, 15, 30, 45, 60).forEach { minutes ->
            item(key = "m$minutes") {
                FluidWearListRow(
                    title = stringResource(R.string.sleep_minutes, minutes),
                    onClick = {
                        controls.sleep(minutes = minutes)
                        onSet()
                    },
                )
            }
        }
        item {
            FluidWearListRow(
                title = stringResource(R.string.sleep_at_track_end),
                onClick = {
                    controls.sleep(atTrackEnd = true)
                    onSet()
                },
            )
        }
    }
}
