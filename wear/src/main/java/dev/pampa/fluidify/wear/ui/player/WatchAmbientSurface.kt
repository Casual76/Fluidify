package dev.pampa.fluidify.wear.ui.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.antigravity.fluidengine.wear.ambient.FluidAmbientState
import dev.antigravity.fluidengine.wear.ambient.LocalFluidWearAmbient
import dev.pampa.fluidify.wear.playback.NowPlaying

/** One short transition, then the interactive composition (and its frame clocks) is disposed. */
@Composable
internal fun WatchAmbientSurface(now: NowPlaying, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val ambient = LocalFluidWearAmbient.current
    val interactive = remember { FluidAmbientState.interactive() }
    Crossfade(ambient.isAmbient, modifier, animationSpec = tween(220), label = "ambient") { dimmed ->
        if (dimmed) AmbientNowPlaying(now)
        else CompositionLocalProvider(LocalFluidWearAmbient provides interactive) { content() }
    }
}
