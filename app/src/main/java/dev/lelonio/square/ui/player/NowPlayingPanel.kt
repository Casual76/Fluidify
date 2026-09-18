package dev.lelonio.square.ui.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.Pause
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.fill.SkipBack
import com.adamglin.phosphoricons.fill.SkipForward
import com.adamglin.phosphoricons.regular.CaretUp
import dev.lelonio.square.R
import dev.lelonio.square.ui.components.Artwork
import dev.lelonio.square.ui.glass.pressable
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import androidx.compose.ui.res.stringResource

/**
 * What is playing, kept on screen beside the page.
 *
 * The pill's job, given the room a wide window has for it. Below nine hundred
 * points the pill is right — it is the most a phone can spare — and above it the
 * same information as a strip forty-eight points tall, on a screen with a
 * thousand to spare, is an app that has not noticed where it is running.
 *
 * It is the *same* surface the window grows out of, not a second one: the panel
 * reports its own rectangle exactly as the pill does, so the morph that turns it
 * into the full player is the one that was already there. Nothing about
 * [NowPlayingSheet] knows which of the two it started from.
 *
 * Deliberately not a second player. The queue, the lyrics, the effects and the
 * credits all live in the full window, and putting any of them here would make
 * two places to do the same thing, one of them smaller. What this holds is what
 * a glance is for: what is playing, how far in, and the three buttons.
 */
@Composable
fun NowPlayingPanel(
    state: PlaybackState,
    positionMs: State<Long>,
    /** The device the music is coming out of, when it is not this one. */
    playingOn: String?,
    modifier: Modifier = Modifier,
    /**
     * False for the copy that rides inside the travelling surface.
     *
     * Three more gestures on the axis the journey is being dragged along is not
     * a detail: the copy is a picture, and the real panel is one frame away.
     */
    interactive: Boolean = true,
    onOpen: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
) {
    Column(
        modifier = modifier.padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The way into the full player, said with a word rather than left to be
        // discovered: the panel is a surface you can press, and nothing about a
        // cover suggests that.
        Row(
            Modifier
                .fillMaxWidth()
                .then(
                    if (interactive) {
                        Modifier.pressable(onOpen, pressedScale = 0.98f)
                    } else {
                        Modifier
                    },
                )
                .padding(bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = playingOn ?: stringResource(R.string.now_playing),
                style = MaterialTheme.typography.labelLarge,
                color = InkDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                PhosphorIcons.Regular.CaretUp,
                contentDescription = null,
                tint = InkDim,
                modifier = Modifier.size(18.dp),
            )
        }

        Crossfade(
            targetState = state.artworkUrl to state.title,
            animationSpec = tween(320),
            label = "panel-art",
        ) { (url, title) ->
            Artwork(
                url = url,
                title = title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .then(
                        if (interactive) {
                            Modifier.pressable(onOpen, pressedScale = 0.97f)
                        } else {
                            Modifier
                        },
                    ),
                corner = 18.dp,
            )
        }

        Spacer(Modifier.height(18.dp))

        Text(
            text = state.title,
            style = MaterialTheme.typography.titleLarge,
            color = Ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = state.artist,
            style = MaterialTheme.typography.bodyMedium,
            color = InkDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        )

        Spacer(Modifier.height(14.dp))

        LinearProgressIndicator(
            progress = { progressOf(positionMs.value, state.durationMs) },
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = Ink.copy(alpha = 0.18f),
            drawStopIndicator = {},
        )

        Spacer(Modifier.height(10.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrevious, enabled = interactive && state.hasPrevious) {
                Icon(
                    PhosphorIcons.Fill.SkipBack,
                    contentDescription = stringResource(R.string.previous),
                    tint = if (state.hasPrevious) Ink else Ink.copy(alpha = 0.35f),
                    modifier = Modifier.size(26.dp),
                )
            }
            IconButton(
                onClick = onTogglePlay,
                enabled = interactive,
                modifier = Modifier.size(56.dp),
            ) {
                Icon(
                    if (state.isPlaying) PhosphorIcons.Fill.Pause else PhosphorIcons.Fill.Play,
                    contentDescription = stringResource(
                        if (state.isPlaying) R.string.pause else R.string.play,
                    ),
                    tint = Ink,
                    modifier = Modifier.size(38.dp),
                )
            }
            IconButton(onClick = onNext, enabled = interactive && state.hasNext) {
                Icon(
                    PhosphorIcons.Fill.SkipForward,
                    contentDescription = stringResource(R.string.next),
                    tint = if (state.hasNext) Ink else Ink.copy(alpha = 0.35f),
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }
}

/**
 * How wide the panel is.
 *
 * Three hundred and forty, which is a phone's width less its gutters: the cover
 * in it is then about the size of the one on a phone's player, and the title
 * under it breaks where a title is used to breaking. Wider and it starts taking
 * room from the page for a picture nobody asked to be bigger.
 */
val NowPlayingPanelWidth = 340.dp

/** Below this the panel does not fit beside a page worth reading. See [NowPlayingPanel]. */
val NowPlayingPanelMinWindow = 900.dp
