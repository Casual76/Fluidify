// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.ui.airpods.host

import android.Manifest
import android.app.Activity
import android.companion.CompanionDeviceManager
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.lelonio.square.R
import dev.lelonio.square.airpods.runtime.android.*
import dev.lelonio.square.ui.airpods.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

class AirPodsHostBinding internal constructor(
    val binding: AirPodsUiBinding,
    internal val host: AirPodsHost,
    val quickVisible: Boolean,
    val quickOrigin: Rect?,
    val dismissQuick: () -> Unit,
)

@Composable
fun rememberAirPodsHostBinding(): AirPodsHostBinding {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Capture this composition's entry: retained callbacks must not read a replacement owner.
    val ingress = AirPodsIngressResolver.presentation.collectAsState(initial = null).value
    val owner = ingress?.owner ?: AirPodsAndroidRuntime.get()
    val host = remember(context.applicationContext, owner, ingress) {
        AirPodsHost(context.applicationContext, owner, functionalAuthority = ingress?.authority,
            ingressCurrent = { if (ingress != null) ingress.current() else
                AirPodsIngressResolver.resolve() === owner })
    }
    DisposableEffect(ingress, lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            ingress?.hostVisible?.invoke(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        lifecycle.addObserver(observer)
        ingress?.hostVisible?.invoke(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose { lifecycle.removeObserver(observer); ingress?.hostVisible?.invoke(false) }
    }
    val scope = rememberCoroutineScope()
    val state by host.state.collectAsState()
    var quick by remember(host) { mutableStateOf(false) }
    var origin by remember(host) { mutableStateOf<Rect?>(null) }
    fun result() { scope.launch { host.permissionsChanged() } }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result() }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result() }
    val association = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result() }
    val failureText = stringResource(R.string.airpods_host_platform_failure)
    val tileText = stringResource(R.string.airpods_host_tile)
    fun platform(action: SetupAction, pair: dev.lelonio.square.airpods.runtime.storage.PairAddress?) {
        runCatching {
            when(action) {
                SetupAction.BLUETOOTH_PERMISSION -> if(Build.VERSION.SDK_INT >= 31) permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
                SetupAction.NOTIFICATION_PERMISSION -> if(Build.VERSION.SDK_INT >= 33) permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                SetupAction.CALL_PERMISSION -> permissions.launch(arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS))
                SetupAction.MEDIA_ACCESS -> settings.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                SetupAction.BLUETOOTH_SETTINGS -> settings.launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                SetupAction.COMPANION_ASSOCIATION -> {
                    val owner = requireNotNull(host.owner)
                    val selected = requireNotNull(pair)
                    val captured = host.stamp
                    owner.companionRegistry.associate(selected, object: CompanionDeviceManager.Callback() {
                        @Deprecated("Legacy CDM callback")
                        override fun onDeviceFound(chooserLauncher: IntentSender) { launchChooser(chooserLauncher) }
                        override fun onAssociationPending(intentSender: IntentSender) { launchChooser(intentSender) }
                        private fun launchChooser(sender: IntentSender) {
                            scope.launch {
                                val current = withContext(Dispatchers.IO) { owner.store.snapshot().getOrNull() }
                                if(AirPodsIngressResolver.resolve() === owner && current != null && captured?.matches(current, owner.state.value.generation) == true)
                                    association.launch(IntentSenderRequest.Builder(sender).build())
                            }
                        }
                        override fun onFailure(error: CharSequence?) { Toast.makeText(context, failureText, Toast.LENGTH_LONG).show(); result() }
                        override fun onAssociationCreated(info: android.companion.AssociationInfo) { result() }
                    }, Handler(Looper.getMainLooper()))
                }
                SetupAction.EXPORT_DIAGNOSTICS -> {
                    val owner = requireNotNull(host.owner)
                    settings.launch(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, owner.sanitizedDiagnostics() + "\n" + owner.diagnostics.export()), null))
                }
                SetupAction.TILE_INSTRUCTIONS -> Toast.makeText(context, tileText, Toast.LENGTH_LONG).show()
                else -> error("UNAVAILABLE_ACTION")
            }
        }.onFailure { Toast.makeText(context, failureText, Toast.LENGTH_LONG).show(); result() }
    }
    LaunchedEffect(host, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            host.refreshCandidates()
            launch { host.owner?.state?.collect { host.refresh() } }
            launch { host.owner?.runtime?.collect { host.refresh() } }
            launch { host.owner?.volume?.collect { host.refresh() } }
            launch { host.owner?.diagnostics?.records?.collect { host.refresh() } }
            // Refresh durable configuration and battery age while UI is started; never reconnect.
            while(isActive) { host.refresh(); delay(1_000) }
        }
    }
    DisposableEffect(host) { onDispose { host.close() } }
    val captured = host.stamp
    val binding = AirPodsUiBinding(state,
        dispatch = { action -> scope.launch {
            try { host.dispatch(captured, action, ::platform) }
            catch(e: CancellationException) { throw e }
            catch(_: Exception) { Toast.makeText(context, failureText, Toast.LENGTH_LONG).show(); host.refresh() }
        } },
        openQuick = { origin = null; quick = true },
        openQuickFrom = { rect -> origin = rect; quick = true })
    return AirPodsHostBinding(binding, host, quick, origin, { quick = false })
}

@Composable
fun AirPodsHostPage(host: AirPodsHostBinding, padding: PaddingValues, backdrop: GlassBackdropState,
    active: Boolean, onBack: () -> Unit) {
    DisposableEffect(host.host, active) {
        host.host.owner?.setSetupVisible(active)
        onDispose { host.host.owner?.setSetupVisible(false) }
    }
    // The main page and the page behind one of its rows; the back closes the inner one first.
    AirPodsSettingsPages(host.binding.state, host.binding.dispatch, padding, backdrop, onBack,
        notice = if(host.host.owner == null) stringResource(R.string.airpods_host_absent) else null)
}

/** One portal in SquareApp's existing modal host; no new backdrop recorder. */
@Composable
fun AirPodsHostQuick(host: AirPodsHostBinding, openSettings: () -> Unit) {
    AirPodsQuickOverlay(host.binding.state, host.quickVisible, host.dismissQuick,
        host.binding.dispatch, { host.dismissQuick(); openSettings() }, origin = { host.quickOrigin })
}

/** Root places this once beside rememberAirPodsHostBinding, including onboarding.
 * Independent stop remains reachable when data/stamps/lease are stale or setup is still NV. */
@Composable
fun AirPodsQualificationIndicator() {
    val entry by AirPodsIngressResolver.selection.collectAsState()
    val current = entry ?: return
    androidx.compose.ui.window.Popup(alignment = androidx.compose.ui.Alignment.TopCenter) {
        androidx.compose.material3.Surface(shape = androidx.compose.material3.MaterialTheme.shapes.medium) {
            Row(Modifier.windowInsetsPadding(WindowInsets.statusBars), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("TEST / NV AirPods", style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                dev.antigravity.fluidengine.ui.fluid.FluidButton("Arresta TEST", current.stop)
            }
        }
    }
}
