package dev.lelonio.square.ui.player

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * The shared-transition contract between the now-playing pill and the player.
 *
 * The bar composable that used to live here is gone — the pill in the tab bar
 * is the mini player now — but the two ends of the expand animation still have
 * to agree on their keys and their timing, and this is where they agree.
 */

/**
 * Marks the artwork as the element shared with the full player.
 *
 * An extension rather than an inline block because it is needed identically at
 * both ends, and the two have to agree on the key or nothing is shared.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedArtwork(
    sharedScope: androidx.compose.animation.SharedTransitionScope?,
    animatedScope: androidx.compose.animation.AnimatedVisibilityScope?,
): Modifier {
    if (sharedScope == null || animatedScope == null) return this
    return with(sharedScope) {
        this@sharedArtwork.sharedElement(
            rememberSharedContentState(key = SHARED_ARTWORK),
            animatedScope,
        )
    }
}

/**
 * Morphs the now-playing bar into the player's title capsule.
 *
 * The artwork alone was not enough: a track with a Canvas has no cover on the
 * player screen, so there was nothing for the thumbnail to become and the whole
 * thing collapsed into a cross-dissolve of two full screens. These two panes,
 * though, always both exist — a rounded glass pill carrying the title and the
 * artist at each end — so the bar has something to grow into on every track.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedPill(
    sharedScope: androidx.compose.animation.SharedTransitionScope?,
    animatedScope: androidx.compose.animation.AnimatedVisibilityScope?,
): Modifier {
    if (sharedScope == null || animatedScope == null) return this
    return with(sharedScope) {
        this@sharedPill.sharedBounds(
            rememberSharedContentState(key = SHARED_PILL),
            animatedScope,
            // The contents differ at the two ends — the bar has transport
            // buttons, the capsule has the queue and like actions — so they
            // cross-fade inside bounds that are morphing.
            enter = fadeIn(tween(EXPAND_MS)),
            exit = fadeOut(tween(EXPAND_MS / 2)),
            // Scaled rather than remeasured: the two panes hold different
            // controls, and re-laying them out mid-flight makes the contents
            // jump around inside bounds that are already moving.
            resizeMode = androidx.compose.animation.SharedTransitionScope
                .ResizeMode.Companion.scaleToBounds(),
        )
    }
}

/** The keys both ends of the expand animation agree on. */
const val SHARED_ARTWORK = "nowPlayingArtwork"
const val SHARED_PILL = "nowPlayingPill"

/** Length of the expand, shared by both halves so they move together. */
const val EXPAND_MS = 400

/** Fixed light ink for the pill, which always sits over darkened artwork. */
internal val MiniPlayerInk = Color(0xFFF7F8FA)
internal val MiniPlayerInkDim = Color(0xFFF7F8FA).copy(alpha = 0.66f)
