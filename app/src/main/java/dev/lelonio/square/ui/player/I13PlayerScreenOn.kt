package dev.lelonio.square.ui.player

import android.view.View
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Owns only this view's request; it never clears another surface's keep-screen-on flag. */
internal class I13ScreenOnRequest(private val view: View) {
    fun update(enabled: Boolean, fullscreen: Boolean, playing: Boolean, resumed: Boolean) {
        val requested = enabled && fullscreen && playing && resumed
        if (view.keepScreenOn != requested) view.keepScreenOn = requested
    }

    fun release() { view.keepScreenOn = false }
}

/** No polling, service or WakeLock. A pause/background/disposal releases the view immediately. */
@Composable
internal fun I13PlayerScreenOn(enabled: Boolean, isPlaying: Boolean, fullscreen: () -> Boolean) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = remember(context) { View(context).apply { tag = "wf_i13_screen_on" } }
    val request = remember(view) { I13ScreenOnRequest(view) }
    val currentFullscreen by rememberUpdatedState(fullscreen)
    // Read the morph in this small composable, never in the whole player or queue.
    val full by remember { derivedStateOf { currentFullscreen() } }
    val currentEnabled by rememberUpdatedState(enabled)
    val currentPlaying by rememberUpdatedState(isPlaying)
    val currentFull by rememberUpdatedState(full)

    DisposableEffect(lifecycle, request) {
        val observer = LifecycleEventObserver { _, _ ->
            request.update(currentEnabled, currentFull, currentPlaying,
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            request.release()
        }
    }
    AndroidView(
        factory = { view },
        modifier = Modifier.size(0.dp),
        update = {
            request.update(enabled, full, isPlaying,
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        },
    )
}
