package dev.lelonio.square.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.lelonio.square.data.Lyrics
import dev.lelonio.square.ui.glass.backdrop.Backdrop
import dev.lelonio.square.ui.glass.pressable
import dev.lelonio.square.ui.theme.Ink

/**
 * The line being sung, on the player rather than in the lyrics.
 *
 * The whole lyric is a place you go to; this is the one line you get without
 * going anywhere — the same thing the official client puts over the artwork, and
 * the reason a listener who is not reading along still catches the words. It is
 * a signpost as well as a reading: it says what the lyrics panel holds, on a
 * screen where that panel is otherwise a word in a segmented control.
 *
 * Only for timed lyrics. A plain document knows the words and not the moment,
 * and "the line being sung" is a question it cannot answer — the whole of it is
 * the answer, which is what the panel is for.
 */

/** How long a line takes to hand over to the next one. */
private const val LINE_IN_MS = 260
private const val LINE_OUT_MS = 190

/** And how long the line itself takes to arrive on the player, or leave it. */
private const val LINE_ENTER_MS = 320
private const val LINE_EXIT_MS = 200

/**
 * Which line of [lyrics] is being sung, or null when there is nothing to say.
 *
 * Derived, so whatever reads it is invalidated once a line rather than once a
 * frame: the clock underneath moves every frame on purpose — see
 * [rememberSmoothPosition] — and the answer being asked of it changes every few
 * seconds.
 */
@Composable
private fun rememberSungLine(
    lyrics: Lyrics?,
    positionMs: State<Long>,
    isPlaying: Boolean,
): State<String?> {
    // That frame loop is worth running for a highlight that sweeps across words.
    // With nothing to highlight it is sixty wake-ups a second for a question
    // nobody is asking, so the clock is told the song is not playing.
    val timed = lyrics != null && lyrics.synced
    val position = rememberSmoothPosition(positionMs, isPlaying && timed)

    return remember(lyrics) {
        derivedStateOf {
            if (lyrics == null || !lyrics.synced) return@derivedStateOf null
            val index = lyrics.lines.indexOfLast { (it.startTimeMs ?: 0L) <= position.value }
            lyrics.lines.getOrNull(index)?.text?.takeIf { it.isNotBlank() }
        }
    }
}

/**
 * One line, lifted away as the next arrives from below.
 *
 * Upwards, because that is the direction the song is going and the direction the
 * panel scrolls: a line dropping in from above would be the previous one coming
 * back.
 */
