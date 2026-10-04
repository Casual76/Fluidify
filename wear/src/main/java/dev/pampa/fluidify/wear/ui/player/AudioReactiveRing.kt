package dev.pampa.fluidify.wear.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.MaterialTheme
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.antigravity.fluidengine.wear.ambient.LocalFluidWearAmbient
import dev.antigravity.fluidengine.wear.components.FluidEdgeGlowRing
import dev.lelonio.square.playback.*
import dev.pampa.fluidify.wear.WearApp
import dev.pampa.fluidify.wear.playback.PlaybackMode
import kotlinx.coroutines.delay
import kotlin.math.*

internal val LocalWatchAudioLightPreview = staticCompositionLocalOf<AudioLightFrame?> { null }

/** One subscription for both pager pages. No engine or radio is created for visual data. */
@Composable
fun WatchLightActivity(app: WearApp, visible: Boolean, playing: Boolean, track: String) {
    val preference by app.audioLightPreferences.enabled.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val mode by app.playback.mode.collectAsStateWithLifecycle()
    val seek by app.playback.visualSeek.collectAsStateWithLifecycle()
    val phone by app.link.phone.collectAsStateWithLifecycle()
    val status by app.link.status.collectAsStateWithLifecycle()
    val ambient = LocalFluidWearAmbient.current.isAmbient
    val reduced = LocalFluidMotionPolicy.current.reducedMotion
    val policy = remember(app) { AudioLightPolicy(app) }
    DisposableEffect(policy) { onDispose { policy.close() } }
    val allowed by policy.allowed.collectAsStateWithLifecycle()
    val active = visible && playing && preference && allowed && !ambient && !reduced && lifecycle.isAtLeast(Lifecycle.State.STARTED)
    LaunchedEffect(active, mode, phone?.features, status, track, seek) {
        if (active) app.audioLight.run(mode == PlaybackMode.WATCH)
    }
}

@Composable
fun AudioReactiveRing(positionMs: () -> Long, durationMs: Long, running: Boolean,
    modifier: Modifier = Modifier, clearTop: Dp = 0.dp, color: Color = MaterialTheme.colorScheme.primary) {
    LocalWatchAudioLightPreview.current?.let { frame ->
        val painter = remember { AudioHaloPainter() }
        Canvas(modifier.fillMaxSize()) {
            val diameter = min(size.width, size.height)
            val gap = if (clearTop > 0.dp) (2 * asin((clearTop.toPx() / diameter).coerceIn(0f, .95f)) * 180 / PI).toFloat() else 0f
            drawIntoCanvas { painter.drawRing(it.nativeCanvas, diameter,
                if (durationMs > 0) (positionMs().toFloat()/durationMs).coerceIn(0f,1f) else 0f,
                gap, color.toArgb(), frame.energy, frame.bass) }
        }
        return
    }
    val app = LocalContext.current.applicationContext as? WearApp
    val preference = app?.audioLightPreferences?.enabled?.collectAsStateWithLifecycle()
    val policy = remember(app) { app?.let { AudioLightPolicy(it) } }
    DisposableEffect(policy) { onDispose { policy?.close() } }
    val allowed = policy?.allowed?.collectAsStateWithLifecycle()
    val mode = app?.playback?.mode?.collectAsStateWithLifecycle()
    val reduced = LocalFluidMotionPolicy.current.reducedMotion
    val enabled = preference?.value == true && allowed?.value == true && !reduced
    // This state is read only by drawing and graphicsLayer, never the player composition.
    val tick = remember { mutableLongStateOf(0) }
    val painter = remember { AudioHaloPainter() }
    var blend by remember { mutableFloatStateOf(0f) }
    var energy by remember { mutableFloatStateOf(0f) }
    var bass by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(enabled, running) {
        if (!enabled) { blend = 0f; energy = 0f; bass = 0f; return@LaunchedEffect }
        val fade = AudioLightFade(blend)
        do {
            val frame = if (running) app?.audioLight?.frame(mode?.value == PlaybackMode.WATCH) else null
            blend = fade.update(frame != null, android.os.SystemClock.elapsedRealtime())
            energy += ((frame?.energy ?: 0f) - energy) * .36f
            bass += ((frame?.bass ?: 0f) - bass) * .30f
            if (blend > 0f) tick.longValue = System.nanoTime()
            delay(if (blend > 0f) 34 else 100)
        } while (running || blend > 0f)
    }
    val reacting by remember { derivedStateOf { blend > .02f } }
    FluidEdgeGlowRing(positionMs, durationMs, running && !reacting, modifier = modifier.graphicsLayer { alpha = 1 - blend }, clearTop = clearTop, color = color)
    Canvas(modifier.fillMaxSize().graphicsLayer { alpha = blend }) {
        if (blend == 0f) return@Canvas
        tick.longValue
        val diameter = min(size.width, size.height)
        val gap = if (clearTop > 0.dp) (2 * asin((clearTop.toPx() / diameter).coerceIn(0f, .95f)) * 180 / PI).toFloat() else 0f
        val progress = if (durationMs > 0) (positionMs().toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        drawIntoCanvas { painter.drawRing(it.nativeCanvas, diameter, progress, gap, color.toArgb(), energy, bass) }
    }
}
