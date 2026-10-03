package dev.pampa.fluidify.wear.ui.debug

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import dev.antigravity.fluidengine.ui.glass.backdrop.FluidGlassDiagnostics
import dev.pampa.fluidify.wear.BuildConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Developer mode and the glass meter, in builds that are not released.
 *
 * Both off by default. Developer mode is unlocked by tapping the version seven times in "More",
 * the way Android's own developer options are, and only then does the meter's switch appear: the
 * meter is for checking, on the watch itself, that a still screen costs the glass nothing, and the
 * person using the watch should never meet it by accident.
 */
class GlassMeterPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("dev", Context.MODE_PRIVATE)
    private val _developer = MutableStateFlow(available && prefs.getBoolean(KEY_DEVELOPER, false))
    private val _enabled = MutableStateFlow(available && prefs.getBoolean(KEY, false))
    private val _visible = MutableStateFlow(_developer.value && _enabled.value)
    private var taps = 0

    /** Whether the developer section is showing. */
    val developer: StateFlow<Boolean> = _developer.asStateFlow()

    /** The meter's switch. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Whether the meter is on screen: switched on, in developer mode. */
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    fun set(on: Boolean) {
        prefs.edit { putBoolean(KEY, on) }
        _enabled.value = available && on
        refresh()
    }

    /** One tap on the version. Answers true on the tap that unlocks developer mode. */
    fun tapVersion(): Boolean {
        if (!available || _developer.value) return false
        taps++
        if (taps < TAPS_TO_UNLOCK) return false
        prefs.edit { putBoolean(KEY_DEVELOPER, true) }
        _developer.value = true
        refresh()
        return true
    }

    /** Leaves developer mode, and takes the meter with it. */
    fun leaveDeveloper() {
        prefs.edit { putBoolean(KEY_DEVELOPER, false).putBoolean(KEY, false) }
        taps = 0
        _developer.value = false
        _enabled.value = false
        refresh()
    }

    private fun refresh() {
        _visible.value = _developer.value && _enabled.value
        FluidGlassDiagnostics.enabled = _visible.value
    }

    init {
        // The meter's first key was on by itself on watches that had tried it; it is gone, so
        // nobody carries an old switch into this version.
        if (prefs.contains(OLD_KEY)) prefs.edit { remove(OLD_KEY) }
        refresh()
    }

    companion object {
        private const val KEY = "glass_meter_v2"
        private const val OLD_KEY = "glass_meter"
        private const val KEY_DEVELOPER = "developer"
        private const val TAPS_TO_UNLOCK = 7

        /** Only debug and dev builds carry the meter. */
        val available: Boolean get() = BuildConfig.BUILD_TYPE != "release"
    }
}

/**
 * How many times the glass re-captured what is behind it in the last second,
 * and since the meter was switched on.
 *
 * Drawn above everything and outside every glass source, so the meter's own
 * once-a-second change is not a reason for the glass to work. On a still
 * player the first number should sit at 0; it may move while something moves
 * and must drop back when it stops.
 */
@Composable
fun GlassMeter(modifier: Modifier = Modifier) {
    var total by remember { mutableLongStateOf(FluidGlassDiagnostics.totalCaptures) }
    var perSecond by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        var last = FluidGlassDiagnostics.totalCaptures
        while (true) {
            delay(1_000)
            val now = FluidGlassDiagnostics.totalCaptures
            perSecond = now - last
            total = now
            last = now
        }
    }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Text(
            text = "◎ $perSecond/s · $total",
            style = MaterialTheme.typography.labelSmall,
            color = if (perSecond == 0L) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(bottom = 6.dp),
        )
    }
}
