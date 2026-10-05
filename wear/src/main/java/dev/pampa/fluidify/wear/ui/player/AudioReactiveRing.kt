package dev.pampa.fluidify.wear.ui.player

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
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
import dev.lelonio.square.playback.AudioHaloPainter
import dev.lelonio.square.playback.AudioLightFade
import dev.lelonio.square.playback.AudioLightFrame
import dev.pampa.fluidify.wear.WearApp
import dev.pampa.fluidify.wear.playback.PlaybackMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.min

internal val LocalWatchAudioLightPreview = staticCompositionLocalOf<AudioLightFrame?> { null }

/** One subscription for both pager pages. No engine or radio is created for visual data. */
@Composable
fun WatchLightActivity(app: WearApp, visible: Boolean, playing: Boolean, track: String) {
    val preference by app.audioLightPreferences.enabled.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val mode by app.playback.mode.collectAsStateWithLifecycle()
    val seek by app.playback.visualSeek.collectAsStateWithLifecycle()
    val phone by app.link.phone.collectAsStateWithLifecycle()
    val status by app.link.status.collectAsStateWithLifecycle()
    val ambient = LocalFluidWearAmbient.current.isAmbient
    val reduced = LocalFluidMotionPolicy.current.reducedMotion
    val allowed by app.audioLightPolicy.allowed.collectAsStateWithLifecycle()
    val active = visible && playing && preference && allowed && !ambient && !reduced && lifecycle.isAtLeast(Lifecycle.State.STARTED)
    LaunchedEffect(active, mode, phone?.features, status, track, seek) {
        if (active) app.audioLight.run(mode == PlaybackMode.WATCH)
    }
}

/**
 * The song's progress as a line of light on the edge of the screen, which breathes with the music
 * when the audio light is on and there are frames to breathe with.
 *
 * @param clearTop how much of the top the line leaves free for the clock. A function, so that the
 *   width of a clock that is only measured after the first frame reaches the ring without the
 *   screen that holds it being composed again.
 */
@Composable
fun AudioReactiveRing(
    positionMs: () -> Long,
    durationMs: Long,
    running: Boolean,
    modifier: Modifier = Modifier,
    clearTop: () -> Dp = { 0.dp },
    color: Color = MaterialTheme.colorScheme.primary,
) {
    if (LocalFluidWearAmbient.current.isAmbient) return
    LocalWatchAudioLightPreview.current?.let { frame ->
        val painter = remember { AudioHaloPainter() }
        Canvas(modifier.fillMaxSize()) {
            val diameter = min(size.width, size.height)
            val gap = clearGap(clearTop().toPx(), diameter)
            drawIntoCanvas {
                painter.drawRing(
                    it.nativeCanvas,
                    diameter,
                    progressOf(positionMs(), durationMs),
                    gap,
                    color.toArgb(),
                    frame.energy,
                    frame.bass,
                )
            }
        }
        return
    }
    val app = LocalContext.current.applicationContext as? WearApp
    val preference = app?.audioLightPreferences?.enabled?.collectAsStateWithLifecycle()
    val allowed = app?.audioLightPolicy?.allowed?.collectAsStateWithLifecycle()
    val mode = app?.playback?.mode?.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val reduced = LocalFluidMotionPolicy.current.reducedMotion
    val enabled = preference?.value == true && allowed?.value == true && !reduced && lifecycle.isAtLeast(Lifecycle.State.STARTED)
    // This state is read only by drawing and graphicsLayer, never the player composition.
    val tick = remember { mutableLongStateOf(0) }
    val painter = remember { AudioHaloPainter() }
    var blend by remember { mutableFloatStateOf(0f) }
    var energy by remember { mutableFloatStateOf(0f) }
    var bass by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(enabled, running) {
        if (!enabled) {
            blend = 0f
            energy = 0f
            bass = 0f
            return@LaunchedEffect
        }
        val light = app?.audioLight
        val fade = AudioLightFade(blend)
        var emptyLooks = 0
        do {
            // Read before the frame is: one that lands in between is then seen as new below.
            val seen = light?.arrivals?.value
            val local = mode?.value == PlaybackMode.WATCH
            val frame = if (running) light?.frame(local) else null
            blend = fade.update(frame != null, SystemClock.elapsedRealtime())
            energy += ((frame?.energy ?: 0f) - energy) * ENERGY_SMOOTHING
            bass += ((frame?.bass ?: 0f) - bass) * BASS_SMOOTHING
            if (blend > 0f) tick.longValue = System.nanoTime()
            emptyLooks = if (frame == null) emptyLooks + 1 else 0
            when {
                blend > 0f -> delay(FRAME_MS)
                // Frames from the phone say when they arrive; nothing to do until one does.
                running && !local && light != null -> withTimeoutOrNull(QUIET_WAIT_MS) { light.arrivals.first { it != seen } }
                else -> delay(AudioLightPolling.quietMs(emptyLooks))
            }
        } while (running || blend > 0f)
    }
    val reacting by remember { derivedStateOf { blend > REACTING_BLEND } }
    FluidEdgeGlowRing(
        positionMs,
        durationMs,
        running && !reacting && lifecycle.isAtLeast(Lifecycle.State.STARTED),
        modifier = modifier.graphicsLayer { alpha = 1 - blend },
        clearTop = clearTop(),
        color = color,
    )
    Canvas(modifier.fillMaxSize().graphicsLayer { alpha = blend }) {
        if (blend == 0f) return@Canvas
        tick.longValue
        val diameter = min(size.width, size.height)
        val gap = clearGap(clearTop().toPx(), diameter)
        drawIntoCanvas {
            painter.drawRing(it.nativeCanvas, diameter, progressOf(positionMs(), durationMs), gap, color.toArgb(), energy, bass)
        }
    }
}

/** How long a song has got, 0 to 1. */
private fun progressOf(positionMs: Long, durationMs: Long): Float =
    if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

/** The angle, in degrees, a clock [clearTopPx] wide takes out of the top of a ring [diameter] across. */
private fun clearGap(clearTopPx: Float, diameter: Float): Float =
    if (clearTopPx > 0f) (2 * asin((clearTopPx / diameter).coerceIn(0f, MAX_CLEAR_RATIO)) * 180 / PI).toFloat() else 0f

/** How the ring looks at the music while it does not: the steadier the polling, the less often. */
internal object AudioLightPolling {
    /** The wait between looks for a frame that has not come: soon at first, then less and less. */
    fun quietMs(emptyLooks: Int): Long = when {
        emptyLooks < FIRST_LOOKS -> 100L
        emptyLooks < LATER_LOOKS -> 250L
        else -> 500L
    }

    private const val FIRST_LOOKS = 3
    private const val LATER_LOOKS = 10
}

/** A frame of the ring while it reacts: about thirty a second. */
private const val FRAME_MS = 34L

/** The longest the ring waits for a frame from the phone before looking at how it is faring. */
private const val QUIET_WAIT_MS = 500L
private const val ENERGY_SMOOTHING = 0.36f
private const val BASS_SMOOTHING = 0.30f

/** From this blend on the reacting ring replaces the plain one. */
private const val REACTING_BLEND = 0.02f
private const val MAX_CLEAR_RATIO = 0.95f
