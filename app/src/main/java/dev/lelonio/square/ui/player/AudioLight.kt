package dev.lelonio.square.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.lelonio.square.SquareApplication
import dev.lelonio.square.playback.AudioHaloPainter
import dev.lelonio.square.playback.AudioLightFrame
import dev.lelonio.square.playback.AudioReactive
import kotlinx.coroutines.delay

/** Deterministic frames for previews and render tests; absent in normal app composition. */
internal val LocalAudioLightPreview = staticCompositionLocalOf<State<AudioLightFrame?>?> { null }

/**
 * The music's light for one player surface: the latest analysed frame, or `null` when there is
 * no light to draw.
 *
 * The value changes only when a new analysis frame comes due (thirty a second at most, ten in the
 * light mode), never once per display frame. It used to be rewritten every 17 ms with a fresh
 * copy, purely so that it would never compare equal — which invalidated everything that read it,
 * the cover's aura under its full-screen blur included, sixty times a second, to answer "is the
 * light on?". The smooth motion between two frames is the painter's job: [AudioLightHalo] redraws
 * on the display's clock and eases towards the latest frame itself.
 *
 * [visible] is a lambda so that a caller can hand over something that changes every frame of a
 * gesture (the player's open fraction) without recomposing for it.
 */
@Composable
internal fun rememberAudioLight(
    playing: Boolean,
    visible: () -> Boolean = { true },
    mini: Boolean = false,
): State<AudioLightFrame?> {
    LocalAudioLightPreview.current?.let { return it }
    val app = LocalContext.current.applicationContext as? SquareApplication
    val preference = app?.audioLightPreferences?.enabled?.collectAsStateWithLifecycle()
    val allowed = app?.audioLightPolicy?.allowed?.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val reducedMotion = LocalFluidMotionPolicy.current.reducedMotion
    val shown by remember(visible) { derivedStateOf(visible) }
    val enabled = preference?.value == true &&
        allowed?.value == true &&
        shown &&
        !reducedMotion &&
        lifecycle.isAtLeast(Lifecycle.State.STARTED)

    val frame = remember { mutableStateOf<AudioLightFrame?>(null, referentialEqualityPolicy()) }
    val owner = remember { Any() }
    LaunchedEffect(enabled, playing) {
        if (!enabled) {
            frame.value = null
            return@LaunchedEffect
        }
        if (!playing) {
            // A pause lets the light settle rather than cut: a quiet frame for as long as the
            // painter takes to ease its bands down. Opened already paused, there is nothing lit
            // to settle and nothing is drawn at all.
            if (frame.value != null) {
                frame.value = QUIET
                delay(SETTLE_MS)
            }
            frame.value = null
            return@LaunchedEffect
        }
        AudioReactive.acquire(owner)
        try {
            var lastHeard = 0L
            while (true) {
                // Asked on the display's clock: frames are stamped with when their sound will be
                // heard, and one becomes current at a moment no timer knows in advance.
                val now = withFrameNanos { it }
                val latest = AudioReactive.frame()
                val next = when {
                    latest != null -> {
                        lastHeard = now
                        latest
                    }
                    // A gap (buffering, a skip) settles to quiet before it goes dark, so the
                    // light fades instead of vanishing mid-song.
                    lastHeard > 0 && now - lastHeard < SETTLE_MS * 1_000_000 -> QUIET
                    else -> null
                }
                if (next !== frame.value) frame.value = next
                if (mini) withFrameNanos { }
            }
        } finally {
            AudioReactive.release(owner)
            frame.value = null
        }
    }
    return frame
}

/** Nothing heard: the painter eases every band towards zero from wherever it was. */
private val QUIET = AudioLightFrame()

/** How long the light takes to settle after the music stops or stalls. */
private const val SETTLE_MS = 260L

@Composable
internal fun AudioLightHalo(
    frame: State<AudioLightFrame?>,
    /** Read in the draw pass, so a colour crossing over to the next record redraws, not recomposes. */
    color: () -> Color,
    cover: () -> Rect? = { null },
    modifier: Modifier = Modifier,
    mini: Boolean = false,
    compact: Boolean = false,
    bottomMix: () -> Float = { 0f },
    contrastColor: Color? = null,
    contrastMix: () -> Float = { 0f },
    clock: State<Long> = rememberAudioLightClock(frame, mini),
) {
    val painter = remember { AudioHaloPainter() }
    val bounds = remember { android.graphics.RectF() }
    val clip = remember { android.graphics.Path() }

    Canvas(modifier.fillMaxSize()) {
        clock.value
        val current = frame.value
        if (current == null) {
            painter.reset()
            return@Canvas
        }
        val rect = cover()
        if (rect != null) bounds.set(rect.left, rect.top, rect.right, rect.bottom)
        drawIntoCanvas {
            val native = it.nativeCanvas
            val saved = native.save()
            if (mini) {
                clip.reset()
                clip.addRoundRect(
                    0f,
                    0f,
                    size.width,
                    size.height,
                    size.height / 2,
                    size.height / 2,
                    android.graphics.Path.Direction.CW,
                )
                native.clipPath(clip)
            }
            painter.draw(
                native,
                size.width,
                size.height,
                current,
                color().let { tint ->
                    if (contrastColor == null) tint else lerp(tint, contrastColor, contrastMix().coerceIn(0f, 1f))
                }.toArgb(),
                bounds.takeIf { rect != null },
                mini,
                compact,
                bottomMix = bottomMix(),
            )
            native.restoreToCount(saved)
        }
    }
}

/**
 * The display's clock, while there is light to draw, for whatever has to redraw with the halo.
 *
 * The painter eases between analysis frames by the time that has passed, so it needs a redraw per
 * display frame — but not more than sixty a second (thirty for the pill): on a 120 Hz panel every
 * glass pane above the stage resamples on each redraw, and twice the frames buys no visible
 * smoothness here. The pill's glass reads the same clock, so it bends the halo as it is now and
 * not as it was at the last analysis frame.
 */
@Composable
internal fun rememberAudioLightClock(frame: State<AudioLightFrame?>, mini: Boolean): State<Long> {
    val lit by remember(frame) { derivedStateOf { frame.value != null } }
    val tick = remember { mutableLongStateOf(0L) }
    LaunchedEffect(lit, mini) {
        if (!lit) return@LaunchedEffect
        val interval = if (mini) MINI_REDRAW_NS else REDRAW_NS
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (now - last >= interval) {
                    last = now
                    tick.longValue = now
                }
            }
        }
    }
    return tick
}

/** Slightly under a 60 Hz frame, so a 60 Hz display never skips one. */
private const val REDRAW_NS = 15_000_000L
private const val MINI_REDRAW_NS = 31_000_000L

/** The requested violet/fuchsia light over Canvas and music video, independent of artwork hues. */
internal val CanvasAudioLightColor = Color(0xFFD36BE8)
