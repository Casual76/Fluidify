package dev.lelonio.square.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * What is left of the now-playing bar's own file.
 *
 * It used to carry a second transition: shared elements keyed on the artwork and
 * on the title capsule, so the bar could morph into the player through
 * `SharedTransitionLayout`. Both halves were inert — nothing ever passed the
 * scopes — and the approach had already been rejected in this same file for a
 * reason that is worth keeping: the two ends hold different controls, so shared
 * bounds could only ever cross-fade their contents inside a moving frame.
 *
 * The journey is one surface now; see NowPlayingSheet. Leaving a second, already
 * abandoned mechanism standing would only ensure that the next reader tries to
 * wire it back up.
 */

/**
 * The pill's ink.
 *
 * It was fixed light for as long as the pill always sat over *darkened*
 * artwork, and that stopped being true the day the app grew a light side: the
 * veil goes up towards paper there, the film on the pill goes with it, and white
 * letters on it are white letters on white. It is the page's ink now, like
 * everything else written on the page.
 */
internal val MiniPlayerInk: Color
    @Composable get() = dev.lelonio.square.ui.theme.Ink

internal val MiniPlayerInkDim: Color
    @Composable get() = dev.lelonio.square.ui.theme.InkDim
