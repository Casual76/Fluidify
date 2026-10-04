package dev.lelonio.square.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lelonio.square.SquareApplication
import dev.lelonio.square.playback.*
import kotlinx.coroutines.delay

/** Deterministic frames for previews and render tests; absent in normal app composition. */
internal val LocalAudioLightPreview = staticCompositionLocalOf<State<AudioLightFrame?>?> { null }

@Composable
internal fun rememberAudioLight(playing: Boolean, visible: Boolean = true, mini: Boolean = false): State<AudioLightFrame?> {
    LocalAudioLightPreview.current?.let { return it }
    val context = LocalContext.current
    val app = context.applicationContext as? SquareApplication
    val preference = app?.audioLightPreferences?.enabled?.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val policy = remember(context) { AudioLightPolicy(context) }
    DisposableEffect(policy) { onDispose { policy.close() } }
    val allowed by policy.allowed.collectAsStateWithLifecycle()
    val frame = remember { mutableStateOf<AudioLightFrame?>(null) }
    val owner = remember { Any() }
    val enabled = preference?.value == true && visible && allowed && !dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy.current.reducedMotion && lifecycle.isAtLeast(Lifecycle.State.STARTED)
    LaunchedEffect(enabled, playing) {
        if (!enabled) { frame.value = null; return@LaunchedEffect }
        if (playing) AudioReactive.acquire(owner)
        try {
            if (!playing) {
                repeat(if (mini) 8 else 15) { frame.value = AudioLightFrame(dueNs = System.nanoTime()); delay(if (mini) 34 else 17) }
                frame.value = null; return@LaunchedEffect
            }
            var lastValid = 0L
            while (true) {
                val now = System.nanoTime()
                val value = AudioReactive.frame()
                if (value != null) lastValid = now
                frame.value = value?.copy(dueNs = now) ?: if (lastValid > 0 && now - lastValid < 250_000_000) AudioLightFrame(dueNs = now) else null
                delay(if (mini) 34 else 17)
            }
        } finally { AudioReactive.release(owner); frame.value = null }
    }
    return frame
}

@Composable
internal fun AudioLightHalo(frame: State<AudioLightFrame?>, color: Color, cover: () -> Rect? = { null },
    modifier: Modifier = Modifier, mini: Boolean = false, compact: Boolean = false, bottomMix: () -> Float = { 0f }) {
    val painter = remember { AudioHaloPainter() }
    val bounds = remember { android.graphics.RectF() }
    val clip = remember { android.graphics.Path() }
    Canvas(modifier.fillMaxSize()) {
        val current = frame.value
        val rect = cover()
        if (rect != null) bounds.set(rect.left, rect.top, rect.right, rect.bottom)
        if (current == null) painter.reset()
        else drawIntoCanvas {
                val native = it.nativeCanvas
                val saved = native.save()
                if (mini) {
                    clip.reset(); clip.addRoundRect(0f,0f,size.width,size.height,size.height/2,size.height/2,android.graphics.Path.Direction.CW)
                    native.clipPath(clip)
                }
                painter.draw(native, size.width, size.height, current, color.toArgb(),
                    bounds.takeIf { rect != null }, mini, compact, bottomMix = bottomMix())
                native.restoreToCount(saved)
        }
    }
}
