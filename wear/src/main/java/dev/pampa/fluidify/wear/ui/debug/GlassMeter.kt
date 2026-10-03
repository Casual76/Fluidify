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
 * The switch for the glass meter, in builds that are not released.
 *
 * Off by default even there: the meter is for checking, on the watch itself,
 * that a still screen costs the glass nothing.
 */
class GlassMeterPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("dev", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(available && prefs.getBoolean(KEY, false))

    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun set(on: Boolean) {
        prefs.edit { putBoolean(KEY, on) }
        _enabled.value = available && on
        FluidGlassDiagnostics.enabled = _enabled.value
    }

    init {
        FluidGlassDiagnostics.enabled = _enabled.value
    }

    companion object {
        private const val KEY = "glass_meter"

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
