package dev.pampa.fluidify.wear.ui.player

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.wear.ambient.LocalFluidWearAmbient
import dev.antigravity.fluidengine.wear.components.FluidWearList
import dev.antigravity.fluidengine.wear.components.FluidWearListRow
import dev.pampa.fluidify.wear.R
import dev.pampa.fluidify.wear.link.LinkStatus
import dev.pampa.fluidify.wear.link.PhoneAccountSession
import dev.pampa.fluidify.wear.playback.PlaybackControls
import dev.pampa.fluidify.wear.protocol.PlaybackSource
import dev.pampa.fluidify.wear.ui.common.screenStarted
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/** Read-only: opening this screen never calls PlaybackControls or asks the phone for telemetry. */
@Composable
fun AirPodsBatteryScreen(controls: PlaybackControls) {
    val now by controls.nowPlaying.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val accounts = remember(context) { PhoneAccountSession.forContext(context) }
    val account by accounts.scope.collectAsStateWithLifecycle()
    val started = screenStarted()
    val ambient = LocalFluidWearAmbient.current
    var expiry by remember { mutableIntStateOf(0) }
    val receipt = now.received?.airPodsBatteryReceipt
    val leaseValid by (receipt?.lease?.current ?: remember { MutableStateFlow(false) }).collectAsStateWithLifecycle()
    val elapsed = SystemClock.elapsedRealtime()
    val model = remember(now, account, started, ambient.isAmbient, ambient.updateTick, expiry, leaseValid) {
        airPodsBatteryModel(now.received, now.link, account?.session, System.currentTimeMillis(), elapsed)
    }
    // One deadline per observed component, only while visible. This expires local display;
    // it neither polls the sensor/link nor keeps the watch or phone awake.
    LaunchedEffect(receipt, account, now.link, started, ambient.isAmbient, expiry, leaseValid) {
        if (started && !ambient.isAmbient && model.available) {
            val next = listOfNotNull(receipt?.leftExpiresAtElapsedMs, receipt?.rightExpiresAtElapsedMs,
                receipt?.caseExpiresAtElapsedMs).filter { it > SystemClock.elapsedRealtime() }.minOrNull()
            if (next != null) {
                delay((next - SystemClock.elapsedRealtime()).coerceAtLeast(1))
                expiry++
            }
        }
    }
    AirPodsBatteryContent(model, when {
        now.snapshot?.source == PlaybackSource.WATCH -> R.string.wf_i15_phone_headphones_only
        now.link != LinkStatus.CONNECTED -> R.string.wf_i15_phone_unavailable
        else -> R.string.wf_i15_no_fresh_reading
    })
}

@Composable
internal fun AirPodsBatteryContent(model: AirPodsBatteryModel, unavailable: Int) {
    FluidWearList(title = stringResource(R.string.wf_i15_headphones)) {
        item(key = "phone-headphones") {
            FluidWearListRow(title = stringResource(R.string.wf_i15_airpods),
                subtitle = stringResource(if (model.available) R.string.wf_i15_from_phone else unavailable))
        }
        item(key = "left") { BatteryRow(R.string.wf_i15_left, model.left) }
        item(key = "right") { BatteryRow(R.string.wf_i15_right, model.right) }
        // An unobserved/expired case is absent, never a made-up zero or a claim it is connected.
        model.case?.let { percent -> item(key = "case") { BatteryRow(R.string.wf_i15_case, percent) } }
    }
}

@Composable
private fun BatteryRow(label: Int, percent: Int?) {
    FluidWearListRow(
        title = stringResource(label),
        subtitle = if (percent == null) stringResource(R.string.wf_i15_unknown)
            else stringResource(R.string.wf_i15_percent, percent),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}