@Composable
private fun SungLineText(
    line: String?,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    // The last thing there was to say, kept for the length of the removal.
    //
    // What is drawn here goes on being drawn while the container animates it
    // away, and by then the answer is already null: without this the line
    // empties itself first and what leaves is a blank. A plain array rather than
    // snapshot state, as in LyricsView: nothing on screen depends on it
    // changing, and making it observable would recompose this to record a fact
    // that is read on the same frame it is written.
    val held = remember { arrayOf("") }
    val text = line?.takeIf { it.isNotBlank() }
    if (text != null) held[0] = text

    AnimatedContent(
        targetState = text ?: held[0],
        transitionSpec = {
            (slideInVertically { it } + fadeIn(tween(LINE_IN_MS)))
                .togetherWith(slideOutVertically { -it } + fadeOut(tween(LINE_OUT_MS)))
                // The height is part of the change, not something that happens
                // to the layout around it.
                //
                // A wrapped line and a short one are different heights, so this
                // grows and shrinks as the song moves through them — which is
                // fine while it is a movement and awful while it is a jump: the
                // seek bar and the transport sit directly under it. Critically
                // damped and on the family's own sheet spring, like every other
                // surface in the app that changes size.
                .using(
                    SizeTransform(clip = true) { _, _ ->
                        // The engine's own, typed: a generic spring over IntSize
                        // carries Float's visibility threshold and goes on
                        // ticking for fractions of a pixel after the movement is
                        // over. See FluidMotion.
                        FluidMotion.intSize(
                            dampingRatio = FluidMotion.DampingChrome,
                            stiffness = FluidMotion.ResponseSmooth,
                        )
                    },
                )
        },
        label = "sung-line",
        // Given its width by the caller rather than measured to the words: sized
        // to its contents, the box would animate between the width of one line
        // and the width of the next, and a line changing shape while it travels
        // reads as a wobble. Its *height* is its own, which is the point above.
        modifier = modifier,
    ) { shown ->
        Text(
            text = shown,
            style = style,
            color = color,
            // Wrapped rather than cut off. A lyric is a sentence, and half of
            // one with a full stop made of dots says less than nothing — the
            // line is here to be read, and on a phone, or in the panel, plenty
            // of them do not fit across.
            //
            // Bounded all the same: three is past anything a lyric document
            // actually writes on one line, and it is what stops a badly timed
            // source — a whole verse filed as a single line, which LrcLib has —
            // from taking the screen off the transport.
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The line being sung, on its own capsule above the title.
 *
 * The same pane as the title's, at the same width and with the same answer about
 * standing on a picture: over a Canvas those two are the only surfaces the words
 * have, and a lyric in bare ink on a clip graded near-white is a line that
 * disappears for a verse at a time.
 *
 * It is a way into the lyrics as well as a reading of them: a listener who wants
 * the rest of the song presses the words, not the label in the bar below.
 */
@Composable
internal fun SungLineCapsule(
    lyrics: Lyrics?,
    positionMs: State<Long>,
    isPlaying: Boolean,
    /** False while the whole lyric is already the thing on screen. */
    wanted: Boolean,
    backdrop: Backdrop,
    /** Whether the capsule stands on a clip; see GlassSurface. */
    onPicture: () -> Boolean,
    onOpenLyrics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sung by rememberSungLine(lyrics, positionMs, isPlaying)

    // Grown rather than dropped in. The words for a song arrive a moment after
    // it starts, with the listener already looking at the screen, and a capsule
    // that appears between two frames reads as a glitch where one that opens
    // reads as an answer — the same reason the video button beneath it is
    // written this way.
    AnimatedVisibility(
        visible = wanted && sung != null,
        enter = fadeIn(tween(LINE_ENTER_MS)) + expandVertically(tween(LINE_ENTER_MS)),
        exit = fadeOut(tween(LINE_EXIT_MS)) + shrinkVertically(tween(LINE_EXIT_MS)),
        modifier = modifier,
    ) {
        GlassSurface(
            backdrop = backdrop,
            surfaceColor = GlassFilm,
            onPicture = onPicture,
            shape = RoundedCornerShape(50),
            // The gap to the title lives inside the visibility block, so it
            // closes with the capsule instead of leaving a hole where one was.
            modifier = Modifier
                .padding(bottom = 10.dp)
                .fillMaxWidth()
                .pressable(onOpenLyrics, pressedScale = 0.98f),
        ) {
            SungLineText(
                line = sung,
                // Under the title in size and over it in nothing else: this is
                // the song talking, so it carries weight, and it is still not
                // the name of the thing being played.
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = GlassInk,
                // Lined up with the title below it, which is what makes the two
                // read as one block rather than as two floating capsules.
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 11.dp),
            )
        }
    }
}

/**
 * The same line, at the panel's scale.
 *
 * Bare rather than on a capsule of its own: in the panel it goes inside the pane
 * the title already stands on, so it is covered by the same glass for the same
 * reason and one surface does the work of two.
 */
@Composable
internal fun SungLineLabel(
    lyrics: Lyrics?,
    positionMs: State<Long>,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val sung by rememberSungLine(lyrics, positionMs, isPlaying)

    AnimatedVisibility(
        visible = sung != null,
        enter = fadeIn(tween(LINE_ENTER_MS)) + expandVertically(tween(LINE_ENTER_MS)),
        exit = fadeOut(tween(LINE_EXIT_MS)) + shrinkVertically(tween(LINE_EXIT_MS)),
        modifier = modifier,
    ) {
        SungLineText(
            line = sung,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.SemiBold,
            ),
            color = Ink,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
        )
    }
}
