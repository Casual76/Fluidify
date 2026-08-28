package dev.lelonio.square.ui.player

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

/** Fixed light ink for the pill, which always sits over darkened artwork. */
internal val MiniPlayerInk = Color(0xFFF7F8FA)
internal val MiniPlayerInkDim = Color(0xFFF7F8FA).copy(alpha = 0.66f)
