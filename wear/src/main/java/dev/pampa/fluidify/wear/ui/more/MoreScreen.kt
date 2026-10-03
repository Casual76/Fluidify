package dev.pampa.fluidify.wear.ui.more

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Repeat
import com.adamglin.phosphoricons.regular.RepeatOnce
import com.adamglin.phosphoricons.regular.Shuffle
import dev.antigravity.fluidengine.wear.components.FluidWearListRow
import dev.pampa.fluidify.wear.R
import dev.pampa.fluidify.wear.playback.PlaybackControls
import dev.pampa.fluidify.wear.protocol.RepeatMode
import dev.pampa.fluidify.wear.ui.player.deviceIcon

/**
 * "Altro": the page to the right of the player.
 *
 * Everything a player has that does not deserve a place on a 200 dp circle:
 * where the sound comes out, shuffle and repeat, the settings. Swiped to from the
 * right edge, as the user asked, so the left edge stays the system's back.
 */
@Composable
fun MoreScreen(
    controls: PlaybackControls,
    onOutput: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val now by controls.nowPlaying.collectAsStateWithLifecycle()
    val snapshot = now.snapshot
    val listState = rememberTransformingLazyColumnState()

    ScreenScaffold(scrollState = listState, modifier = modifier) { padding ->
        TransformingLazyColumn(state = listState, contentPadding = padding) {
            item { ListHeader { Text(stringResource(R.string.more)) } }
            item {
                FluidWearListRow(
                    title = stringResource(R.string.audio_output),
                    subtitle = snapshot?.device?.name,
                    onClick = onOutput,
                    leading = { Icon(deviceIcon(snapshot?.device?.kind), contentDescription = null, modifier = Modifier.size(22.dp)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                val shuffle = snapshot?.shuffle == true
                FluidWearListRow(
                    title = stringResource(R.string.shuffle),
                    subtitle = stringResource(if (shuffle) R.string.on else R.string.off),
                    onClick = { controls.setShuffle(!shuffle) },
                    leading = { Icon(PhosphorIcons.Regular.Shuffle, contentDescription = null, modifier = Modifier.size(22.dp)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                val repeat = snapshot?.repeat ?: RepeatMode.OFF
                FluidWearListRow(
                    title = stringResource(R.string.repeat),
                    subtitle = stringResource(
                        when (repeat) {
                            RepeatMode.OFF -> R.string.off
                            RepeatMode.ALL -> R.string.on
                            RepeatMode.ONE -> R.string.repeat_one
                        },
                    ),
                    onClick = {
                        controls.setRepeat(
                            when (repeat) {
                                RepeatMode.OFF -> RepeatMode.ALL
                                RepeatMode.ALL -> RepeatMode.ONE
                                RepeatMode.ONE -> RepeatMode.OFF
                            },
                        )
                    },
                    leading = {
                        Icon(
                            if (repeat == RepeatMode.ONE) PhosphorIcons.Regular.RepeatOnce else PhosphorIcons.Regular.Repeat,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
