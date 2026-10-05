package dev.pampa.fluidify.wear.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import dev.antigravity.fluidengine.wear.ambient.LocalFluidWearAmbient
import dev.pampa.fluidify.wear.link.ArtStore
import dev.pampa.fluidify.wear.playback.NowPlaying

/**
 * True below [WatchAmbientSurface]: the ambient screen is drawn over everything there, and a
 * player or cover page that finds itself in ambient has nothing of its own to show.
 */
internal val LocalAmbientHosted = staticCompositionLocalOf { false }

/**
 * Ambient mode for the whole app: one sparse, opaque screen drawn over the app, which stays where
 * it is.
 *
 * The app used to be taken out of composition in ambient and built again on the way back, and with
 * it went the scroll position of a list, the page of a pager and every effect the screens had
 * started: a search came back asking for the keyboard again, a library fetched itself again. Now
 * the app stays composed under the ambient screen but is neither drawn, nor read by accessibility,
 * and the pages that animate or tick (the player, the cover page) stop themselves in ambient.
 *
 * Ambient entry must finish in one frame: the black screen is there in the same composition that
 * learns of it. A fade is safe only on the interactive return.
 *
 * @param now what is playing, as a state read only while the ambient screen is up: a new
 *   snapshot recomposes nothing here the rest of the time.
 */
@Composable
internal fun WatchAmbientSurface(
    now: State<NowPlaying>,
    modifier: Modifier = Modifier,
    art: ArtStore? = null,
    content: @Composable () -> Unit,
) {
    val ambient = LocalFluidWearAmbient.current
    val inAmbient = ambient.isAmbient
    val reveal = remember { Animatable(1f) }
    val wasAmbient = remember { booleanArrayOf(false) }
    LaunchedEffect(inAmbient) {
        if (inAmbient) {
            wasAmbient[0] = true
            reveal.snapTo(0f)
        } else if (wasAmbient[0]) {
            reveal.animateTo(1f, tween(RevealMs))
        }
    }
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = reveal.value }
                .drawWithContent { if (!inAmbient) drawContent() }
                .then(if (inAmbient) Modifier.clearAndSetSemantics { } else Modifier),
        ) {
            CompositionLocalProvider(LocalAmbientHosted provides true) { content() }
        }
        if (inAmbient) AmbientNowPlaying(now.value, Modifier, art)
    }
}

/** How long the app takes to come back after ambient. */
private const val RevealMs = 220
