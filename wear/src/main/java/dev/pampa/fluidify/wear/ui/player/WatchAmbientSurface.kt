package dev.pampa.fluidify.wear.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import dev.antigravity.fluidengine.wear.ambient.LocalFluidWearAmbient
import dev.pampa.fluidify.wear.playback.NowPlaying

/** Ambient entry must finish in one frame. A fade is safe only on the interactive return. */
@Composable
internal fun WatchAmbientSurface(now: NowPlaying, modifier: Modifier = Modifier,
    art: dev.pampa.fluidify.wear.link.ArtStore? = null, content: @Composable () -> Unit) {
    val ambient = LocalFluidWearAmbient.current
    var hasBeenAmbient by remember { mutableStateOf(false) }
    if (ambient.isAmbient) {
        SideEffect { hasBeenAmbient = true }
        AmbientNowPlaying(now, modifier, art)
    } else {
        val reveal = remember { Animatable(if (hasBeenAmbient) 0f else 1f) }
        LaunchedEffect(reveal) { reveal.animateTo(1f, tween(220)) }
        Box(modifier.graphicsLayer { alpha = reveal.value }) { content() }
    }
}
